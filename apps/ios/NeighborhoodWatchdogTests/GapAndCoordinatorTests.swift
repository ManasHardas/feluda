import XCTest
@testable import NeighborhoodWatchdog

final class GapDetectorTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func testNoGapWithoutEarlierRunOrUnderThreshold() {
        XCTAssertNil(GapDetector.detect(lastAliveAt: nil, now: t0, coldLaunch: true, systemUptime: 10))
        XCTAssertNil(GapDetector.detect(lastAliveAt: t0, now: t0.addingTimeInterval(120), coldLaunch: false, systemUptime: 99_999))
        XCTAssertNil(GapDetector.detect(lastAliveAt: t0.addingTimeInterval(50), now: t0, coldLaunch: false, systemUptime: 99_999),
                     "a clock that moved backwards is not a gap")
    }

    func testSuspension() {
        let gap = GapDetector.detect(lastAliveAt: t0, now: t0.addingTimeInterval(121), coldLaunch: false, systemUptime: 99_999)
        XCTAssertEqual(gap, MonitoringGap(startedAt: t0, endedAt: t0.addingTimeInterval(121), reason: .appSuspended))
    }

    func testTermination() {
        let gap = GapDetector.detect(lastAliveAt: t0, now: t0.addingTimeInterval(900), coldLaunch: true, systemUptime: 99_999)
        XCTAssertEqual(gap?.reason, .processTerminated)
    }

    func testRebootWhenUptimeIsShorterThanTheGap() {
        let cold = GapDetector.detect(lastAliveAt: t0, now: t0.addingTimeInterval(900), coldLaunch: true, systemUptime: 300)
        XCTAssertEqual(cold?.reason, .deviceReboot)
        XCTAssertEqual(cold?.startedAt, t0)
        XCTAssertEqual(cold?.duration, 900)
        // Uptime unknown: fall back to what is known.
        XCTAssertEqual(GapDetector.detect(lastAliveAt: t0, now: t0.addingTimeInterval(900), coldLaunch: true, systemUptime: nil)?.reason,
                       .processTerminated)
    }
}

/// Drives the monitoring component with a fake path, a fake probe and a fake clock.
final class MonitoringCoordinatorTests: XCTestCase {
    private struct Rig {
        let clock: TestClock
        let path: FakePath
        let probe: FakeProbe
        let queue: EventQueue
        let fingerprints: MemoryFingerprintStore
        let directory: URL
        let uptime: Locked<TimeInterval?>

        /// A new coordinator on the same storage, like a new process after the old one ended.
        func makeCoordinator() -> MonitoringCoordinator {
            let sentLog = SentLog(directory: directory)
            // No device id: the reporter never uploads, so events stay in the queue for inspection.
            let reporter = EventReporter(queue: queue, sentLog: sentLog, dependencies: .init(
                submit: { _ in throw URLError(.notConnectedToInternet) },
                deviceID: { nil },
                currentTransport: { .unknown }), schedulesRetries: false)
            return MonitoringCoordinator(dependencies: .init(
                path: path, probe: probe, queue: queue, reporter: reporter,
                stateStore: JSONFileStore(url: directory.appendingPathComponent("monitor-state.json")),
                fingerprintStore: fingerprints, osVersion: "17.4", appVersion: "1.0.0 (1)",
                now: clock.provider, systemUptime: { [uptime] in uptime.value }, lowPowerMode: { false }, runsTimers: false))
        }

        func events() async -> [ConnectivityEvent] { await queue.entries().map(\.event) }
    }

    private func makeRig() -> Rig {
        let directory = TestFiles.temporaryDirectory()
        return Rig(clock: TestClock(), path: FakePath(.homeWifi), probe: FakeProbe(ProbeResult(anyPath: true, withoutCellular: true)),
                   queue: EventQueue(directory: directory), fingerprints: MemoryFingerprintStore(), directory: directory,
                   uptime: Locked(1_000_000))
    }

