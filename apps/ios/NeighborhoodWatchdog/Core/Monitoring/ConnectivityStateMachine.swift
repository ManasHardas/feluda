import Foundation

/// Everything the state machine knows about the device at one moment. A nil value means
/// "not known", never "false".
struct MonitorInputs: Codable, Sendable, Equatable {
    var monitoringEnabled: Bool = true
    /// A permission monitoring needs is missing. iOS path monitoring needs none, so the app never
    /// sets this today; the state exists in the contract and the machine honours it.
    var permissionMissing: Bool = false
    /// A Wi-Fi path exists (the phone is associated with a Wi-Fi network).
    var wifiAssociated: Bool?
    /// A cellular data path exists.
    var cellularAvailable: Bool?
    /// A usable path that is neither Wi-Fi nor cellular exists (for example wired Ethernet).
    var otherPathUsable: Bool = false
    /// Interface type of the system's current default path.
    var defaultTransport: Transport?
    /// Result of the HTTPS probe that is not allowed to use cellular, taken while Wi-Fi was
    /// associated. An available Wi-Fi path alone does not prove the internet is reachable.
    var wifiInternetReachable: Bool?
    /// Result of the HTTPS probe over whatever path the system chose.
    var internetReachable: Bool?
    /// Whether the associated Wi-Fi matched the home-network fingerprint (nil: unknown / not associated).
    var homeNetworkMatch: Bool?

    static let paused = MonitorInputs(monitoringEnabled: false)
}

/// One event the machine decided to emit. The coordinator turns it into a `ConnectivityEvent`.
struct StateChange: Sendable, Equatable {
    let eventType: EventType
    let state: ConnectivityState
    /// Time of the first callback that started this change (or the resume time when reconciled).
    let occurredAt: Date
    let confidence: ObservationConfidence
    let inputs: MonitorInputs
    /// Home-network match of the Wi-Fi network this event is about. For a loss this is the
    /// match of the network that was lost.
    let homeNetworkMatch: Bool?
}

/// Pure connectivity state machine with debounce. No timers and no system calls: the caller
/// passes the time in, and calls `tick` when `nextDeadline` is reached.
struct ConnectivityStateMachine: Codable, Sendable, Equatable {
    struct Configuration: Codable, Sendable, Equatable {
        /// Quiet time after the last callback before a change is classified.
        var debounce: TimeInterval = 5
        /// Upper bound for waiting (flapping network, or a probe that has not answered yet).
        var maxSettleWait: TimeInterval = 30
        /// Window for the `network_changed_recently` diagnostic.
        var recentChangeWindow: TimeInterval = 60
    }

    private enum Fact: String, Codable, CaseIterable { case wifi, cellular, transport, internet, home }

    private struct Pending: Codable, Sendable, Equatable {
        var firstAt: Date
        var lastAt: Date
        var inputs: MonitorInputs
        var changedAt: [String: Date]
    }

    var configuration = Configuration()
    /// The last classified (settled) inputs. Persisted so a later launch can reconcile against it.
    private(set) var settled: MonitorInputs?
    /// Whether Wi-Fi was ever seen associated since monitoring was set up on this install.
    private(set) var wifiSeen = false
    /// A WIFI_LOST was emitted and no WIFI_RESTORED followed yet.
    private(set) var awaitingWifiRestore = false
    private(set) var lastChangeSettledAt: Date?
    private var pending: Pending?

    enum CodingKeys: String, CodingKey {
        // `pending` is deliberately not persisted: an unsettled change does not survive the process.
        case configuration, settled, wifiSeen, awaitingWifiRestore, lastChangeSettledAt
    }

    init(configuration: Configuration = Configuration()) {
        self.configuration = configuration
    }

    // MARK: Classification

