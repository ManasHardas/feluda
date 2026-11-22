import XCTest
@testable import NeighborhoodWatchdog

final class StateMachineTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    private func healthyMachine() -> ConnectivityStateMachine {
        var machine = ConnectivityStateMachine()
        machine.begin(with: .healthy, freshSession: true)
        return machine
    }

    private func lostWifi(internet: Bool? = true) -> MonitorInputs {
        MonitorInputs(wifiAssociated: false, cellularAvailable: true, defaultTransport: .cellular,
                      wifiInternetReachable: nil, internetReachable: internet)
    }

    func testStartsHealthy() {
        let machine = healthyMachine()
        XCTAssertEqual(machine.state, .healthy)
        XCTAssertNil(machine.nextDeadline)
    }

    func testWifiLossIsDebouncedAndTimestampedAtFirstCallback() {
        var machine = healthyMachine()
        // First callback: Wi-Fi gone, reachability not probed yet.
        XCTAssertEqual(machine.ingest(lostWifi(internet: nil), at: t0), [])
        XCTAssertEqual(machine.state, .networkTransition)
        // Probe answers over cellular one second later.
        XCTAssertEqual(machine.ingest(lostWifi(), at: t0.addingTimeInterval(1)), [])
        XCTAssertEqual(machine.tick(at: t0.addingTimeInterval(4)), [], "must not classify before the debounce time")
        let changes = machine.tick(at: t0.addingTimeInterval(6.5))
        XCTAssertEqual(changes.map(\.eventType), [.wifiLost, .defaultNetworkChanged])
        XCTAssertTrue(changes.allSatisfy { $0.occurredAt == self.t0 }, "occurred_at is the first callback, not the end of the debounce")
        XCTAssertTrue(changes.allSatisfy { $0.state == .wifiDisconnected && $0.confidence == .observed })
        XCTAssertEqual(machine.state, .wifiDisconnected)
    }

    func testCellularTransitionChangesDefaultNetwork() {
        var machine = healthyMachine()
        _ = machine.ingest(lostWifi(), at: t0)
        let changes = machine.tick(at: t0.addingTimeInterval(5))
        let change = changes.first { $0.eventType == .defaultNetworkChanged }
        XCTAssertEqual(change?.inputs.defaultTransport, .cellular)
        XCTAssertEqual(change?.state, .wifiDisconnected)
    }

    func testWifiRecoveryAfterLoss() {
        var machine = healthyMachine()
        _ = machine.ingest(lostWifi(), at: t0)
        _ = machine.tick(at: t0.addingTimeInterval(5))
        let back = t0.addingTimeInterval(300)
        // Associated again; the probe has not answered yet, so nothing is settled.
        var returning = MonitorInputs.healthy
        returning.wifiInternetReachable = nil
        returning.internetReachable = nil
        XCTAssertEqual(machine.ingest(returning, at: back), [])
        XCTAssertEqual(machine.tick(at: back.addingTimeInterval(6)), [], "waits for the reachability probe")
        _ = machine.ingest(.healthy, at: back.addingTimeInterval(7))
        let changes = machine.tick(at: back.addingTimeInterval(12))
        XCTAssertEqual(changes.map(\.eventType), [.wifiRestored, .defaultNetworkChanged])
        XCTAssertEqual(changes.first?.occurredAt, back)
        XCTAssertEqual(machine.state, .healthy)
        XCTAssertFalse(machine.awaitingWifiRestore)
    }

    func testWifiAssociatedAgainWithoutInternetIsNotARestore() {
        var machine = healthyMachine()
        _ = machine.ingest(lostWifi(), at: t0)
        _ = machine.tick(at: t0.addingTimeInterval(5))
        var noInternet = MonitorInputs.healthy
        noInternet.wifiInternetReachable = false
        _ = machine.ingest(noInternet, at: t0.addingTimeInterval(100))
        let first = machine.tick(at: t0.addingTimeInterval(105))
        XCTAssertFalse(first.map(\.eventType).contains(.wifiRestored))
        XCTAssertTrue(first.map(\.eventType).contains(.wifiAssociationChanged))
        XCTAssertEqual(machine.state, .wifiConnectedNoInternet)
        // Internet returns over Wi-Fi: now it is a restore.
        _ = machine.ingest(.healthy, at: t0.addingTimeInterval(200))
        let second = machine.tick(at: t0.addingTimeInterval(205))
        XCTAssertEqual(second.map(\.eventType), [.wifiRestored])
        XCTAssertEqual(machine.state, .healthy)
    }

    func testWifiWithoutInternet() {
        var machine = healthyMachine()
        var noInternet = MonitorInputs.healthy
        noInternet.wifiInternetReachable = false
        noInternet.internetReachable = false
        _ = machine.ingest(noInternet, at: t0)
        let down = machine.tick(at: t0.addingTimeInterval(5))
        XCTAssertEqual(down.map(\.eventType), [.internetReachabilityChanged])
        XCTAssertEqual(down.first?.state, .wifiConnectedNoInternet)
        XCTAssertEqual(machine.state, .wifiConnectedNoInternet)

        _ = machine.ingest(.healthy, at: t0.addingTimeInterval(60))
        let up = machine.tick(at: t0.addingTimeInterval(65))
        XCTAssertEqual(up.map(\.eventType), [.internetReachabilityChanged])
        XCTAssertEqual(up.first?.state, .healthy)
    }

    func testSatisfiedWifiPathWithoutProbeResultIsNotHealthy() {
        var inputs = MonitorInputs.healthy
        inputs.wifiInternetReachable = nil
        XCTAssertEqual(ConnectivityStateMachine.classify(inputs, wifiSeen: true), .unknown)
    }

    func testTotalLossLikeAirplaneMode() {
        var machine = healthyMachine()
        let nothing = MonitorInputs(wifiAssociated: false, cellularAvailable: false, defaultTransport: Transport.none,
                                    wifiInternetReachable: nil, internetReachable: false)
        _ = machine.ingest(nothing, at: t0)
        let changes = machine.tick(at: t0.addingTimeInterval(5))
        XCTAssertEqual(Set(changes.map(\.eventType)), [.wifiLost, .defaultNetworkChanged, .cellularUnavailable])
        XCTAssertEqual(changes.first?.eventType, .wifiLost)
        XCTAssertTrue(changes.allSatisfy { $0.state == .internetUnavailable })
        XCTAssertEqual(machine.state, .internetUnavailable)
    }

    func testCellularLossWhileOnWifi() {
        var machine = healthyMachine()
        var inputs = MonitorInputs.healthy
        inputs.cellularAvailable = false
        _ = machine.ingest(inputs, at: t0)
        let changes = machine.tick(at: t0.addingTimeInterval(5))
        XCTAssertEqual(changes.map(\.eventType), [.cellularUnavailable])
        XCTAssertEqual(machine.state, .healthy)
        inputs.cellularAvailable = true
        _ = machine.ingest(inputs, at: t0.addingTimeInterval(50))
        XCTAssertEqual(machine.tick(at: t0.addingTimeInterval(55)).map(\.eventType), [.cellularAvailable])
    }

    func testDebounceSwallowsAShortRoam() {
        var machine = healthyMachine()
        _ = machine.ingest(lostWifi(internet: nil), at: t0)
        XCTAssertEqual(machine.state, .networkTransition)
        // Back on the same network three seconds later.
        XCTAssertEqual(machine.ingest(.healthy, at: t0.addingTimeInterval(3)), [])
        XCTAssertEqual(machine.state, .healthy)
        XCTAssertNil(machine.nextDeadline)
        XCTAssertEqual(machine.tick(at: t0.addingTimeInterval(30)), [])
        XCTAssertFalse(machine.awaitingWifiRestore)
    }

    func testDebounceRestartsWithEachNewCallbackButIsBounded() {
        var machine = healthyMachine()
        _ = machine.ingest(lostWifi(), at: t0)
        var flapping = lostWifi()
        flapping.cellularAvailable = false
        flapping.defaultTransport = Transport.none
        _ = machine.ingest(flapping, at: t0.addingTimeInterval(4))
        XCTAssertEqual(machine.tick(at: t0.addingTimeInterval(6)), [], "the second callback restarted the quiet time")
        XCTAssertFalse(machine.tick(at: t0.addingTimeInterval(9.5)).isEmpty)

        // A change whose probe never answers still settles at the hard limit.
        var second = healthyMachine()
        _ = second.ingest(lostWifi(internet: nil), at: t0)
        XCTAssertEqual(second.tick(at: t0.addingTimeInterval(29)), [])
        XCTAssertEqual(second.tick(at: t0.addingTimeInterval(30)).map(\.eventType), [.wifiLost, .defaultNetworkChanged])
    }

    func testMonitoringDisabledIsNeverHealthy() {
        // Whatever the network looks like, a monitor that is off is MONITORING_PAUSED.
        for wifi in [true, false, nil] as [Bool?] {
            for internet in [true, false, nil] as [Bool?] {
                for cellular in [true, false, nil] as [Bool?] {
                    let inputs = MonitorInputs(monitoringEnabled: false, wifiAssociated: wifi, cellularAvailable: cellular,
                                               defaultTransport: .wifi, wifiInternetReachable: internet, internetReachable: internet)
                    for seen in [true, false] {
                        XCTAssertEqual(ConnectivityStateMachine.classify(inputs, wifiSeen: seen), .monitoringPaused)
                    }
                }
            }
        }
        var machine = healthyMachine()
        machine.pause()
        XCTAssertEqual(machine.state, .monitoringPaused)
        XCTAssertEqual(machine.settledState, .monitoringPaused)
        // Observations arriving while paused do not produce events and do not make it healthy.
        var paused = MonitorInputs.healthy
        paused.monitoringEnabled = false
        XCTAssertEqual(machine.ingest(paused, at: t0), [])
        XCTAssertEqual(machine.tick(at: t0.addingTimeInterval(60)), [])
        XCTAssertEqual(machine.state, .monitoringPaused)
    }

    func testPermissionMissing() {
        var inputs = MonitorInputs.healthy
        inputs.permissionMissing = true
        XCTAssertEqual(ConnectivityStateMachine.classify(inputs, wifiSeen: true), .monitoringPermissionRequired)
    }

    func testUnknownWhenNothingIsKnown() {
        XCTAssertEqual(ConnectivityStateMachine.classify(MonitorInputs(), wifiSeen: false), .unknown)
        XCTAssertEqual(ConnectivityStateMachine().state, .unknown)
    }

    func testCellularOnlySessionUsesCellularStates() {
        var machine = ConnectivityStateMachine()
        machine.begin(with: lostWifi(), freshSession: true)
        XCTAssertEqual(machine.state, .cellularAvailable)
        var none = lostWifi()
        none.cellularAvailable = false
        _ = machine.ingest(none, at: t0)
        _ = machine.tick(at: t0.addingTimeInterval(5))
        XCTAssertEqual(machine.state, .cellularUnavailable)
        // Once Wi-Fi has been seen, losing it is WIFI_DISCONNECTED, not a cellular-only state.
        _ = machine.ingest(.healthy, at: t0.addingTimeInterval(100))
        _ = machine.tick(at: t0.addingTimeInterval(105))
        _ = machine.ingest(lostWifi(), at: t0.addingTimeInterval(200))
        _ = machine.tick(at: t0.addingTimeInterval(205))
        XCTAssertEqual(machine.state, .wifiDisconnected)
    }

    func testHomeNetworkMatchOfLostNetworkIsKept() {
        var machine = ConnectivityStateMachine()
        var home = MonitorInputs.healthy
        home.homeNetworkMatch = true
        machine.begin(with: home, freshSession: true)
        _ = machine.ingest(lostWifi(), at: t0)
        let changes = machine.tick(at: t0.addingTimeInterval(5))
        XCTAssertEqual(changes.first?.eventType, .wifiLost)
        XCTAssertEqual(changes.first?.homeNetworkMatch, true)
    }

    func testAssociationChangeBetweenHomeAndOtherNetwork() {
        var machine = ConnectivityStateMachine()
        var home = MonitorInputs.healthy
        home.homeNetworkMatch = true
        machine.begin(with: home, freshSession: true)
        var other = home
        other.homeNetworkMatch = false
        _ = machine.ingest(other, at: t0)
        let changes = machine.tick(at: t0.addingTimeInterval(5))
        XCTAssertEqual(changes.map(\.eventType), [.wifiAssociationChanged])
        XCTAssertEqual(changes.first?.homeNetworkMatch, false)
    }

    func testReconcileUsesResumeTimeAndReconciledConfidence() {
        var machine = healthyMachine()
        let resume = t0.addingTimeInterval(3_600)
        let changes = machine.reconcile(with: lostWifi(), at: resume)
        XCTAssertEqual(changes.map(\.eventType), [.wifiLost, .defaultNetworkChanged])
        XCTAssertTrue(changes.allSatisfy { $0.confidence == .reconciled })
        XCTAssertTrue(changes.allSatisfy { $0.occurredAt == resume }, "a reconciled event is never back-dated")
        XCTAssertEqual(machine.state, .wifiDisconnected)
        // Nothing changed: nothing to report.
        XCTAssertEqual(machine.reconcile(with: lostWifi(), at: resume.addingTimeInterval(600)), [])
    }

    func testPersistedMachineKeepsSettledStateButNotPendingChanges() throws {
        var machine = healthyMachine()
        _ = machine.ingest(lostWifi(), at: t0)
        _ = machine.tick(at: t0.addingTimeInterval(5))
        _ = machine.ingest(.healthy, at: t0.addingTimeInterval(50))
        let data = try WatchdogJSON.encoder().encode(machine)
        let restored = try WatchdogJSON.decoder().decode(ConnectivityStateMachine.self, from: data)
        XCTAssertEqual(restored.settledState, .wifiDisconnected)
        XCTAssertFalse(restored.hasPendingChange)
        XCTAssertTrue(restored.awaitingWifiRestore)
        XCTAssertTrue(restored.wifiSeen)
    }

    func testNetworkChangedRecently() {
        var machine = healthyMachine()
        XCTAssertEqual(machine.networkChangedRecently(at: t0), false)
        _ = machine.ingest(lostWifi(), at: t0)
        XCTAssertEqual(machine.networkChangedRecently(at: t0.addingTimeInterval(1)), true)
        _ = machine.tick(at: t0.addingTimeInterval(5))
        XCTAssertEqual(machine.networkChangedRecently(at: t0.addingTimeInterval(30)), true)
        XCTAssertEqual(machine.networkChangedRecently(at: t0.addingTimeInterval(500)), false)
        XCTAssertNil(ConnectivityStateMachine().networkChangedRecently(at: t0))
    }
}
