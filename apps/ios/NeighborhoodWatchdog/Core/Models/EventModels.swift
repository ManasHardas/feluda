import Foundation

/// Measurements available at the time of an event. A field is nil when it was not measured.
/// Nothing in here is ever filled with a guess; nil is encoded as an explicit JSON null so the
/// transparency viewer shows exactly what is (and is not) reported.
struct Diagnostics: Codable, Sendable, Equatable {
    var wifiAssociated: Bool?
    /// On iOS: result of the app's own HTTPS probe with cellular access switched off, taken while
    /// Wi-Fi was associated. iOS does not expose the system's own validation result.
    var wifiValidated: Bool?
    /// Android only. Always nil on iOS.
    var wifiRadioEnabled: Bool?
    /// Android only. Always nil on iOS.
    var airplaneMode: Bool?
    var homeNetworkMatch: Bool?
    /// Always nil on iOS: the app does not ask for the Local Network permission.
    var localGatewayReachable: Bool?
    var internetReachable: Bool?
    var cellularAvailable: Bool?
    var defaultTransport: Transport?
    var networkChangedRecently: Bool?
    /// Not exposed reliably by NWPath; nil on iOS.
    var vpnActive: Bool?
    /// Android only. Always nil on iOS.
    var lastWifiRssiDbm: Int?
    /// Always BAND_UNKNOWN on iOS.
    var bandEvidence: BandEvidence = .bandUnknown
    var externalPowerLostRecently: Bool?
    var batterySaver: Bool?
    var userInitiated: Bool?
    var gapStartedAt: Date?
    var gapEndedAt: Date?
    var gapReason: GapReason?

    enum CodingKeys: String, CodingKey, CaseIterable {
        case wifiAssociated = "wifi_associated"
        case wifiValidated = "wifi_validated"
        case wifiRadioEnabled = "wifi_radio_enabled"
        case airplaneMode = "airplane_mode"
        case homeNetworkMatch = "home_network_match"
        case localGatewayReachable = "local_gateway_reachable"
        case internetReachable = "internet_reachable"
        case cellularAvailable = "cellular_available"
        case defaultTransport = "default_transport"
        case networkChangedRecently = "network_changed_recently"
        case vpnActive = "vpn_active"
        case lastWifiRssiDbm = "last_wifi_rssi_dbm"
        case bandEvidence = "band_evidence"
        case externalPowerLostRecently = "external_power_lost_recently"
        case batterySaver = "battery_saver"
        case userInitiated = "user_initiated"
        case gapStartedAt = "gap_started_at"
        case gapEndedAt = "gap_ended_at"
        case gapReason = "gap_reason"
    }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        wifiAssociated = try c.decodeIfPresent(Bool.self, forKey: .wifiAssociated)
        wifiValidated = try c.decodeIfPresent(Bool.self, forKey: .wifiValidated)
        wifiRadioEnabled = try c.decodeIfPresent(Bool.self, forKey: .wifiRadioEnabled)
        airplaneMode = try c.decodeIfPresent(Bool.self, forKey: .airplaneMode)
        homeNetworkMatch = try c.decodeIfPresent(Bool.self, forKey: .homeNetworkMatch)
        localGatewayReachable = try c.decodeIfPresent(Bool.self, forKey: .localGatewayReachable)
        internetReachable = try c.decodeIfPresent(Bool.self, forKey: .internetReachable)
        cellularAvailable = try c.decodeIfPresent(Bool.self, forKey: .cellularAvailable)
        defaultTransport = try c.decodeIfPresent(Transport.self, forKey: .defaultTransport)
        networkChangedRecently = try c.decodeIfPresent(Bool.self, forKey: .networkChangedRecently)
        vpnActive = try c.decodeIfPresent(Bool.self, forKey: .vpnActive)
        lastWifiRssiDbm = try c.decodeIfPresent(Int.self, forKey: .lastWifiRssiDbm)
        bandEvidence = try c.decodeIfPresent(BandEvidence.self, forKey: .bandEvidence) ?? .bandUnknown
        externalPowerLostRecently = try c.decodeIfPresent(Bool.self, forKey: .externalPowerLostRecently)
        batterySaver = try c.decodeIfPresent(Bool.self, forKey: .batterySaver)
        userInitiated = try c.decodeIfPresent(Bool.self, forKey: .userInitiated)
        gapStartedAt = try c.decodeIfPresent(Date.self, forKey: .gapStartedAt)
        gapEndedAt = try c.decodeIfPresent(Date.self, forKey: .gapEndedAt)
        gapReason = try c.decodeIfPresent(GapReason.self, forKey: .gapReason)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        // `encode` (not `encodeIfPresent`) writes an explicit null for an unmeasured value.
        try c.encode(wifiAssociated, forKey: .wifiAssociated)
        try c.encode(wifiValidated, forKey: .wifiValidated)
        try c.encode(wifiRadioEnabled, forKey: .wifiRadioEnabled)
        try c.encode(airplaneMode, forKey: .airplaneMode)
        try c.encode(homeNetworkMatch, forKey: .homeNetworkMatch)
        try c.encode(localGatewayReachable, forKey: .localGatewayReachable)
        try c.encode(internetReachable, forKey: .internetReachable)
        try c.encode(cellularAvailable, forKey: .cellularAvailable)
        try c.encode(defaultTransport, forKey: .defaultTransport)
        try c.encode(networkChangedRecently, forKey: .networkChangedRecently)
        try c.encode(vpnActive, forKey: .vpnActive)
        try c.encode(lastWifiRssiDbm, forKey: .lastWifiRssiDbm)
        try c.encode(bandEvidence, forKey: .bandEvidence)
        try c.encode(externalPowerLostRecently, forKey: .externalPowerLostRecently)
        try c.encode(batterySaver, forKey: .batterySaver)
        try c.encode(userInitiated, forKey: .userInitiated)
        try c.encode(gapStartedAt, forKey: .gapStartedAt)
        try c.encode(gapEndedAt, forKey: .gapEndedAt)
        try c.encode(gapReason, forKey: .gapReason)
    }
}