    /// Maps inputs to exactly one state. See CLIENT_PROTOCOL.md section 4.
    static func classify(_ inputs: MonitorInputs, wifiSeen: Bool) -> ConnectivityState {
        // Monitoring that is off is never reported as healthy, whatever the network looks like.
        guard inputs.monitoringEnabled else { return .monitoringPaused }
        guard !inputs.permissionMissing else { return .monitoringPermissionRequired }
        guard let wifi = inputs.wifiAssociated else { return .unknown }
        if wifi {
            switch inputs.wifiInternetReachable {
            case .some(true): return .healthy
            case .some(false): return .wifiConnectedNoInternet
            case .none: return .unknown
            }
        }
        guard let cellular = inputs.cellularAvailable else { return .unknown }
        if !wifiSeen {
            // A device that never had Wi-Fi in this monitoring session: cellular-only observation.
            return cellular ? .cellularAvailable : .cellularUnavailable
        }
        if cellular || inputs.otherPathUsable { return .wifiDisconnected }
        return .internetUnavailable
    }

    var settledState: ConnectivityState {
        guard let settled else { return .unknown }
        return Self.classify(settled, wifiSeen: wifiSeen)
    }

    /// The state to show and to attach to lifecycle events right now.
    var state: ConnectivityState {
        if let settled, !settled.monitoringEnabled { return .monitoringPaused }
        return pending == nil ? settledState : .networkTransition
    }

    /// The freshest inputs, settled or not (for diagnostics and the UI rows).
    var latestInputs: MonitorInputs? { pending?.inputs ?? settled }

    var hasPendingChange: Bool { pending != nil }

    /// When `tick` should be called next, or nil when nothing is pending.
    var nextDeadline: Date? {
        guard let pending else { return nil }
        let hard = pending.firstAt.addingTimeInterval(configuration.maxSettleWait)
        if Self.awaitingProbe(pending.inputs) { return hard }
        return min(pending.lastAt.addingTimeInterval(configuration.debounce), hard)
    }

    func networkChangedRecently(at now: Date) -> Bool? {
        guard settled != nil else { return nil }
        if pending != nil { return true }
        guard let last = lastChangeSettledAt else { return false }
        return now.timeIntervalSince(last) <= configuration.recentChangeWindow
    }

    private static func awaitingProbe(_ inputs: MonitorInputs) -> Bool {
        inputs.internetReachable == nil || (inputs.wifiAssociated == true && inputs.wifiInternetReachable == nil)
    }

    // MARK: Transitions

    /// Adopts `inputs` as the starting point without emitting events (monitor start).
    mutating func begin(with inputs: MonitorInputs, freshSession: Bool) {
        if freshSession {
            wifiSeen = false
            awaitingWifiRestore = false
            lastChangeSettledAt = nil
        }
        if inputs.wifiAssociated == true { wifiSeen = true }
        if inputs.wifiAssociated == true, inputs.wifiInternetReachable == true { awaitingWifiRestore = false }
        settled = inputs
        pending = nil
    }

    /// Monitoring was switched off. State becomes MONITORING_PAUSED immediately.
    mutating func pause() {
        var inputs = settled ?? MonitorInputs()
        inputs.monitoringEnabled = false
        settled = inputs
        pending = nil
    }

    /// Feeds a new observation. Changes are held for the debounce time; a change that reverts
    /// inside it (a roam, a band switch) produces no event at all.
    mutating func ingest(_ inputs: MonitorInputs, at now: Date) -> [StateChange] {
        var changes = tick(at: now)
        guard let settled else {
            begin(with: inputs, freshSession: false)
            return changes
        }
        if inputs == settled {
            pending = nil
            return changes
        }
        if !inputs.monitoringEnabled || inputs.permissionMissing || !settled.monitoringEnabled || settled.permissionMissing {
            // Lifecycle changes are not debounced and carry no connectivity events of their own.
            if inputs.wifiAssociated == true { wifiSeen = true }
            self.settled = inputs
            pending = nil
            return changes
        }
        var changedAt = pending?.changedAt ?? [:]
        for fact in Fact.allCases {
            if Self.differs(fact, settled, inputs) {
                if changedAt[fact.rawValue] == nil { changedAt[fact.rawValue] = now }
            } else {
                changedAt[fact.rawValue] = nil
            }
        }
        let lastAt = (pending?.inputs == inputs) ? (pending?.lastAt ?? now) : now
        pending = Pending(firstAt: pending?.firstAt ?? now, lastAt: lastAt, inputs: inputs, changedAt: changedAt)
        changes += tick(at: now)
        return changes
    }

