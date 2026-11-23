import Foundation

// Enumerations of the API contract. Raw values match backend/watchdog/enums.py exactly.

enum ConnectivityState: String, Codable, Sendable, CaseIterable {
    case healthy = "HEALTHY"
    case wifiDisconnected = "WIFI_DISCONNECTED"
    case wifiConnectedNoInternet = "WIFI_CONNECTED_NO_INTERNET"
    case internetUnavailable = "INTERNET_UNAVAILABLE"
    case cellularAvailable = "CELLULAR_AVAILABLE"
    case cellularUnavailable = "CELLULAR_UNAVAILABLE"
    case networkTransition = "NETWORK_TRANSITION"
    case monitoringPaused = "MONITORING_PAUSED"
    case monitoringPermissionRequired = "MONITORING_PERMISSION_REQUIRED"
    case unknown = "UNKNOWN"
}

enum EventType: String, Codable, Sendable, CaseIterable {
    case wifiLost = "WIFI_LOST"
    case wifiRestored = "WIFI_RESTORED"
    case defaultNetworkChanged = "DEFAULT_NETWORK_CHANGED"
    case cellularAvailable = "CELLULAR_AVAILABLE"
    case cellularUnavailable = "CELLULAR_UNAVAILABLE"
    case internetReachabilityChanged = "INTERNET_REACHABILITY_CHANGED"
    case signalStrengthChanged = "SIGNAL_STRENGTH_CHANGED"
    case wifiAssociationChanged = "WIFI_ASSOCIATION_CHANGED"
    case monitoringStarted = "MONITORING_STARTED"
    case monitoringResumed = "MONITORING_RESUMED"
    case monitoringStopped = "MONITORING_STOPPED"
    case monitoringDisabledByUser = "MONITORING_DISABLED_BY_USER"
    case monitoringGapDetected = "MONITORING_GAP_DETECTED"
    case permissionChanged = "PERMISSION_CHANGED"
    case heartbeat = "HEARTBEAT"
}

enum Platform: String, Codable, Sendable {
    case android
    case ios
}

enum Transport: String, Codable, Sendable {
    case wifi
    case cellular
    case ethernet
    case vpn
    case other
    case none
    case unknown
}

enum BandEvidence: String, Codable, Sendable {
    case band24Observed = "BAND_2_4_OBSERVED"
    case band5Observed = "BAND_5_OBSERVED"
    case band6Observed = "BAND_6_OBSERVED"
    case bandUnknown = "BAND_UNKNOWN"
    case multiBandFailureNotVerified = "MULTI_BAND_FAILURE_NOT_VERIFIED"
}

enum ObservationConfidence: String, Codable, Sendable {
    /// Seen live through an operating-system callback.
    case observed = "OBSERVED"
    /// Derived after a monitoring gap; the true time of the change is unknown.
    case reconciled = "RECONCILED"
}

enum GapReason: String, Codable, Sendable {
    case appSuspended = "app_suspended"
    case processTerminated = "process_terminated"
    case deviceReboot = "device_reboot"
    case permissionRevoked = "permission_revoked"
    case backgroundRestricted = "background_restricted"
    case unknown
}

enum BackgroundMonitoring: String, Codable, Sendable {
    case callback
    case opportunistic
    case foregroundOnly = "foreground_only"
}

enum Isp: String, Codable, Sendable, CaseIterable {
    case att
    case xfinity
    case spectrum
    case verizon
    case tmobileHome = "tmobile_home"
    case googleFiber = "google_fiber"
    case other
    case unknown
}

enum HouseholdStatus: String, Codable, Sendable {
    case approved, active, suspended, removed
}

enum DeviceStatus: String, Codable, Sendable {
    case active, revoked
}

enum Classification: String, Codable, Sendable {
    case normal = "NORMAL"
    case householdConnectivityIssue = "HOUSEHOLD_CONNECTIVITY_ISSUE"
    case possibleIspOutage = "POSSIBLE_ISP_OUTAGE"
    case possibleRouterOrPowerFailure = "POSSIBLE_ROUTER_OR_POWER_FAILURE"
    case possibleWirelessInterference = "POSSIBLE_WIRELESS_INTERFERENCE"
    case correlatedNeighborhoodDisruption = "CORRELATED_NEIGHBORHOOD_DISRUPTION"
    case highPrioritySuspiciousDisruption = "HIGH_PRIORITY_SUSPICIOUS_DISRUPTION"
}

/// Encoded as an integer 0...3.
enum AlertLevel: Int, Codable, Sendable, Comparable {
    case normal = 0
    case householdWarning = 1
    case neighborhoodAnomaly = 2
    case highPriority = 3

    static func < (lhs: AlertLevel, rhs: AlertLevel) -> Bool { lhs.rawValue < rhs.rawValue }
}

enum StatusColor: String, Codable, Sendable {
    case green = "GREEN"
    case yellow = "YELLOW"
    case orange = "ORANGE"
    case red = "RED"
    /// Monitoring inactive or unknown. Never presented as healthy.
    case grey = "GREY"
}

enum IncidentScope: String, Codable, Sendable {
    case household, neighborhood
}

enum IncidentStatus: String, Codable, Sendable {
    case open
    case resolved
    /// Closed by timeout; no recovery reports arrived, the outcome is unknown.
    case expired
}

enum FailureMode: String, Codable, Sendable {
    case wifiLost = "wifi_lost"
    case noInternet = "no_internet"
    case allConnectivityLost = "all_connectivity_lost"
}

enum MonitoringStatus: String, Codable, Sendable {
    case confirmedActive = "CONFIRMED_ACTIVE"
    case knownGap = "KNOWN_GAP"
    case paused = "PAUSED"
    case noRecentTelemetry = "NO_RECENT_TELEMETRY"
    case failureReported = "FAILURE_REPORTED"
    case neverReported = "NEVER_REPORTED"
}

enum PushProvider: String, Codable, Sendable {
    case fcm, apns
}

enum ApnsEnvironment: String, Codable, Sendable {
    case sandbox, production
}

enum EventResult: String, Codable, Sendable {
    case accepted, duplicate, rejected
}