/// One connectivity event (ConnectivityEventIn). Immutable after creation: `eventID` is the
/// idempotency key and `occurredAt` is the time it happened on the device; neither changes across
/// upload retries.
struct ConnectivityEvent: Codable, Sendable, Equatable, Identifiable {
    let eventID: UUID
    let sequence: Int
    let eventType: EventType
    let connectivityState: ConnectivityState
    let occurredAt: Date
    let osVersion: String
    let appVersion: String
    let confidence: ObservationConfidence
    let diagnostics: Diagnostics

    var id: UUID { eventID }

    enum CodingKeys: String, CodingKey, CaseIterable {
        case eventID = "event_id"
        case sequence
        case eventType = "event_type"
        case connectivityState = "connectivity_state"
        case occurredAt = "occurred_at"
        case osVersion = "os_version"
        case appVersion = "app_version"
        case confidence
        case diagnostics
    }
}

struct SubmitEventsRequest: Codable, Sendable, Equatable {
    let deviceID: UUID
    /// Network the device used for this upload, as far as it knows.
    let reportedVia: Transport
    /// Time of this upload attempt (not of the events).
    let sentAt: Date
    let events: [ConnectivityEvent]

    enum CodingKeys: String, CodingKey, CaseIterable {
        case deviceID = "device_id"
        case reportedVia = "reported_via"
        case sentAt = "sent_at"
        case events
    }
}

struct EventOutcome: Codable, Sendable, Equatable {
    let eventID: UUID
    let result: EventResult
    let reason: String?

    enum CodingKeys: String, CodingKey {
        case eventID = "event_id"
        case result
        case reason
    }
}

struct SubmitEventsResponse: Codable, Sendable, Equatable {
    let serverTime: Date
    let outcomes: [EventOutcome]
    let retryAfterSeconds: Int?

    enum CodingKeys: String, CodingKey {
        case serverTime = "server_time"
        case outcomes
        case retryAfterSeconds = "retry_after_seconds"
    }
}

extension EventType {
    /// Order in which event classes are sacrificed when the local queue is over its cap.
    /// Heartbeats go first, then lifecycle events; connectivity events are kept the longest.
    enum RetentionClass: Int, Comparable, Sendable {
        case heartbeat = 0
        case lifecycle = 1
        case connectivity = 2
        static func < (lhs: RetentionClass, rhs: RetentionClass) -> Bool { lhs.rawValue < rhs.rawValue }
    }

    var retentionClass: RetentionClass {
        switch self {
        case .heartbeat:
            return .heartbeat
        case .monitoringStarted, .monitoringResumed, .monitoringStopped, .monitoringDisabledByUser,
             .permissionChanged, .signalStrengthChanged:
            return .lifecycle
        case .wifiLost, .wifiRestored, .defaultNetworkChanged, .cellularAvailable, .cellularUnavailable,
             .internetReachabilityChanged, .wifiAssociationChanged, .monitoringGapDetected:
            return .connectivity
        }
    }
}