    /// Settles the pending change when its deadline has passed and returns the resulting events.
    mutating func tick(at now: Date) -> [StateChange] {
        guard let pending, let settled, let deadline = nextDeadline, now >= deadline else { return [] }
        let changes = diff(from: settled, to: pending.inputs, confidence: .observed) { fact in
            pending.changedAt[fact.rawValue] ?? pending.firstAt
        }
        self.settled = pending.inputs
        self.pending = nil
        if !changes.isEmpty { lastChangeSettledAt = now }
        return changes
    }

    /// After a monitoring gap: compares the state persisted before the gap with `current` and
    /// returns the matching events with confidence RECONCILED and `occurredAt` = `resumeTime`.
    /// The real time of the change is unknown, so it is never back-dated into the gap.
    mutating func reconcile(with current: MonitorInputs, at resumeTime: Date) -> [StateChange] {
        guard let before = settled, before.monitoringEnabled else {
            begin(with: current, freshSession: false)
            return []
        }
        let changes = diff(from: before, to: current, confidence: .reconciled) { _ in resumeTime }
        settled = current
        pending = nil
        if !changes.isEmpty { lastChangeSettledAt = resumeTime }
        return changes
    }

    private static func differs(_ fact: Fact, _ a: MonitorInputs, _ b: MonitorInputs) -> Bool {
        switch fact {
        case .wifi: return a.wifiAssociated != b.wifiAssociated
        case .cellular: return a.cellularAvailable != b.cellularAvailable || a.otherPathUsable != b.otherPathUsable
        case .transport: return a.defaultTransport != b.defaultTransport
        case .internet: return a.wifiInternetReachable != b.wifiInternetReachable || a.internetReachable != b.internetReachable
        case .home: return a.homeNetworkMatch != b.homeNetworkMatch
        }
    }

    private mutating func diff(
        from before: MonitorInputs, to after: MonitorInputs, confidence: ObservationConfidence,
        time: (Fact) -> Date
    ) -> [StateChange] {
        guard before.monitoringEnabled, after.monitoringEnabled, !before.permissionMissing, !after.permissionMissing else {
            if after.wifiAssociated == true { wifiSeen = true }
            return []
        }
        var planned: [(EventType, Fact)] = []

        switch (before.wifiAssociated, after.wifiAssociated) {
        case (.some(true), .some(false)):
            planned.append((.wifiLost, .wifi))
            awaitingWifiRestore = true
        case (.some(false), .some(true)):
            if awaitingWifiRestore, after.wifiInternetReachable == true {
                planned.append((.wifiRestored, .wifi))
                awaitingWifiRestore = false
            } else {
                // Associated again but without confirmed internet, or associated without a loss
                // having been observed first (for example arriving home).
                planned.append((.wifiAssociationChanged, .wifi))
            }
        case (.some(true), .some(true)):
            if awaitingWifiRestore, after.wifiInternetReachable == true {
                planned.append((.wifiRestored, .internet))
                awaitingWifiRestore = false
            } else if let was = before.wifiInternetReachable, let now = after.wifiInternetReachable, was != now {
                planned.append((.internetReachabilityChanged, .internet))
            }
            if let was = before.homeNetworkMatch, let now = after.homeNetworkMatch, was != now {
                planned.append((.wifiAssociationChanged, .home))
            }
        default:
            break
        }

        if let was = before.defaultTransport, let now = after.defaultTransport, was != now {
            planned.append((.defaultNetworkChanged, .transport))
        }

        switch (before.cellularAvailable, after.cellularAvailable) {
        case (.some(true), .some(false)): planned.append((.cellularUnavailable, .cellular))
        case (.some(false), .some(true)): planned.append((.cellularAvailable, .cellular))
        default: break
        }

        if after.wifiAssociated == true { wifiSeen = true }
        let newState = Self.classify(after, wifiSeen: wifiSeen)
        let homeMatch = after.wifiAssociated == true ? after.homeNetworkMatch : before.homeNetworkMatch
        return planned.map { type, fact in
            StateChange(eventType: type, state: newState, occurredAt: time(fact), confidence: confidence,
                        inputs: after, homeNetworkMatch: homeMatch)
        }
    }
}
