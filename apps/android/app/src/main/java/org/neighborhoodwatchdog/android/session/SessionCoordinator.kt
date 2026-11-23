package org.neighborhoodwatchdog.android.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.auth.AuthRepository
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.data.repository.WatchdogRepository
import org.neighborhoodwatchdog.android.monitoring.ClearMode
import org.neighborhoodwatchdog.android.monitoring.MonitoringController

enum class OnboardingStage { CONSENT, SIGN_IN, MEMBERSHIP, DEVICE_SETUP, HOME_NETWORK, READY }

/** Bumped whenever something changed that decides which screen the app must show. */
class SessionSignal {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun bump() {
        _version.value = _version.value + 1
    }
}

class SessionCoordinator(
    private val auth: AuthRepository,
    private val repository: WatchdogRepository,
    private val controller: MonitoringController,
    private val localState: LocalState,
    val signal: SessionSignal,
) {
    /** Onboarding order from the client protocol. Monitoring never starts before the last step. */
    fun stage(): OnboardingStage = when {
        !localState.consentGiven -> OnboardingStage.CONSENT
        !auth.isSignedIn -> OnboardingStage.SIGN_IN
        !localState.memberConfirmed -> OnboardingStage.MEMBERSHIP
        !localState.deviceRegistered || !localState.notificationStepDone -> OnboardingStage.DEVICE_SETUP
        !localState.homeStepDone -> OnboardingStage.HOME_NETWORK
        else -> OnboardingStage.READY
    }

    /** The server said this account is not a household member (any more). */
    fun membershipLost() {
        localState.memberConfirmed = false
        signal.bump()
    }

    suspend fun signOut() {
        controller.stopAndClear(ClearMode.SIGN_OUT)
        auth.signOut()
        signal.bump()
    }

    /** After the server confirmed: stop monitoring and clear tokens, queue, fingerprint and install id. */
    suspend fun leaveNeighborhood(): ApiResult<Unit> = forget { repository.leave() }

    suspend fun deleteAccount(): ApiResult<Unit> = forget { repository.deleteAccount() }

    private suspend fun forget(call: suspend () -> ApiResult<*>): ApiResult<Unit> =
        when (val result = call()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                controller.stopAndClear(ClearMode.FORGET_EVERYTHING)
                auth.signOut()
                signal.bump()
                ApiResult.Success(Unit)
            }
        }
}
