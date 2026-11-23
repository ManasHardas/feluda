package org.neighborhoodwatchdog.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.neighborhoodwatchdog.android.domain.model.BackgroundMonitoring
import org.neighborhoodwatchdog.android.domain.model.Classification
import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.DeviceStatus
import org.neighborhoodwatchdog.android.domain.model.HouseholdStatus
import org.neighborhoodwatchdog.android.domain.model.IncidentScope
import org.neighborhoodwatchdog.android.domain.model.IncidentStatus
import org.neighborhoodwatchdog.android.domain.model.Isp
import org.neighborhoodwatchdog.android.domain.model.MonitoringStatus
import org.neighborhoodwatchdog.android.domain.model.Platform
import org.neighborhoodwatchdog.android.domain.model.PushProvider
import org.neighborhoodwatchdog.android.domain.model.StatusColor
import org.neighborhoodwatchdog.android.domain.model.Timestamp

// Field names mirror backend/watchdog/schemas.py. Enum-typed response fields have a default so an
// enum value added by a newer server does not break an older app (see WatchdogJson.coerceInputValues).

@Serializable
data class ErrorBody(val code: String = "", val message: String = "", @SerialName("request_id") val requestId: String? = null)

@Serializable
data class ErrorResponse(val error: ErrorBody)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class VerifyInvitationRequest(@SerialName("invitation_token") val invitationToken: String)

@Serializable
data class VerifyInvitationResponse(
    val valid: Boolean = true,
    @SerialName("neighborhood_name") val neighborhoodName: String,
    @SerialName("expires_at") val expiresAt: Timestamp,
)

/** Everything a phone sends to join. `house_number` is left out when the resident gave none. */
@Serializable
data class EnrollRequest(
    @SerialName("invitation_token") val invitationToken: String,
    @SerialName("street_name") val streetName: String,
    @SerialName("house_number") val houseNumber: String? = null,
    @SerialName("privacy_acknowledged") val privacyAcknowledged: Boolean,
    @SerialName("device_id") val deviceId: String,
    val platform: Platform,
    @SerialName("os_version") val osVersion: String,
    @SerialName("app_version") val appVersion: String,
    val capabilities: DeviceCapabilities,
)

@Serializable
data class HouseholdOut(
    @SerialName("household_id") val householdId: String,
    @SerialName("neighborhood_id") val neighborhoodId: String,
    @SerialName("anonymous_reference") val anonymousReference: String,
    @SerialName("display_label") val displayLabel: String? = null,
    @SerialName("house_number") val houseNumber: String? = null,
    @SerialName("street_name") val streetName: String,
    val isp: Isp = Isp.UNKNOWN,
    @SerialName("membership_status") val membershipStatus: HouseholdStatus = HouseholdStatus.ACTIVE,
    /** Enrolled phones in this household. */
    @SerialName("device_count") val deviceCount: Int,
)

@Serializable
data class UpdateHouseholdRequest(
    @SerialName("display_label") val displayLabel: String? = null,
    val isp: Isp? = null,
)

@Serializable
data class NeighborhoodOut(
    @SerialName("neighborhood_id") val neighborhoodId: String,
    val name: String,
    @SerialName("participating_households") val participatingHouseholds: Int,
    @SerialName("confirmed_active_households") val confirmedActiveHouseholds: Int,
    @SerialName("open_incident_count") val openIncidentCount: Int,
    @SerialName("highest_alert_level") val highestAlertLevel: Int,
    @SerialName("status_color") val statusColor: StatusColor = StatusColor.GREY,
    @SerialName("status_explanation") val statusExplanation: String,
    @SerialName("correlation_radius_m") val correlationRadiusM: Int,
    @SerialName("correlation_window_s") val correlationWindowS: Int,
)

@Serializable
data class DeviceCapabilities(
    @SerialName("can_measure_rssi") val canMeasureRssi: Boolean = false,
    @SerialName("can_detect_band") val canDetectBand: Boolean = false,
    @SerialName("can_probe_gateway") val canProbeGateway: Boolean = false,
    @SerialName("can_route_over_cellular") val canRouteOverCellular: Boolean = false,
    @SerialName("can_detect_power_source") val canDetectPowerSource: Boolean = false,
    @SerialName("can_detect_wifi_radio_state") val canDetectWifiRadioState: Boolean = false,
    @SerialName("can_identify_home_network") val canIdentifyHomeNetwork: Boolean = false,
    @SerialName("background_monitoring") val backgroundMonitoring: BackgroundMonitoring = BackgroundMonitoring.FOREGROUND_ONLY,
)

@Serializable
data class RegisterDeviceRequest(
    @SerialName("device_id") val deviceId: String,
    val platform: Platform,
    @SerialName("os_version") val osVersion: String,
    @SerialName("app_version") val appVersion: String,
    val capabilities: DeviceCapabilities,
)

@Serializable
data class DeviceOut(
    @SerialName("device_id") val deviceId: String,
    val platform: Platform = Platform.ANDROID,
    @SerialName("os_version") val osVersion: String,
    @SerialName("app_version") val appVersion: String,
    val status: DeviceStatus = DeviceStatus.ACTIVE,
    @SerialName("monitoring_enabled") val monitoringEnabled: Boolean,
    @SerialName("last_seen_at") val lastSeenAt: Timestamp? = null,
    @SerialName("created_at") val createdAt: Timestamp,
)

/** [deviceToken] is the phone's secret, shown once. It is stored only in the Keystore-encrypted file. */
@Serializable
data class EnrollResponse(
    @SerialName("device_token") val deviceToken: String,
    val household: HouseholdOut,
    val device: DeviceOut,
)

