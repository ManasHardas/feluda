import Foundation

/// What the Network framework reports, reduced to plain values (so this file needs no system
/// frameworks and can be driven by tests).
struct PathSnapshot: Sendable, Equatable {
    /// A path that requires the Wi-Fi interface is satisfied. nil until the monitor reported once.
    var wifiAvailable: Bool?
    /// A path that requires the cellular interface is satisfied. nil until the monitor reported once.
    var cellularAvailable: Bool?
    /// Interface type of the default path; `.none` when no path is satisfied; nil until known.
    var defaultTransport: Transport?
    /// The default path is satisfied over something that is neither Wi-Fi nor cellular.
    var otherPathUsable = false
    /// Gateway addresses of the Wi-Fi path (may be empty: iOS does not always provide them).
    var wifiGateways: [String] = []

    var isComplete: Bool { wifiAvailable != nil && cellularAvailable != nil && defaultTransport != nil }
}

protocol PathObserving: Sendable {
    func start(onChange: @escaping @Sendable (PathSnapshot) -> Void)
    func stop()
    func current() -> PathSnapshot
}

/// Result of the active HTTPS reachability probes.
struct ProbeResult: Sendable, Equatable {
    /// An HTTPS request got an answer over whatever path the system chose.
    var anyPath: Bool
    /// An HTTPS request that was not allowed to use cellular got an answer. nil when not tested.
    var withoutCellular: Bool?
}

protocol ReachabilityProbing: Sendable {
    func probe(testWithoutCellular: Bool) async -> ProbeResult
}

struct PersistedMonitorState: Codable, Sendable, Equatable {
    var machine = ConnectivityStateMachine()
    /// The resident's monitoring switch.
    var userEnabled = true
    var hasStartedBefore = false
    /// Written every few seconds while the monitor runs. The distance from this to "now" on the
    /// next resume or launch is the monitoring gap.
    var lastAliveAt: Date?
    var lastObservationAt: Date?
    var lastHeartbeatAt: Date?
    /// Most recent gaps, newest first.
    var gaps: [MonitoringGap] = []
}