    func testFirstStartEmitsMonitoringStarted() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        let events = await rig.events()
        XCTAssertEqual(events.map(\.eventType), [.monitoringStarted])
        XCTAssertEqual(events[0].connectivityState, .healthy)
        XCTAssertEqual(events[0].diagnostics.userInitiated, true)
        XCTAssertEqual(events[0].occurredAt, rig.clock.now)
        let snapshot = await coordinator.snapshot()
        XCTAssertEqual(snapshot.state, .healthy)
        XCTAssertEqual(snapshot.lastObservationAt, rig.clock.now)
    }

    func testLiveWifiLossGoesThroughDebounceAndKeepsCallbackTime() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        _ = await coordinator.rememberCurrentNetworkAsHome()

        rig.clock.advance(100)
        let callbackTime = rig.clock.now
        rig.path.snapshot.value = .cellularOnly
        rig.probe.result.value = ProbeResult(anyPath: true, withoutCellular: nil)
        await coordinator.pathChanged(.cellularOnly)
        await coordinator.waitForProbe()
        let transitional = await coordinator.snapshot()
        XCTAssertEqual(transitional.state, .networkTransition)

        rig.clock.advance(3)
        await coordinator.tick()
        let early = await rig.events()
        XCTAssertFalse(early.map(\.eventType).contains(.wifiLost), "nothing is classified inside the debounce time")

        rig.clock.advance(4)
        await coordinator.tick()
        let events = await rig.events()
        let lost = try? XCTUnwrap(events.first { $0.eventType == .wifiLost })
        XCTAssertEqual(lost?.occurredAt, callbackTime)
        XCTAssertEqual(lost?.confidence, .observed)
        XCTAssertEqual(lost?.connectivityState, .wifiDisconnected)
        XCTAssertEqual(lost?.diagnostics.homeNetworkMatch, true, "the lost network was the home network")
        XCTAssertEqual(lost?.diagnostics.wifiAssociated, false)
        XCTAssertEqual(lost?.diagnostics.cellularAvailable, true)
        XCTAssertEqual(lost?.diagnostics.internetReachable, true)
        XCTAssertEqual(lost?.diagnostics.defaultTransport, .cellular)
        // iOS cannot measure these: they must be nil, and the band must be BAND_UNKNOWN.
        XCTAssertNil(lost?.diagnostics.lastWifiRssiDbm)
        XCTAssertNil(lost?.diagnostics.wifiRadioEnabled)
        XCTAssertNil(lost?.diagnostics.airplaneMode)
        XCTAssertNil(lost?.diagnostics.localGatewayReachable)
        XCTAssertNil(lost?.diagnostics.vpnActive)
        XCTAssertEqual(lost?.diagnostics.bandEvidence, .bandUnknown)
        XCTAssertNil(lost?.diagnostics.externalPowerLostRecently, "power state unknown: nil, not false")
        XCTAssertTrue(events.map(\.eventType).contains(.defaultNetworkChanged))
    }

    func testShortRoamProducesNoEvent() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        rig.clock.advance(50)
        rig.path.snapshot.value = .cellularOnly
        await coordinator.pathChanged(.cellularOnly)
        await coordinator.waitForProbe()
        rig.clock.advance(2)
        rig.path.snapshot.value = .homeWifi
        await coordinator.pathChanged(.homeWifi)
        await coordinator.waitForProbe()
        rig.clock.advance(30)
        await coordinator.tick()
        let types = await rig.events().map(\.eventType)
        XCTAssertEqual(types, [.monitoringStarted])
    }

    func testSuspensionGapIsReportedFirstThenReconciled() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        let lastAlive = rig.clock.now

        // The app is suspended for ten minutes. Wi-Fi goes away in the meantime.
        rig.clock.advance(600)
        rig.path.snapshot.value = .cellularOnly
        rig.probe.result.value = ProbeResult(anyPath: true, withoutCellular: nil)
        let resume = rig.clock.now
        await coordinator.appBecameActive()

        let events = await rig.events()
        XCTAssertEqual(events.map(\.eventType),
                       [.monitoringStarted, .monitoringGapDetected, .wifiLost, .defaultNetworkChanged, .monitoringResumed])
        XCTAssertEqual(events.map(\.sequence), [0, 1, 2, 3, 4])
        let gap = events[1]
        XCTAssertEqual(gap.diagnostics.gapStartedAt, lastAlive)
        XCTAssertEqual(gap.diagnostics.gapEndedAt, resume)
        XCTAssertEqual(gap.diagnostics.gapReason, .appSuspended)
        XCTAssertEqual(gap.occurredAt, resume)
        let lost = events[2]
        XCTAssertEqual(lost.confidence, .reconciled)
        XCTAssertEqual(lost.occurredAt, resume, "reconciled events carry the resume time, never a guess inside the gap")
        XCTAssertEqual(lost.connectivityState, .wifiDisconnected)
        XCTAssertEqual(events[4].confidence, .observed)

        let snapshot = await coordinator.snapshot()
        XCTAssertEqual(snapshot.gaps, [MonitoringGap(startedAt: lastAlive, endedAt: resume, reason: .appSuspended)])
        XCTAssertEqual(snapshot.state, .wifiDisconnected)

        // A second foregrounding right away finds no new gap.
        rig.clock.advance(5)
        await coordinator.appBecameActive()
        let after = await rig.events()
        XCTAssertEqual(after.count, 5)
    }

    func testSuspensionGapWithoutStateChangeReportsOnlyTheGap() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        rig.clock.advance(300)
        await coordinator.housekeeping()
        let types = await rig.events().map(\.eventType)
        XCTAssertEqual(types, [.monitoringStarted, .monitoringGapDetected, .monitoringResumed])
    }

    func testNoGapUnderTwoMinutes() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        rig.clock.advance(90)
        await coordinator.appBecameActive()
        let types = await rig.events().map(\.eventType)
        XCTAssertEqual(types, [.monitoringStarted])
    }

    func testTerminationGapOnColdLaunch() async {
        let rig = makeRig()
        let first = rig.makeCoordinator()
        await first.start(reason: .firstStart)
        let lastAlive = rig.clock.now

        // The process is gone for an hour; a new process starts with the same storage.
        rig.clock.advance(3_600)
        rig.path.snapshot.value = .nothing
        rig.probe.result.value = ProbeResult(anyPath: false, withoutCellular: nil)
        let second = rig.makeCoordinator()
        await second.start(reason: .processLaunch)

        let events = await rig.events()
        XCTAssertEqual(events.map(\.eventType), [.monitoringStarted, .monitoringGapDetected, .wifiLost, .defaultNetworkChanged,
                                                 .cellularUnavailable, .monitoringResumed])
        XCTAssertEqual(events[1].diagnostics.gapReason, .processTerminated)
        XCTAssertEqual(events[1].diagnostics.gapStartedAt, lastAlive)
        XCTAssertEqual(events[1].connectivityState, .internetUnavailable)
        XCTAssertTrue(events[2...4].allSatisfy { $0.confidence == .reconciled && $0.occurredAt == rig.clock.now })
    }

    func testRebootGapOnColdLaunch() async {
        let rig = makeRig()
        let first = rig.makeCoordinator()
        await first.start(reason: .firstStart)
        rig.clock.advance(3_600)
        rig.uptime.value = 120 // the phone booted two minutes ago
        let second = rig.makeCoordinator()
        await second.start(reason: .processLaunch)
        let events = await rig.events()
        XCTAssertEqual(events.map(\.eventType), [.monitoringStarted, .monitoringGapDetected, .monitoringResumed])
        XCTAssertEqual(events[1].diagnostics.gapReason, .deviceReboot)
    }

    func testDisabledByUserIsPausedAndNeverHealthy() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        rig.clock.advance(30)
        await coordinator.disableByUser()

        let events = await rig.events()
        XCTAssertEqual(events.map(\.eventType), [.monitoringStarted, .monitoringDisabledByUser])
        XCTAssertEqual(events[1].connectivityState, .monitoringPaused)
        XCTAssertEqual(events[1].diagnostics.userInitiated, true)
        var snapshot = await coordinator.snapshot()
        XCTAssertEqual(snapshot.state, .monitoringPaused)
        XCTAssertFalse(snapshot.isRunning)
        XCTAssertFalse(rig.path.started.value, "the path monitors are stopped")

        // A later launch with healthy Wi-Fi: still paused, no events, no gap.
        rig.clock.advance(7_200)
        let relaunched = rig.makeCoordinator()
        await relaunched.start(reason: .processLaunch)
        snapshot = await relaunched.snapshot()
        XCTAssertEqual(snapshot.state, .monitoringPaused)
        XCTAssertNotEqual(snapshot.state, .healthy)
        let afterRelaunch = await rig.events()
        XCTAssertEqual(afterRelaunch.count, 2)

        // Switching it back on starts a fresh observation without a gap event.
        await relaunched.start(reason: .userEnabled)
        let types = await rig.events().map(\.eventType)
        XCTAssertEqual(types, [.monitoringStarted, .monitoringDisabledByUser, .monitoringStarted])
        snapshot = await relaunched.snapshot()
        XCTAssertEqual(snapshot.state, .healthy)
    }

    func testNotStartedIsUnknownNotHealthy() async {
        let rig = makeRig()
        let snapshot = await rig.makeCoordinator().snapshot()
        XCTAssertEqual(snapshot.state, .unknown)
    }

    func testBackgroundRefreshSendsHeartbeat() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        rig.clock.advance(60)
        await coordinator.backgroundRefresh()
        let events = await rig.events()
        XCTAssertEqual(events.map(\.eventType), [.monitoringStarted, .heartbeat])
        XCTAssertEqual(events[1].connectivityState, .healthy)
    }

    func testForegroundHeartbeatEveryFiveMinutes() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        for _ in 0..<31 { // 310 seconds in 10-second steps: the process stays alive, so no gap
            rig.clock.advance(10)
            await coordinator.housekeeping()
        }
        let types = await rig.events().map(\.eventType)
        XCTAssertEqual(types, [.monitoringStarted, .heartbeat])
    }

    func testPeriodicProbeBacksOffWhileNothingIsReachable() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        var interval = await coordinator.periodicProbeInterval
        XCTAssertEqual(interval, 60)
        rig.probe.result.value = ProbeResult(anyPath: false, withoutCellular: false)
        for _ in 0..<3 {
            rig.clock.advance(100)
            await coordinator.appBecameActive()
            await coordinator.waitForProbe()
        }
        interval = await coordinator.periodicProbeInterval
        XCTAssertEqual(interval, 480, "60 s doubled for each of three failed probes")
        for _ in 0..<10 {
            rig.clock.advance(100)
            await coordinator.appBecameActive()
            await coordinator.waitForProbe()
        }
        interval = await coordinator.periodicProbeInterval
        XCTAssertEqual(interval, 900, "capped at 15 minutes")
        rig.probe.result.value = ProbeResult(anyPath: true, withoutCellular: true)
        rig.clock.advance(100)
        await coordinator.appBecameActive()
        await coordinator.waitForProbe()
        interval = await coordinator.periodicProbeInterval
        XCTAssertEqual(interval, 60, "back to normal once a probe succeeds")
    }

    func testHomeNetworkCannotBeStoredWithoutGateways() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        var noGateways = PathSnapshot.homeWifi
        noGateways.wifiGateways = []
        rig.path.snapshot.value = noGateways
        let unavailable = await coordinator.rememberCurrentNetworkAsHome()
        XCTAssertEqual(unavailable, .gatewaysUnavailable)
        rig.path.snapshot.value = .cellularOnly
        let offWifi = await coordinator.rememberCurrentNetworkAsHome()
        XCTAssertEqual(offWifi, .notOnWifi)
        XCTAssertNil(rig.fingerprints.fingerprint.value)
    }

    func testWipeRemovesQueueStateAndFingerprint() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.start(reason: .firstStart)
        _ = await coordinator.rememberCurrentNetworkAsHome()
        XCTAssertNotNil(rig.fingerprints.fingerprint.value)
        await coordinator.wipe()
        let remaining = await rig.queue.count
        XCTAssertEqual(remaining, 0)
        XCTAssertNil(rig.fingerprints.fingerprint.value)
        XCTAssertFalse(FileManager.default.fileExists(atPath: rig.directory.appendingPathComponent("monitor-state.json").path))
    }

    func testChargerUnpluggedAtTheSameMomentIsReported() async {
        let rig = makeRig()
        let coordinator = rig.makeCoordinator()
        await coordinator.powerStateChanged(.externalPower)
        await coordinator.start(reason: .firstStart)
        rig.clock.advance(100)
        await coordinator.powerStateChanged(.onBattery)
        rig.clock.advance(2)
        rig.path.snapshot.value = .cellularOnly
        await coordinator.pathChanged(.cellularOnly)
        await coordinator.waitForProbe()
        rig.clock.advance(6)
        await coordinator.tick()
        let lost = await rig.events().first { $0.eventType == .wifiLost }
        XCTAssertEqual(lost?.diagnostics.externalPowerLostRecently, true)
    }
}

final class PowerTrackerTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func testRule() {
        var tracker = PowerTracker()
        XCTAssertNil(tracker.externalPowerLostRecently(eventTime: t0), "unknown power state")
        tracker.record(.onBattery, at: t0)
        XCTAssertNil(tracker.externalPowerLostRecently(eventTime: t0), "not charging: nil")
        tracker.record(.externalPower, at: t0.addingTimeInterval(10))
        XCTAssertEqual(tracker.externalPowerLostRecently(eventTime: t0.addingTimeInterval(20)), false, "charging and still charging")
        tracker.record(.onBattery, at: t0.addingTimeInterval(100))
        XCTAssertEqual(tracker.externalPowerLostRecently(eventTime: t0.addingTimeInterval(90)), true, "unplugged 10 s after the event")
        XCTAssertEqual(tracker.externalPowerLostRecently(eventTime: t0.addingTimeInterval(112)), true, "unplugged 12 s before the event")
        XCTAssertNil(tracker.externalPowerLostRecently(eventTime: t0.addingTimeInterval(200)), "long after: on battery, so nil")
    }
}
