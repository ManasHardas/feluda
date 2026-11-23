package org.neighborhoodwatchdog.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Values mirror backend/watchdog/enums.py exactly. They are part of the public API contract.

@Serializable
enum class ConnectivityState {
    HEALTHY,
    WIFI_DISCONNECTED,
    WIFI_CONNECTED_NO_INTERNET,
    INTERNET_UNAVAILABLE,
    CELLULAR_AVAILABLE,
    CELLULAR_UNAVAILABLE,
    NETWORK_TRANSITION,
    MONITORING_PAUSED,
    MONITORING_PERMISSION_REQUIRED,
    UNKNOWN,
}

@Serializable
enum class EventType {
    WIFI_LOST,
    WIFI_RESTORED,
    DEFAULT_NETWORK_CHANGED,
    CELLULAR_AVAILABLE,
    CELLULAR_UNAVAILABLE,
    INTERNET_REACHABILITY_CHANGED,
    SIGNAL_STRENGTH_CHANGED,
    WIFI_ASSOCIATION_CHANGED,
    MONITORING_STARTED,
    MONITORING_RESUMED,
    MONITORING_STOPPED,
    MONITORING_DISABLED_BY_USER,
    MONITORING_GAP_DETECTED,
    PERMISSION_CHANGED,
    HEARTBEAT,
}

@Serializable
enum class Platform {
    @SerialName("android") ANDROID,
    @SerialName("ios") IOS,
}

@Serializable
enum class Transport {
    @SerialName("wifi") WIFI,
    @SerialName("cellular") CELLULAR,
    @SerialName("ethernet") ETHERNET,
    @SerialName("vpn") VPN,
    @SerialName("other") OTHER,
    @SerialName("none") NONE,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class BandEvidence {
    BAND_2_4_OBSERVED,
    BAND_5_OBSERVED,
    BAND_6_OBSERVED,
    BAND_UNKNOWN,
    MULTI_BAND_FAILURE_NOT_VERIFIED,
}

@Serializable
enum class ObservationConfidence { OBSERVED, RECONCILED }

@Serializable
enum class GapReason {
    @SerialName("app_suspended") APP_SUSPENDED,
    @SerialName("process_terminated") PROCESS_TERMINATED,
    @SerialName("device_reboot") DEVICE_REBOOT,
    @SerialName("permission_revoked") PERMISSION_REVOKED,
    @SerialName("background_restricted") BACKGROUND_RESTRICTED,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class BackgroundMonitoring {
    @SerialName("callback") CALLBACK,
    @SerialName("opportunistic") OPPORTUNISTIC,
    @SerialName("foreground_only") FOREGROUND_ONLY,
}

@Serializable
enum class Isp {
    @SerialName("att") ATT,
    @SerialName("xfinity") XFINITY,
    @SerialName("spectrum") SPECTRUM,
    @SerialName("verizon") VERIZON,
    @SerialName("tmobile_home") TMOBILE_HOME,
    @SerialName("google_fiber") GOOGLE_FIBER,
    @SerialName("other") OTHER,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class HouseholdStatus {
    @SerialName("approved") APPROVED,
    @SerialName("active") ACTIVE,
    @SerialName("suspended") SUSPENDED,
    @SerialName("removed") REMOVED,
}

@Serializable
enum class DeviceStatus {
    @SerialName("active") ACTIVE,
    @SerialName("revoked") REVOKED,
}

@Serializable
enum class Classification {
    NORMAL,
    HOUSEHOLD_CONNECTIVITY_ISSUE,
    POSSIBLE_ISP_OUTAGE,
    POSSIBLE_ROUTER_OR_POWER_FAILURE,
    POSSIBLE_WIRELESS_INTERFERENCE,
    CORRELATED_NEIGHBORHOOD_DISRUPTION,
    HIGH_PRIORITY_SUSPICIOUS_DISRUPTION,
}

@Serializable
enum class StatusColor { GREEN, YELLOW, ORANGE, RED, GREY }

@Serializable
enum class IncidentScope {
    @SerialName("household") HOUSEHOLD,
    @SerialName("neighborhood") NEIGHBORHOOD,
}

@Serializable
enum class IncidentStatus {
    @SerialName("open") OPEN,
    @SerialName("resolved") RESOLVED,
    @SerialName("expired") EXPIRED,
}

@Serializable
enum class MonitoringStatus {
    CONFIRMED_ACTIVE,
    KNOWN_GAP,
    PAUSED,
    NO_RECENT_TELEMETRY,
    FAILURE_REPORTED,
    NEVER_REPORTED,
}

@Serializable
enum class PushProvider {
    @SerialName("fcm") FCM,
    @SerialName("apns") APNS,
}

@Serializable
enum class EventResult {
    @SerialName("accepted") ACCEPTED,
    @SerialName("duplicate") DUPLICATE,
    @SerialName("rejected") REJECTED,
}
