package org.neighborhoodwatchdog.android.data.repository

import android.content.Context
import android.content.pm.PackageManager
import org.neighborhoodwatchdog.android.config.AppConfig
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.ConnectivityStatusOut
import org.neighborhoodwatchdog.android.data.api.DeviceCapabilities
import org.neighborhoodwatchdog.android.data.api.DeviceOut
import org.neighborhoodwatchdog.android.data.api.HouseholdOut
import org.neighborhoodwatchdog.android.data.api.IncidentDetailOut
import org.neighborhoodwatchdog.android.data.api.IncidentListOut
import org.neighborhoodwatchdog.android.data.api.JoinHouseholdRequest
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
import org.neighborhoodwatchdog.android.domain.model.BackgroundMonitoring
import org.neighborhoodwatchdog.android.domain.model.Isp
import org.neighborhoodwatchdog.android.domain.model.Platform
import org.neighborhoodwatchdog.android.domain.model.PushProvider

/** Household, neighborhood, incident and preference calls. Authorization is enforced by the backend. */
class WatchdogRepository(private val api: WatchdogApi, private val localState: LocalState) {
    suspend fun household(): ApiResult<HouseholdOut> = apiCall { api.household() }.also {
        if (it is ApiResult.Success) localState.memberConfirmed = true
    }

    suspend fun verifyInvitation(token: String): ApiResult<VerifyInvitationResponse> =
        apiCall { api.verifyInvitation(VerifyInvitationRequest(token)) }

    suspend fun join(
        token: String,
        houseNumber: String,
        streetName: String,
        displayLabel: String?,
        isp: Isp?,
        privacyAcknowledged: Boolean,
    ): ApiResult<HouseholdOut> = apiCall {
        api.joinHousehold(
            JoinHouseholdRequest(
                invitationToken = token,
                houseNumber = houseNumber.trim(),
                streetName = streetName.trim(),
                displayLabel = displayLabel?.trim()?.takeIf { it.isNotEmpty() },
                isp = isp,
                privacyAcknowledged = privacyAcknowledged,
            ),
        )
    }.also { if (it is ApiResult.Success) localState.memberConfirmed = true }

    suspend fun updateHousehold(displayLabel: String?, isp: Isp?): ApiResult<HouseholdOut> =
        apiCall { api.updateHousehold(UpdateHouseholdRequest(displayLabel?.trim()?.takeIf { it.isNotEmpty() }, isp)) }

    suspend fun leave(): ApiResult<OkResponse> = apiCall { api.leaveHousehold() }
    suspend fun deleteAccount(): ApiResult<OkResponse> = apiCall { api.deleteAccount() }
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
) {
    /** What this phone can really measure. Sent once at registration. */
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

    suspend fun register(): ApiResult<DeviceOut> = apiCall {
        api.registerDevice(
            RegisterDeviceRequest(
                deviceId = localState.installId(),
                platform = Platform.ANDROID,
                osVersion = config.osVersion,
                appVersion = config.appVersion,
                capabilities = capabilities(),
            ),
        )
    }.also { if (it is ApiResult.Success) localState.deviceRegistered = true }

    suspend fun registerPushToken(token: String): ApiResult<OkResponse> = apiCall {
        api.registerPushToken(RegisterPushTokenRequest(localState.installId(), PushProvider.FCM, token))
    }.also { if (it is ApiResult.Success) localState.registeredPushToken = token }

    suspend fun devices(): ApiResult<List<DeviceOut>> = apiCall { api.devices() }

    suspend fun revoke(deviceId: String): ApiResult<OkResponse> = apiCall { api.revokeDevice(RevokeDeviceRequest(deviceId)) }
}
