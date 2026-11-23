package org.neighborhoodwatchdog.android.ui.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.neighborhoodwatchdog.android.R
import org.neighborhoodwatchdog.android.config.AppConfig
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.data.repository.DeviceRepository
import org.neighborhoodwatchdog.android.data.repository.WatchdogRepository
import org.neighborhoodwatchdog.android.domain.home.InvitationToken
import org.neighborhoodwatchdog.android.monitoring.HomeNetworkManager
import org.neighborhoodwatchdog.android.monitoring.MonitoringController
import org.neighborhoodwatchdog.android.monitoring.PermissionStatus
import org.neighborhoodwatchdog.android.push.PushManager
import org.neighborhoodwatchdog.android.push.PushSyncResult
import org.neighborhoodwatchdog.android.session.InvitationInbox
import org.neighborhoodwatchdog.android.session.SessionCoordinator
import org.neighborhoodwatchdog.android.ui.common.errorMessage
import javax.inject.Inject

@HiltViewModel
class IntroViewModel @Inject constructor(
    private val localState: LocalState,
    private val session: SessionCoordinator,
) : ViewModel() {
    /** The introduction was read. Nothing is monitored or sent until the phone has also joined. */
    fun continueToJoin() {
        localState.consentGiven = true
        session.signal.bump()
    }
}

data class JoinState(
    /** What the resident pasted: the whole invitation link or only the token. */
    val link: String = "",
    /** Set once the server said the invitation is valid; then the street is asked. */
    val token: String? = null,
    val neighborhoodName: String? = null,
    val streetName: String = "",
    val houseNumber: String = "",
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
    /** An explanation written by the server, shown as it is (for example: this address already takes part). */
    val serverMessage: String? = null,
    /** This phone was enrolled before and the server no longer accepts it. */
    val enrolmentEnded: Boolean = false,
)

/**
 * Joining needs an invitation link, the street and, if the resident wishes, a house number.
 * Nothing else is asked. The link arrives from the web join page (`feluda://join#t=...`) or is pasted.
 */
