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
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.auth.AuthRepository
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.data.repository.DeviceRepository
import org.neighborhoodwatchdog.android.data.repository.WatchdogRepository
import org.neighborhoodwatchdog.android.domain.home.InvitationToken
import org.neighborhoodwatchdog.android.domain.model.Isp
import org.neighborhoodwatchdog.android.monitoring.HomeNetworkManager
import org.neighborhoodwatchdog.android.monitoring.MonitoringController
import org.neighborhoodwatchdog.android.monitoring.PermissionStatus
import org.neighborhoodwatchdog.android.push.PushManager
import org.neighborhoodwatchdog.android.push.PushSyncResult
import org.neighborhoodwatchdog.android.session.SessionCoordinator
import org.neighborhoodwatchdog.android.ui.common.errorMessage
import javax.inject.Inject

@HiltViewModel
class ConsentViewModel @Inject constructor(
    private val localState: LocalState,
    private val session: SessionCoordinator,
) : ViewModel() {
    /** Recorded only after the resident ticked the box and pressed the button. */
    fun giveConsent() {
        localState.consentGiven = true
        session.signal.bump()
    }
}

data class SignInState(
    val email: String = "",
    val code: String = "",
    val codeRequested: Boolean = false,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val session: SessionCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state

    fun setEmail(value: String) = _state.update { it.copy(email = value, error = null) }
    fun setCode(value: String) = _state.update { it.copy(code = value.filter(Char::isLetterOrDigit).take(10), error = null) }
    fun changeEmail() = _state.update { it.copy(codeRequested = false, code = "", error = null) }

    fun requestCode() {
        val email = _state.value.email.trim()
        if (!email.contains('@') || email.length < 5) {
            _state.update { it.copy(error = R.string.sign_in_error_email) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = auth.requestCode(email)) {
                is ApiResult.Success -> _state.update { it.copy(busy = false, codeRequested = true) }
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        busy = false,
                        error = when {
                            result.error.isNetwork -> R.string.error_network
                            result.error.httpCode == 429 -> R.string.error_rate_limited
                            else -> R.string.sign_in_error_send
                        },
                    )
                }
            }
        }
    }

    fun verify() {
        val current = _state.value
        if (current.code.isBlank()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = auth.verifyCode(current.email, current.code)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    session.signal.bump()
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        busy = false,
                        error = when {
                            result.error.isNetwork -> R.string.error_network
                            result.error.httpCode == 429 -> R.string.error_rate_limited
                            else -> R.string.sign_in_error_code
                        },
                    )
                }
            }
        }
    }
}

data class MembershipState(
    /** Asking the server whether this account already belongs to a household. */
    val checking: Boolean = true,
    val showJoinForm: Boolean = false,
    val invitation: String = "",
    val houseNumber: String = "",
    val streetName: String = "",
    val displayLabel: String = "",
    val isp: Isp? = null,
    val privacyAcknowledged: Boolean = false,
    val neighborhoodName: String? = null,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
)

@HiltViewModel
class MembershipViewModel @Inject constructor(
    private val repository: WatchdogRepository,
    private val session: SessionCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(MembershipState())
    val state: StateFlow<MembershipState> = _state

    init {
        check()
    }

    /** GET /households/me: a member continues; `not_a_member` shows the invitation form. */
    fun check() {
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.household()) {
                is ApiResult.Success -> session.signal.bump()
                is ApiResult.Failure -> _state.update {
                    if (result.error.code == "not_a_member") {
                        it.copy(checking = false, showJoinForm = true)
                    } else {
                        it.copy(checking = false, showJoinForm = false, error = errorMessage(result.error))
                    }
                }
            }
        }
    }

    fun edit(transform: (MembershipState) -> MembershipState) = _state.update { transform(it).copy(error = null) }

    fun join() {
        val current = _state.value
        val token = InvitationToken.parse(current.invitation)
        val problem = when {
            token == null -> R.string.join_error_invitation_format
            current.houseNumber.isBlank() || current.streetName.isBlank() -> R.string.join_error_address
            !current.privacyAcknowledged -> R.string.join_error_privacy
            else -> null
        }
        if (token == null || problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val verified = repository.verifyInvitation(token)) {
                is ApiResult.Failure -> {
                    _state.update { it.copy(busy = false, error = errorMessage(verified.error)) }
                    return@launch
                }
                is ApiResult.Success -> _state.update { it.copy(neighborhoodName = verified.value.neighborhoodName) }
            }
            val result = repository.join(
                token = token,
                houseNumber = current.houseNumber,
                streetName = current.streetName,
                displayLabel = current.displayLabel,
                isp = current.isp,
                privacyAcknowledged = current.privacyAcknowledged,
            )
            when (result) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    session.signal.bump()
                }
                is ApiResult.Failure -> _state.update { it.copy(busy = false, error = errorMessage(result.error)) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }
}

enum class PushSetup { NOT_TRIED, NOT_CONFIGURED, REGISTERED, FAILED }

data class DeviceSetupState(
    val registering: Boolean = true,
    val registered: Boolean = false,
    @StringRes val error: Int? = null,
    val notificationsGranted: Boolean = false,
    val push: PushSetup = PushSetup.NOT_TRIED,
    val pushConfigured: Boolean = false,
)

@HiltViewModel
class DeviceSetupViewModel @Inject constructor(
    private val devices: DeviceRepository,
    private val pushManager: PushManager,
    private val permissions: PermissionStatus,
    private val localState: LocalState,
    private val session: SessionCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(
        DeviceSetupState(notificationsGranted = permissions.notificationsGranted, pushConfigured = pushManager.configured),
    )
    val state: StateFlow<DeviceSetupState> = _state

    init {
        register()
    }

    /** POST /devices/register with the random install id and the real capability flags. */
    fun register() {
        _state.update { it.copy(registering = true, error = null) }
        viewModelScope.launch {
            when (val result = devices.register()) {
                is ApiResult.Success -> {
                    _state.update { it.copy(registering = false, registered = true) }
                    syncPush()
                }
                is ApiResult.Failure -> {
                    if (result.error.code == "not_a_member") {
                        session.membershipLost()
                    } else {
                        _state.update { it.copy(registering = false, error = errorMessage(result.error)) }
                    }
                }
            }
        }
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
