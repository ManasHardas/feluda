package org.neighborhoodwatchdog.android.data.repository

import android.content.Context
import android.content.pm.PackageManager
import org.neighborhoodwatchdog.android.config.AppConfig
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.ConnectivityStatusOut
import org.neighborhoodwatchdog.android.data.api.DeviceCapabilities
import org.neighborhoodwatchdog.android.data.api.DeviceOut
import org.neighborhoodwatchdog.android.data.api.EnrollRequest
import org.neighborhoodwatchdog.android.data.api.EnrollResponse
import org.neighborhoodwatchdog.android.data.api.HouseholdOut
import org.neighborhoodwatchdog.android.data.api.IncidentDetailOut
import org.neighborhoodwatchdog.android.data.api.IncidentListOut
import org.neighborhoodwatchdog.android.data.api.NeighborhoodOut
import org.neighborhoodwatchdog.android.data.api.NotificationPreferences
import org.neighborhoodwatchdog.android.data.api.OkResponse
import org.neighborhoodwatchdog.android.data.api.RegisterDeviceRequest
import org.neighborhoodwatchdog.android.data.api.RegisterPushTokenRequest
import org.neighborhoodwatchdog.android.data.api.RevokeDeviceRequest
import org.neighborhoodwatchdog.android.data.api.UpdateHouseholdRequest
import org.neighborhoodwatchdog.android.data.api.VerifyInvitationRequest
import org.neighborhoodwatchdog.android.data.api.VerifyInvitationResponse
import org.neighborhoodwatchdog.android.data.api.WatchdogApi
import org.neighborhoodwatchdog.android.data.api.apiCall
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.domain.auth.DeviceCredentials
import org.neighborhoodwatchdog.android.domain.model.BackgroundMonitoring
import org.neighborhoodwatchdog.android.domain.model.Isp
import org.neighborhoodwatchdog.android.domain.model.Platform
import org.neighborhoodwatchdog.android.domain.model.PushProvider

/** Household, neighborhood, incident and preference calls. Authorization is enforced by the backend. */
class WatchdogRepository(private val api: WatchdogApi) {
    suspend fun household(): ApiResult<HouseholdOut> = apiCall { api.household() }

    /** Public: tells whether the link is usable and which neighborhood it belongs to. */
    suspend fun verifyInvitation(token: String): ApiResult<VerifyInvitationResponse> =
        apiCall { api.verifyInvitation(VerifyInvitationRequest(token)) }

    suspend fun updateHousehold(displayLabel: String?, isp: Isp?): ApiResult<HouseholdOut> =
        apiCall { api.updateHousehold(UpdateHouseholdRequest(displayLabel?.trim()?.takeIf { it.isNotEmpty() }, isp)) }

    /** Deletes this phone and everything it reported on the server. Afterwards the secret is invalid. */
    suspend fun leave(): ApiResult<OkResponse> = apiCall { api.leaveHousehold() }
    suspend fun neighborhood(): ApiResult<NeighborhoodOut> = apiCall { api.neighborhood() }
    suspend fun status(): ApiResult<ConnectivityStatusOut> = apiCall { api.connectivityStatus() }
    suspend fun incidents(openOnly: Boolean, limit: Int = 50): ApiResult<IncidentListOut> =
        apiCall { api.incidents(if (openOnly) "open" else "all", limit) }

    suspend fun incident(id: String): ApiResult<IncidentDetailOut> = apiCall { api.incident(id) }
    suspend fun preferences(): ApiResult<NotificationPreferences> = apiCall { api.notificationPreferences() }
    suspend fun savePreferences(preferences: NotificationPreferences): ApiResult<NotificationPreferences> =
        apiCall { api.updateNotificationPreferences(preferences) }
}

class DeviceRepository(
    private val context: Context,
    private val api: WatchdogApi,
    private val localState: LocalState,
    private val config: AppConfig,
    private val credentials: DeviceCredentials,
) {
    /** What this phone can really measure. Sent at enrolment and when the app or OS version changes. */
    fun capabilities(): DeviceCapabilities = DeviceCapabilities(
        canMeasureRssi = true,
        canDetectBand = true,
        canProbeGateway = true,
        canRouteOverCellular = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
        canDetectPowerSource = true,
        canDetectWifiRadioState = true,
        canIdentifyHomeNetwork = true,
        backgroundMonitoring = BackgroundMonitoring.CALLBACK,
    )

    private fun versionMark(): String = config.osVersion + "|" + config.appVersion

    /**
     * POST /enroll: the only step that makes this phone known to the server. The resident typed the
     * street and, if they wished, a house number; pressing "Join" is the privacy acknowledgement.
     * The returned secret goes straight into the Keystore-encrypted store. Retrying with the same
     * link is safe: the install id stays the same until enrolment succeeded.
     */
    suspend fun enroll(invitationToken: String, streetName: String, houseNumber: String?): ApiResult<EnrollResponse> = apiCall {
        api.enroll(
            EnrollRequest(
                invitationToken = invitationToken,
                streetName = streetName.trim(),
                houseNumber = houseNumber?.trim()?.takeIf { it.isNotEmpty() },
                privacyAcknowledged = true,
                deviceId = localState.installId(),
                platform = Platform.ANDROID,
                osVersion = config.osVersion,
                appVersion = config.appVersion,
                capabilities = capabilities(),
            ),
        )
    }.also {
        if (it is ApiResult.Success) {
            credentials.save(it.value.deviceToken)
            localState.reportedDeviceVersion = versionMark()
            localState.enrolmentEndedNotice = false
        }
    }

    /** POST /devices/register: updates this phone's own record. It cannot create a device. */
    suspend fun updateRegistration(): ApiResult<DeviceOut> = apiCall {
        api.registerDevice(
            RegisterDeviceRequest(
                deviceId = localState.installId(),
                platform = Platform.ANDROID,
                osVersion = config.osVersion,
                appVersion = config.appVersion,
                capabilities = capabilities(),
            ),
        )
    }.also { if (it is ApiResult.Success) localState.reportedDeviceVersion = versionMark() }

    /** After an app or OS update the server is told the new versions, once. */
    suspend fun updateRegistrationIfChanged() {
        if (credentials.isEnrolled && localState.reportedDeviceVersion != versionMark()) updateRegistration()
    }

    suspend fun registerPushToken(token: String): ApiResult<OkResponse> = apiCall {
        api.registerPushToken(RegisterPushTokenRequest(localState.installId(), PushProvider.FCM, token))
    }.also { if (it is ApiResult.Success) localState.registeredPushToken = token }

    suspend fun devices(): ApiResult<List<DeviceOut>> = apiCall { api.devices() }

    suspend fun revoke(deviceId: String): ApiResult<OkResponse> = apiCall { api.revokeDevice(RevokeDeviceRequest(deviceId)) }
}