@HiltViewModel
class JoinViewModel @Inject constructor(
    private val repository: WatchdogRepository,
    private val devices: DeviceRepository,
    private val invitations: InvitationInbox,
    private val session: SessionCoordinator,
    localState: LocalState,
    val config: AppConfig,
) : ViewModel() {
    private val _state = MutableStateFlow(JoinState(enrolmentEnded = localState.enrolmentEndedNotice))
    val state: StateFlow<JoinState> = _state

    init {
        // A link that opened the app (also while this screen is already shown) is checked at once.
        viewModelScope.launch {
            invitations.pending.collect { pending ->
                if (pending != null) invitations.take()?.let { verify(it) }
            }
        }
    }

    fun setLink(value: String) = _state.update { it.copy(link = value.take(MAX_LINK_LENGTH), error = null, serverMessage = null) }
    fun setStreet(value: String) = _state.update { it.copy(streetName = value.take(MAX_STREET_LENGTH), error = null, serverMessage = null) }
    fun setHouseNumber(value: String) = _state.update { it.copy(houseNumber = value.take(MAX_HOUSE_NUMBER_LENGTH), error = null, serverMessage = null) }

    /** Back to the paste field, to use another link. */
    fun changeLink() = _state.update { it.copy(token = null, neighborhoodName = null, link = "", error = null, serverMessage = null) }

    fun submitLink() {
        val token = InvitationToken.parse(_state.value.link)
        if (token == null) {
            _state.update { it.copy(error = R.string.join_error_invitation_format) }
            return
        }
        viewModelScope.launch { verify(token) }
    }

    /** POST /auth/verify-invitation: shows the neighborhood name before the street is asked. */
    private suspend fun verify(token: String) {
        _state.update { it.copy(busy = true, error = null, serverMessage = null) }
        when (val result = repository.verifyInvitation(token)) {
            is ApiResult.Success -> _state.update {
                it.copy(busy = false, token = token, neighborhoodName = result.value.neighborhoodName, link = "")
            }
            is ApiResult.Failure -> _state.update {
                it.copy(busy = false, token = null, neighborhoodName = null, error = errorMessage(result.error))
            }
        }
    }

    /** POST /enroll. Pressing "Join" is the acknowledgement of the privacy summary shown above the button. */
    fun join() {
        val current = _state.value
        val token = current.token ?: return
        if (current.busy) return
        if (current.streetName.isBlank()) {
            _state.update { it.copy(error = R.string.join_error_street) }
            return
        }
        _state.update { it.copy(busy = true, error = null, serverMessage = null) }
        viewModelScope.launch {
            when (val result = devices.enroll(token, current.streetName, current.houseNumber)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    session.signal.bump()
                }
                is ApiResult.Failure -> {
                    val error = result.error
                    val fromServer = error.message?.takeIf { error.code == "household_already_registered" && it.isNotBlank() }
                    _state.update {
                        it.copy(busy = false, error = if (fromServer == null) errorMessage(error) else null, serverMessage = fromServer)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_LINK_LENGTH = 512
        const val MAX_STREET_LENGTH = 80
        const val MAX_HOUSE_NUMBER_LENGTH = 16
    }
}

enum class PushSetup { NOT_TRIED, NOT_CONFIGURED, REGISTERED, FAILED }

data class NotificationSetupState(
    val notificationsGranted: Boolean = false,
    val push: PushSetup = PushSetup.NOT_TRIED,
    val pushConfigured: Boolean = false,
)

/** The phone is already enrolled here; this step only explains notifications and asks for the permission. */
@HiltViewModel
class NotificationSetupViewModel @Inject constructor(
    private val pushManager: PushManager,
    private val permissions: PermissionStatus,
    private val localState: LocalState,
    private val session: SessionCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(
        NotificationSetupState(notificationsGranted = permissions.notificationsGranted, pushConfigured = pushManager.configured),
    )
    val state: StateFlow<NotificationSetupState> = _state

    init {
        viewModelScope.launch { syncPush() }
    }

    /** Called after the system permission dialog closed, whatever the answer was. */
    fun onNotificationPermissionResult() {
        _state.update { it.copy(notificationsGranted = permissions.notificationsGranted) }
        viewModelScope.launch { syncPush() }
    }

    private suspend fun syncPush() {
        val outcome = when (pushManager.syncToken()) {
            PushSyncResult.NotConfigured -> PushSetup.NOT_CONFIGURED
            PushSyncResult.Registered -> PushSetup.REGISTERED
            is PushSyncResult.Failed -> PushSetup.FAILED
        }
        _state.update { it.copy(push = outcome) }
    }

    fun finish() {
        localState.notificationStepDone = true
        localState.lastNotificationPermission = permissions.notificationsGranted && permissions.monitoringPermissionsGranted
        session.signal.bump()
    }
}

data class HomeNetworkState(val onWifi: Boolean = false, @StringRes val error: Int? = null)

@HiltViewModel
class HomeNetworkViewModel @Inject constructor(
    private val home: HomeNetworkManager,
    private val localState: LocalState,
    private val controller: MonitoringController,
    private val session: SessionCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeNetworkState(onWifi = home.currentWifiIdentity() != null))
    val state: StateFlow<HomeNetworkState> = _state

    fun refresh() = _state.update { it.copy(onWifi = home.currentWifiIdentity() != null, error = null) }

    /** "This phone is on my home Wi-Fi now": store the fingerprint locally, then start monitoring. */
    fun confirm() {
        if (!home.confirmCurrentNetworkAsHome()) {
            _state.update { it.copy(onWifi = false, error = R.string.home_error_no_wifi) }
            return
        }
        start()
    }

    /** Skipped: `home_network_match` stays null in every report. */
    fun skip() = start()

    private fun start() {
        localState.homeStepDone = true
        controller.ensureRunning()
        session.signal.bump()
    }
}