/// The monitoring component. Owns the state machine, turns its output into events, writes them
/// to the durable queue and asks the reporter to upload. It has no user interface and no UIKit.
actor MonitoringCoordinator {
    struct Dependencies: Sendable {
        var path: PathObserving
        var probe: ReachabilityProbing
        var queue: EventQueue
        var reporter: EventReporter
        var stateStore: JSONFileStore<PersistedMonitorState>
        var fingerprintStore: FingerprintStore
        var osVersion: String
        var appVersion: String
        var now: @Sendable () -> Date = { Date() }
        /// Wall-clock seconds since the device booted, nil when unknown.
        var systemUptime: @Sendable () -> TimeInterval? = { nil }
        /// Low Power Mode, nil when unknown.
        var lowPowerMode: @Sendable () -> Bool? = { nil }
        /// False in tests: no timers, the test drives `tick` and `housekeeping` itself.
        var runsTimers = true
    }

    enum StartReason: Sendable { case firstStart, userEnabled, processLaunch }

    enum HomeNetworkResult: Sendable, Equatable { case stored, notOnWifi, gatewaysUnavailable, storageFailed }

    /// Everything the UI needs, as plain values.
    struct Snapshot: Sendable, Equatable {
        var isRunning = false
        var userEnabled = true
        var state: ConnectivityState = .unknown
        var wifiAssociated: Bool?
        var wifiInternetReachable: Bool?
        var internetReachable: Bool?
        var cellularAvailable: Bool?
        var defaultTransport: Transport?
        var homeNetworkMatch: Bool?
        var hasHomeFingerprint = false
        var wifiGatewaysAvailable = false
        /// Whether the phone is on Wi-Fi right now according to the path monitor (nil: not known).
        var onWifiNow: Bool?
        var lastObservationAt: Date?
        var gaps: [MonitoringGap] = []
        var report = EventReporter.Status()
        var queueError: String?
    }

    static let aliveInterval: TimeInterval = 10
    static let probeInterval: TimeInterval = 60
    /// Probes never start closer together than this, however fast path callbacks arrive.
    static let minimumProbeSpacing: TimeInterval = 5
    /// While probes keep failing, the periodic probe backs off up to this interval.
    static let maximumProbeInterval: TimeInterval = 15 * 60
    static let heartbeatInterval: TimeInterval = 5 * 60
    static let maxStoredGaps = 20

    private let deps: Dependencies
    private var persisted: PersistedMonitorState
    private var fingerprint: HomeNetworkFingerprint?
    private var power = PowerTracker()

    private var isRunning = false
    private var isStarting = false
    private var reconciling = false
    private var latestPath = PathSnapshot()
    private var lastProbe: ProbeResult?
    private var lastProbeAt: Date?
    private var probeGeneration = 0
    private var confirmationScheduled = false
    private var lastProbeStartedAt: Date?
    private var deferredProbeTask: Task<Void, Never>?
    private var consecutiveProbeFailures = 0
    private var probeTask: Task<Void, Never>?
    private var debounceTask: Task<Void, Never>?
    private var housekeepingTask: Task<Void, Never>?
    private var onSnapshot: (@Sendable (Snapshot) -> Void)?

    init(dependencies: Dependencies) {
        self.deps = dependencies
        self.persisted = (try? dependencies.stateStore.load()) ?? PersistedMonitorState()
        self.fingerprint = dependencies.fingerprintStore.load()
    }

    func setOnSnapshot(_ handler: (@Sendable (Snapshot) -> Void)?) {
        onSnapshot = handler
    }

    var userEnabled: Bool { persisted.userEnabled }

    // MARK: Lifecycle

    /// Starts monitoring. The caller is responsible for the rule that monitoring never starts
    /// before the resident gave consent and finished onboarding.
    func start(reason: StartReason) async {
        guard !isRunning, !isStarting else { return }
        if reason == .userEnabled || reason == .firstStart { persisted.userEnabled = true }
        guard persisted.userEnabled else {
            persisted.machine.pause()
            save()
            await publish()
            return
        }
        isStarting = true
        let startTime = deps.now()
        let previousAlive = persisted.lastAliveAt
        persisted.lastAliveAt = startTime
        save()

        deps.path.start { [weak self] snapshot in
            Task { await self?.pathChanged(snapshot) }
        }
        reconciling = true
        let inputs = await observe()

        switch reason {
        case .firstStart, .userEnabled:
            persisted.machine.begin(with: inputs, freshSession: !persisted.hasStartedBefore)
            await enqueue(.monitoringStarted, at: startTime, inputs: inputs, userInitiated: true)
        case .processLaunch:
            var gap = GapDetector.detect(lastAliveAt: previousAlive, now: startTime, coldLaunch: true,
                                         systemUptime: deps.systemUptime())
            let changes = persisted.machine.reconcile(with: inputs, at: startTime)
            if gap == nil, !changes.isEmpty, let previousAlive, previousAlive < startTime {
                // Shorter than the reporting threshold, but the state changed while the process
                // was not running: the change was not observed, so the gap is reported with it.
                gap = MonitoringGap(startedAt: previousAlive, endedAt: startTime, reason: .processTerminated)
            }
            if let gap {
                await report(gap: gap, inputs: inputs)
            }
            for change in changes { await enqueue(change) }
            await enqueue(.monitoringResumed, at: startTime, inputs: inputs)
        }
        persisted.hasStartedBefore = true
        persisted.lastHeartbeatAt = startTime
        reconciling = false
        isStarting = false
        isRunning = true
        save()
        await catchUpWithLatestPath()
        startTimers()
        await publish()
        await requestFlush()
    }

    /// The resident switched monitoring off. State becomes MONITORING_PAUSED.
    func disableByUser() async {
        guard persisted.userEnabled else { return }
        let inputs = persisted.machine.latestInputs
        persisted.userEnabled = false
        persisted.machine.pause()
        await enqueue(.monitoringDisabledByUser, at: deps.now(), inputs: inputs, userInitiated: true)
        halt()
        await publish()
        await requestFlush()
    }

    /// Stops the monitor for a reason other than the monitoring switch (sign-out, leaving).
    func stop(userInitiated: Bool) async {
        guard isRunning else { return }
        await enqueue(.monitoringStopped, at: deps.now(), inputs: persisted.machine.latestInputs,
                      userInitiated: userInitiated ? true : nil)
        halt()
        await publish()
    }

    private func halt() {
        deps.path.stop()
        probeGeneration += 1
        debounceTask?.cancel()
        housekeepingTask?.cancel()
        deferredProbeTask?.cancel()
        deferredProbeTask = nil
        consecutiveProbeFailures = 0
        debounceTask = nil
        housekeepingTask = nil
        isRunning = false
        // No "alive" time while stopped: being off on purpose is not a monitoring gap.
        persisted.lastAliveAt = nil
        save()
    }

    /// Removes everything this component stored (leaving, deleting the account, signing out).
    func wipe() async {
        if isRunning { halt() }
        await deps.queue.clear()
        await deps.reporter.reset()
        deps.stateStore.delete()
        deps.fingerprintStore.clear()
        fingerprint = nil
        persisted = PersistedMonitorState()
        latestPath = PathSnapshot()
        lastProbe = nil
        lastProbeAt = nil
        await publish()
    }

    // MARK: Inputs from the system

    func pathChanged(_ snapshot: PathSnapshot) async {
        latestPath = snapshot
        guard isRunning else {
            // Path preview during onboarding (home-network step): nothing is recorded.
            if !isStarting { await publish() }
            return
        }
        guard !reconciling else { return }
        if await handleResumeIfNeeded() { return }
        process(path: snapshot)
        await publish()
        // A connectivity callback is the trigger for retrying uploads that were waiting.
        let reporter = deps.reporter
        Task { await reporter.connectivityChanged() }
    }

    func powerStateChanged(_ state: PowerTracker.PowerState) {
        power.record(state, at: deps.now())
    }

    /// A permission the app relies on was granted or revoked (notifications, Background App Refresh).
    func permissionChanged() async {
        guard isRunning else { return }
        await enqueue(.permissionChanged, at: deps.now(), inputs: persisted.machine.latestInputs)
        await requestFlush()
    }

    /// The app came to the foreground. Detects a suspension gap right away and re-checks reachability.
    func appBecameActive() async {
        guard isRunning else { return }
        if await handleResumeIfNeeded() { return }
        runProbe()
        // The resident just opened the app: try waiting uploads now rather than at the end of a backoff.
        await requestFlush(ignoreBackoff: true)
    }

    /// Called briefly when the app enters the background: note that the monitor is still alive
    /// and push out whatever is queued before iOS suspends the process.
    func prepareForBackground() async {
        guard isRunning else { return }
        markAlive()
        await deps.reporter.flush(ignoreBackoff: true)
        markAlive()
    }

    /// One opportunistic observation, used by BGAppRefreshTask and by silent remote notifications:
    /// observe, send a HEARTBEAT, flush the queue. iOS decides if and when this runs.
    func backgroundRefresh() async {
        guard isRunning else { return }
        if !(await handleResumeIfNeeded()) {
            let inputs = await observe()
            await apply(persisted.machine.ingest(inputs, at: deps.now()))
            if deps.runsTimers, let deadline = persisted.machine.nextDeadline {
                let wait = min(8, max(0, deadline.timeIntervalSince(deps.now())))
                try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
                await apply(persisted.machine.tick(at: deps.now()))
            }
        }
        await sendHeartbeat()
        markAlive()
        await publish()
        await deps.reporter.flush(ignoreBackoff: true)
    }

    /// Periodic work while the process runs: alive timestamp, debounce, probe, heartbeat.
    func housekeeping() async {
        guard isRunning, !reconciling else { return }
        if await handleResumeIfNeeded() { return }
        markAlive()
        let now = deps.now()
        await apply(persisted.machine.tick(at: now))
        if lastProbeAt.map({ now.timeIntervalSince($0) >= periodicProbeInterval }) ?? true {
            runProbe()
        }
        if persisted.lastHeartbeatAt.map({ now.timeIntervalSince($0) >= Self.heartbeatInterval }) ?? true {
            await sendHeartbeat()
            await requestFlush()
        }
        await publish()
    }

    /// Settles a pending change whose debounce time has passed. Driven by a timer in the app and
    /// called directly by tests.
    func tick() async {
        guard isRunning, !reconciling else { return }
        await apply(persisted.machine.tick(at: deps.now()))
        if !persisted.machine.hasPendingChange { confirmationScheduled = false }
        scheduleDebounce()
        await publish()
    }

    /// Waits for a reachability probe that is in flight (used by tests and background work).
    func waitForProbe() async {
        await probeTask?.value
    }

    // MARK: Home network

    /// Lets the onboarding home-network step see whether the phone is on Wi-Fi before monitoring
    /// has started. Only the current path is looked at; no event is created.
    func beginPathPreview() {
        guard !isRunning, !isStarting else { return }
        deps.path.start { [weak self] snapshot in
            Task { await self?.pathChanged(snapshot) }
        }
    }

    /// Stores the fingerprint of the Wi-Fi network the phone is on right now as "home".
    func rememberCurrentNetworkAsHome() async -> HomeNetworkResult {
        let path = deps.path.current()
        guard path.wifiAvailable == true else { return .notOnWifi }
        guard let made = HomeNetworkFingerprint.make(gateways: path.wifiGateways, now: deps.now()) else {
            return .gatewaysUnavailable
        }
        do {
            try deps.fingerprintStore.save(made)
        } catch {
            return .storageFailed
        }
        fingerprint = made
        if isRunning, !reconciling { process(path: latestPath) }
        await publish()
        return .stored
    }

    func forgetHomeNetwork() async {
        deps.fingerprintStore.clear()
        fingerprint = nil
        if isRunning, !reconciling { process(path: latestPath) }
        await publish()
    }

    // MARK: Snapshot

    func snapshot() async -> Snapshot {
        let inputs = persisted.machine.latestInputs
        let path = isRunning ? latestPath : deps.path.current()
        return Snapshot(
            isRunning: isRunning,
            userEnabled: persisted.userEnabled,
            // Not running is never shown as healthy: paused when switched off, unknown otherwise.
            state: !persisted.userEnabled ? .monitoringPaused : (isRunning ? persisted.machine.state : .unknown),
            wifiAssociated: isRunning ? inputs?.wifiAssociated : nil,
            wifiInternetReachable: isRunning ? inputs?.wifiInternetReachable : nil,
            internetReachable: isRunning ? inputs?.internetReachable : nil,
            cellularAvailable: isRunning ? inputs?.cellularAvailable : nil,
            defaultTransport: isRunning ? inputs?.defaultTransport : nil,
            homeNetworkMatch: isRunning ? inputs?.homeNetworkMatch : nil,
            hasHomeFingerprint: fingerprint != nil,
            wifiGatewaysAvailable: path.wifiAvailable == true && !path.wifiGateways.isEmpty,
            onWifiNow: path.wifiAvailable,
            lastObservationAt: persisted.lastObservationAt,
            gaps: persisted.gaps,
            report: await deps.reporter.currentStatus(),
            queueError: await deps.queue.lastPersistenceError
        )
    }

    private func publish() async {
        guard let onSnapshot else { return }
        onSnapshot(await snapshot())
    }

    // MARK: Gap handling

    /// Checks whether the monitor was not running for more than two minutes (the process was
    /// suspended). If so: report the gap first, then reconcile the state before the gap with the
    /// state now. Returns true when a gap was handled.
    private func handleResumeIfNeeded() async -> Bool {
        guard isRunning, !reconciling else { return false }
        let now = deps.now()
        guard let gap = GapDetector.detect(lastAliveAt: persisted.lastAliveAt, now: now, coldLaunch: false,
                                           systemUptime: deps.systemUptime()) else { return false }
        // Claim the gap before the first suspension point so it is handled exactly once.
        persisted.lastAliveAt = now
        reconciling = true
        probeGeneration += 1
        let inputs = await observe()
        let changes = persisted.machine.reconcile(with: inputs, at: gap.endedAt)
        await report(gap: gap, inputs: inputs)
        for change in changes { await enqueue(change) }
        await enqueue(.monitoringResumed, at: gap.endedAt, inputs: inputs)
        persisted.lastHeartbeatAt = now
        reconciling = false
        save()
        await catchUpWithLatestPath()
        await publish()
        await requestFlush()
        return true
    }

    private func report(gap: MonitoringGap, inputs: MonitorInputs) async {
        persisted.gaps.insert(gap, at: 0)
        if persisted.gaps.count > Self.maxStoredGaps {
            persisted.gaps.removeLast(persisted.gaps.count - Self.maxStoredGaps)
        }
        await enqueue(.monitoringGapDetected, at: gap.endedAt, inputs: inputs, gap: gap)
    }

    /// Path callbacks that arrived while a start or a reconciliation was in progress.
    private func catchUpWithLatestPath() async {
        guard isRunning, latestPath.isComplete else { return }
        process(path: latestPath)
    }

    // MARK: Observation

    /// One full observation: the current path plus fresh reachability probes.
    private func observe() async -> MonitorInputs {
        var path = deps.path.current()
        if deps.runsTimers {
            // Right after start the path monitors may not have reported yet. Wait briefly.
            var waited = 0
            while !path.isComplete, waited < 20 {
                try? await Task.sleep(nanoseconds: 100_000_000)
                path = deps.path.current()
                waited += 1
            }
        }
        let probe = await deps.probe.probe(testWithoutCellular: path.wifiAvailable == true)
        consecutiveProbeFailures = probe.anyPath ? 0 : consecutiveProbeFailures + 1
        lastProbeStartedAt = deps.now()
        let after = deps.path.current()
        let probeStillApplies = Self.sameConnectivity(path, after)
        latestPath = after
        lastProbe = probeStillApplies ? probe : nil
        lastProbeAt = deps.now()
        persisted.lastObservationAt = lastProbeAt
        return makeInputs(path: after, probe: lastProbe)
    }

    private static func sameConnectivity(_ a: PathSnapshot, _ b: PathSnapshot) -> Bool {
        a.wifiAvailable == b.wifiAvailable && a.cellularAvailable == b.cellularAvailable
            && a.defaultTransport == b.defaultTransport && a.otherPathUsable == b.otherPathUsable
    }

    private func makeInputs(path: PathSnapshot, probe: ProbeResult?) -> MonitorInputs {
        let onWifi = path.wifiAvailable == true
        return MonitorInputs(
            monitoringEnabled: true,
            permissionMissing: false,
            wifiAssociated: path.wifiAvailable,
            cellularAvailable: path.cellularAvailable,
            otherPathUsable: path.otherPathUsable,
            defaultTransport: path.defaultTransport,
            // A satisfied path does not prove reachability: only the probe result is used here.
            wifiInternetReachable: onWifi ? probe?.withoutCellular : nil,
            internetReachable: probe?.anyPath,
            homeNetworkMatch: onWifi ? fingerprint?.matches(gateways: path.wifiGateways) : nil
        )
    }

    private func process(path: PathSnapshot) {
        let previous = persisted.machine.latestInputs
        var inputs = makeInputs(path: path, probe: lastProbe)
        let connectivityChanged = previous.map {
            $0.wifiAssociated != inputs.wifiAssociated || $0.cellularAvailable != inputs.cellularAvailable
                || $0.defaultTransport != inputs.defaultTransport || $0.otherPathUsable != inputs.otherPathUsable
        } ?? true
        if connectivityChanged {
            // The old probe result says nothing about the new path. Unknown until re-probed.
            lastProbe = nil
            inputs.internetReachable = nil
            inputs.wifiInternetReachable = nil
        }
        // Changes are debounced; the events come out of `tick` once the change has settled.
        _ = persisted.machine.ingest(inputs, at: deps.now())
        if connectivityChanged { runProbe() }
        scheduleDebounce()
    }

    /// 60 seconds normally; doubles with every probe in a row that found nothing reachable, up
    /// to 15 minutes. A path change still triggers a probe (subject to the minimum spacing), so
    /// recovery is noticed promptly without polling a dead network.
    var periodicProbeInterval: TimeInterval {
        let factor = pow(2, Double(min(consecutiveProbeFailures, 8)))
        return min(Self.maximumProbeInterval, Self.probeInterval * factor)
    }

    /// Starts a reachability probe, rate-limited: at most one probe start per
    /// `minimumProbeSpacing`. Requests inside that window are merged into one deferred probe.
    private func runProbe() {
        let now = deps.now()
        if deps.runsTimers, let last = lastProbeStartedAt {
            let elapsed = now.timeIntervalSince(last)
            if elapsed >= 0, elapsed < Self.minimumProbeSpacing {
                guard deferredProbeTask == nil else { return }
                let wait = Self.minimumProbeSpacing - elapsed
                deferredProbeTask = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
                    await self?.deferredProbeDue()
                }
                return
            }
        }
        lastProbeStartedAt = now
        probeGeneration += 1
        let generation = probeGeneration
        let probe = deps.probe
        let testWithoutCellular = latestPath.wifiAvailable == true
        probeTask = Task { [weak self] in
            let result = await probe.probe(testWithoutCellular: testWithoutCellular)
            await self?.probeFinished(result, generation: generation)
        }
    }

    private func deferredProbeDue() {
        deferredProbeTask = nil
        guard isRunning, !reconciling else { return }
        runProbe()
    }

    private func probeFinished(_ result: ProbeResult, generation: Int) async {
        // A newer path change started a newer probe: this result describes an old situation.
        guard isRunning, !reconciling, generation == probeGeneration else { return }
        let now = deps.now()
        consecutiveProbeFailures = result.anyPath ? 0 : consecutiveProbeFailures + 1
        lastProbe = result
        lastProbeAt = now
        persisted.lastObservationAt = now
        let changes = persisted.machine.ingest(makeInputs(path: latestPath, probe: result), at: now)
        await apply(changes)
        scheduleDebounce()
        scheduleConfirmationProbe()
        save()
        await publish()
    }

    // MARK: Events

    private func apply(_ changes: [StateChange]) async {
        guard !changes.isEmpty else { return }
        for change in changes { await enqueue(change) }
        save()
        await publish()
        await requestFlush()
    }

    /// Asks the reporter to upload. New events respect a running backoff; connectivity callbacks
    /// are what cut a backoff short. While the app runs normally this does not wait for the
    /// upload (which may wait for connectivity); tests wait so they can assert on the result.
    private func requestFlush(ignoreBackoff: Bool = false) async {
        let reporter = deps.reporter
        if deps.runsTimers {
            Task { await reporter.flush(ignoreBackoff: ignoreBackoff) }
        } else {
            await reporter.flush(ignoreBackoff: ignoreBackoff)
        }
    }

    private func sendHeartbeat() async {
        let now = deps.now()
        persisted.lastHeartbeatAt = now
        await enqueue(.heartbeat, at: now, inputs: persisted.machine.latestInputs)
        save()
    }

    private func enqueue(_ change: StateChange) async {
        await enqueue(change.eventType, at: change.occurredAt, inputs: change.inputs, state: change.state,
                      confidence: change.confidence, homeNetworkMatch: .some(change.homeNetworkMatch))
    }

    private func enqueue(
        _ type: EventType, at occurredAt: Date, inputs: MonitorInputs?, state: ConnectivityState? = nil,
        confidence: ObservationConfidence = .observed, homeNetworkMatch: Bool?? = nil,
        userInitiated: Bool? = nil, gap: MonitoringGap? = nil
    ) async {
        let now = deps.now()
        var diagnostics = Diagnostics()
        diagnostics.wifiAssociated = inputs?.wifiAssociated
        diagnostics.wifiValidated = inputs?.wifiInternetReachable
        diagnostics.homeNetworkMatch = homeNetworkMatch ?? inputs?.homeNetworkMatch
        diagnostics.internetReachable = inputs?.internetReachable
        diagnostics.cellularAvailable = inputs?.cellularAvailable
        diagnostics.defaultTransport = inputs?.defaultTransport
        diagnostics.networkChangedRecently = persisted.machine.networkChangedRecently(at: now)
        diagnostics.batterySaver = deps.lowPowerMode()
        diagnostics.externalPowerLostRecently = power.externalPowerLostRecently(eventTime: occurredAt)
        diagnostics.userInitiated = userInitiated
        diagnostics.gapStartedAt = gap?.startedAt
        diagnostics.gapEndedAt = gap?.endedAt
        diagnostics.gapReason = gap?.reason
        // Not measurable on iOS and therefore left nil: wifi_radio_enabled, airplane_mode,
        // local_gateway_reachable, last_wifi_rssi_dbm, vpn_active. band_evidence stays BAND_UNKNOWN.

        let eventState = state ?? persisted.machine.state
        let osVersion = deps.osVersion, appVersion = deps.appVersion
        let finished = diagnostics
        await deps.queue.enqueue(now: now) { sequence in
            ConnectivityEvent(
                eventID: UUID(), sequence: sequence, eventType: type, connectivityState: eventState,
                occurredAt: occurredAt, osVersion: osVersion, appVersion: appVersion,
                confidence: confidence, diagnostics: finished)
        }
    }

    // MARK: Timers and persistence

    private func markAlive() {
        persisted.lastAliveAt = deps.now()
        save()
    }

    private func save() {
        try? deps.stateStore.save(persisted)
    }

    private func startTimers() {
        guard deps.runsTimers else { return }
        housekeepingTask?.cancel()
        housekeepingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(Self.aliveInterval * 1_000_000_000))
                guard !Task.isCancelled else { return }
                await self?.housekeeping()
            }
        }
    }

    /// A single probe can fail for a passing reason. When a probe result opens a pending change,
    /// probe once more inside the debounce time: if the second result disagrees, the change is
    /// dropped before any event is created.
    private func scheduleConfirmationProbe() {
        guard deps.runsTimers else { return }
        guard persisted.machine.hasPendingChange else {
            confirmationScheduled = false
            return
        }
        guard !confirmationScheduled else { return }
        confirmationScheduled = true
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            await self?.runConfirmationProbe()
        }
    }

    private func runConfirmationProbe() {
        guard isRunning, !reconciling, persisted.machine.hasPendingChange else {
            confirmationScheduled = false
            return
        }
        runProbe()
    }

    private func scheduleDebounce() {
        guard deps.runsTimers else { return }
        debounceTask?.cancel()
        debounceTask = nil
        guard let deadline = persisted.machine.nextDeadline else { return }
        let delay = max(0.05, deadline.timeIntervalSince(deps.now()))
        debounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.tick()
        }
    }
}
