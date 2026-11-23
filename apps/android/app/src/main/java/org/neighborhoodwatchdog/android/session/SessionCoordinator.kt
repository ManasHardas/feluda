package org.neighborhoodwatchdog.android.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.data.repository.WatchdogRepository
import org.neighborhoodwatchdog.android.domain.auth.DeviceCredentials
import org.neighborhoodwatchdog.android.monitoring.ClearMode
import org.neighborhoodwatchdog.android.monitoring.MonitoringController

/** INTRO: what the app does and the privacy summary. JOIN: invitation link, street, optional house number. */
enum class OnboardingStage { INTRO, JOIN, NOTIFICATIONS, HOME_NETWORK, READY }

/** Bumped whenever something changed that decides which screen the app must show. */
class SessionSignal {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun bump() {
        _version.value = _version.value + 1
    }
}

/**
 * The invitation token from a `feluda://join#t=<token>` link that opened the app, kept until the
 * join screen takes it. Only held in memory.
 */
class InvitationInbox {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun offer(token: String) {
        _pending.value = token
    }

    fun take(): String? = _pending.value.also { _pending.value = null }
}

class SessionCoordinator(
    private val credentials: DeviceCredentials,
    private val repository: WatchdogRepository,
    private val controller: MonitoringController,
    private val localState: LocalState,
    val signal: SessionSignal,
) {
    /**
     * Onboarding order. There is no sign-in: a phone is enrolled when it holds a device secret.
     * Monitoring never starts before the last step.
     */
    fun stage(): OnboardingStage = when {
        !localState.consentGiven -> OnboardingStage.INTRO
        !credentials.isEnrolled -> OnboardingStage.JOIN
        !localState.notificationStepDone -> OnboardingStage.NOTIFICATIONS
        !localState.homeStepDone -> OnboardingStage.HOME_NETWORK
        else -> OnboardingStage.READY
    }

    /**
     * Leave: the server deletes this phone and everything it reported. Only after it confirmed,
     * monitoring stops and the secret, queue, fingerprint and install id are removed from the phone.
     */
    suspend fun leaveNeighborhood(): ApiResult<Unit> =
        when (val result = repository.leave()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                controller.stopAndClear(ClearMode.FORGET_EVERYTHING)
                credentials.clear()
                signal.bump()
                ApiResult.Success(Unit)
            }
        }
}