@Serializable
data class RevokeDeviceRequest(@SerialName("device_id") val deviceId: String)

@Serializable
data class DeviceMonitoringOut(
    @SerialName("device_id") val deviceId: String,
    val platform: Platform = Platform.ANDROID,
    @SerialName("monitoring_status") val monitoringStatus: MonitoringStatus = MonitoringStatus.NO_RECENT_TELEMETRY,
    @SerialName("connectivity_state") val connectivityState: ConnectivityState = ConnectivityState.UNKNOWN,
    @SerialName("last_event_at") val lastEventAt: Timestamp? = null,
    @SerialName("last_received_at") val lastReceivedAt: Timestamp? = null,
)

@Serializable
data class ConnectivityStatusOut(
    @SerialName("household_monitoring_status") val householdMonitoringStatus: MonitoringStatus = MonitoringStatus.NO_RECENT_TELEMETRY,
    @SerialName("status_color") val statusColor: StatusColor = StatusColor.GREY,
    @SerialName("status_explanation") val statusExplanation: String,
    @SerialName("highest_alert_level") val highestAlertLevel: Int,
    @SerialName("open_incident_ids") val openIncidentIds: List<String> = emptyList(),
    val devices: List<DeviceMonitoringOut> = emptyList(),
    @SerialName("server_time") val serverTime: Timestamp,
)

@Serializable
data class ScoreComponent(
    val indicator: String,
    val points: Double,
    @SerialName("max_points") val maxPoints: Double,
    val detail: String,
)

@Serializable
data class IncidentOut(
    @SerialName("incident_id") val incidentId: String,
    val scope: IncidentScope = IncidentScope.HOUSEHOLD,
    val classification: Classification = Classification.NORMAL,
    @SerialName("alert_level") val alertLevel: Int,
    val status: IncidentStatus = IncidentStatus.OPEN,
    @SerialName("status_color") val statusColor: StatusColor = StatusColor.GREY,
    @SerialName("started_at") val startedAt: Timestamp,
    @SerialName("last_event_at") val lastEventAt: Timestamp,
    @SerialName("resolved_at") val resolvedAt: Timestamp? = null,
    @SerialName("affected_household_count") val affectedHouseholdCount: Int,
    @SerialName("active_household_count") val activeHouseholdCount: Int,
    @SerialName("approximate_scope") val approximateScope: String,
    @SerialName("correlation_window_s") val correlationWindowS: Int,
    @SerialName("time_spread_s") val timeSpreadS: Double,
    @SerialName("isp_diversity") val ispDiversity: Map<String, Int> = emptyMap(),
    @SerialName("my_household_affected") val myHouseholdAffected: Boolean,
    val summary: String,
)

@Serializable
data class IncidentDetailOut(
    @SerialName("incident_id") val incidentId: String,
    val scope: IncidentScope = IncidentScope.HOUSEHOLD,
    val classification: Classification = Classification.NORMAL,
    @SerialName("alert_level") val alertLevel: Int,
    val status: IncidentStatus = IncidentStatus.OPEN,
    @SerialName("status_color") val statusColor: StatusColor = StatusColor.GREY,
    @SerialName("started_at") val startedAt: Timestamp,
    @SerialName("last_event_at") val lastEventAt: Timestamp,
    @SerialName("resolved_at") val resolvedAt: Timestamp? = null,
    @SerialName("affected_household_count") val affectedHouseholdCount: Int,
    @SerialName("active_household_count") val activeHouseholdCount: Int,
    @SerialName("approximate_scope") val approximateScope: String,
    @SerialName("correlation_window_s") val correlationWindowS: Int,
    @SerialName("time_spread_s") val timeSpreadS: Double,
    @SerialName("isp_diversity") val ispDiversity: Map<String, Int> = emptyMap(),
    @SerialName("my_household_affected") val myHouseholdAffected: Boolean,
    val summary: String,
    val score: Double? = null,
    @SerialName("score_breakdown") val scoreBreakdown: List<ScoreComponent> = emptyList(),
    val explanation: String,
    @SerialName("supporting_evidence") val supportingEvidence: List<String> = emptyList(),
    @SerialName("missing_evidence") val missingEvidence: List<String> = emptyList(),
    @SerialName("recommended_actions") val recommendedActions: List<String> = emptyList(),
    @SerialName("delayed_report_count") val delayedReportCount: Int = 0,
    @SerialName("failure_modes") val failureModes: Map<String, Int> = emptyMap(),
)

@Serializable
data class IncidentListOut(val incidents: List<IncidentOut> = emptyList())

@Serializable
data class RegisterPushTokenRequest(
    @SerialName("device_id") val deviceId: String,
    val provider: PushProvider,
    val token: String,
)

/** Quiet-hour times are "HH:MM:SS" strings. */
@Serializable
data class NotificationPreferences(
    @SerialName("household_alerts") val householdAlerts: Boolean = true,
    @SerialName("neighborhood_alerts") val neighborhoodAlerts: Boolean = true,
    @SerialName("quiet_hours_enabled") val quietHoursEnabled: Boolean = false,
    @SerialName("quiet_hours_start") val quietHoursStart: String = "22:00:00",
    @SerialName("quiet_hours_end") val quietHoursEnd: String = "07:00:00",
    val timezone: String = "UTC",
)

@Serializable
data class HealthOut(
    val status: String,
    val api: String = "",
    val database: String = "",
    val version: String = "",
    @SerialName("server_time") val serverTime: Timestamp,
)
