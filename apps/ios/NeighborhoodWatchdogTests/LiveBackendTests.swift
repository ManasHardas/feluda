import XCTest
@testable import NeighborhoodWatchdog

/// Runs the app's real networking code against a locally running development backend.
///
/// Skipped unless the environment has WATCHDOG_LIVE_API (see `make live-ios` in the repository
/// root, which starts nothing itself: it expects `make live-backend` in another terminal).
/// This is the only test that touches a network, and only localhost.
final class LiveBackendTests: XCTestCase {
    private func env(_ name: String) throws -> String {
        guard let value = ProcessInfo.processInfo.environment[name], !value.isEmpty else {
            throw XCTSkip("live backend test not configured (\(name) missing)")
        }
        return value
    }

    private func expectNotEnrolled(_ work: () async throws -> Void, _ message: String,
                                   file: StaticString = #filePath, line: UInt = #line) async {
        do {
            try await work()
            XCTFail("expected 401: \(message)", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? APIError, .notEnrolled, message, file: file, line: line)
        }
    }

    func testWholeResidentJourneyAgainstTheRealBackend() async throws {
        let origin = try env("WATCHDOG_LIVE_API")  // throws XCTSkip when not configured
        let base = try XCTUnwrap(URL(string: origin))
        let invitation = try env("WATCHDOG_LIVE_INVITATION")
        let houseNumber = try env("WATCHDOG_LIVE_HOUSE_NUMBER")
        let street = try env("WATCHDOG_LIVE_STREET")
        // The same parser the join screen uses for a pasted link.
        let token = try XCTUnwrap(InvitationToken.parse(invitation))

        let transport = URLSessionTransport(session: URLSession(configuration: .ephemeral))
        let store = MemoryDeviceTokenStore()
        let api = APIClient(baseURL: base, transport: transport, credentials: store)

        // Public endpoints
        let health = try await api.health()
        XCTAssertEqual(health.database, "ok")
        let verified = try await api.verifyInvitation(token: token)
        XCTAssertTrue(verified.valid)
        XCTAssertFalse(verified.neighborhoodName.isEmpty)

        // Not enrolled yet: nothing is sent without a secret, and a made-up secret is refused.
        await expectNotEnrolled({ _ = try await api.myHousehold() }, "no secret stored")
        let stranger = MemoryDeviceTokenStore("not-a-real-device-secret-0123456789")
        await expectNotEnrolled({ _ = try await APIClient(baseURL: base, transport: transport, credentials: stranger).myHousehold() },
                                "unknown secret")
        XCTAssertNil(stranger.token.value, "a refused secret is removed")

        // A phone that gives only the street (the house number is optional). The link allows 2 phones.
        let streetOnlyStore = MemoryDeviceTokenStore()
        let streetOnly = APIClient(baseURL: base, transport: transport, credentials: streetOnlyStore)
        let streetOnlyEnrolment = try await streetOnly.enroll(EnrollRequest(
            invitationToken: token, streetName: street, houseNumber: nil, privacyAcknowledged: true,
            deviceID: UUID(), platform: .ios, osVersion: "18.2", appVersion: "0.1.0 (1)", capabilities: .iOS))
        XCTAssertNil(streetOnlyEnrolment.household.houseNumber)
        XCTAssertFalse(streetOnlyEnrolment.household.streetName.isEmpty)
        let streetOnlyHousehold = try await streetOnly.myHousehold()
        XCTAssertEqual(streetOnlyHousehold.householdID, streetOnlyEnrolment.household.householdID)
        XCTAssertEqual(streetOnlyHousehold.deviceCount, 1)

        // A second phone with the same link joins the same household, whatever it types:
        // one link is one home (it cannot create a second household).
        let deviceID = UUID()
        let enrolRequest = EnrollRequest(
            invitationToken: token, streetName: street, houseNumber: houseNumber, privacyAcknowledged: true,
            deviceID: deviceID, platform: .ios, osVersion: "18.2", appVersion: "0.1.0 (1)", capabilities: .iOS)
        let joined = try await api.enroll(enrolRequest)
        XCTAssertNil(joined.household.houseNumber, "the household keeps what the first phone gave")
        XCTAssertEqual(joined.device.deviceID, deviceID)
        XCTAssertEqual(joined.device.status, .active)
        XCTAssertEqual(joined.household.householdID, streetOnlyEnrolment.household.householdID)
        XCTAssertEqual(joined.household.deviceCount, 2)
        XCTAssertEqual(store.token.value, joined.deviceToken)

        // Enrolling again with the same device id and link is safe (a lost answer): a fresh secret,
        // the link is not used a third time, and the earlier secret stops working.
        let reissued = try await api.enroll(enrolRequest)
        XCTAssertNotEqual(reissued.deviceToken, joined.deviceToken)
        XCTAssertEqual(reissued.household.householdID, joined.household.householdID)
        XCTAssertEqual(store.token.value, reissued.deviceToken)
        let stale = MemoryDeviceTokenStore(joined.deviceToken)
        await expectNotEnrolled({ _ = try await APIClient(baseURL: base, transport: transport, credentials: stale).myHousehold() },
                                "the replaced secret")

        // Both uses of the link are spent: a third phone is turned away.
        do {
            _ = try await APIClient(baseURL: base, transport: transport, credentials: MemoryDeviceTokenStore()).enroll(EnrollRequest(
                invitationToken: token, streetName: street, houseNumber: nil, privacyAcknowledged: true,
                deviceID: UUID(), platform: .ios, osVersion: "18.2", appVersion: "0.1.0 (1)", capabilities: .iOS))
            XCTFail("expected invitation_invalid")
        } catch {
            XCTAssertEqual(APIError.from(error).code, "invitation_invalid")
        }

        // Authenticated calls with the device secret
        let household = try await api.myHousehold()
        XCTAssertEqual(household.householdID, joined.household.householdID)
        XCTAssertEqual(household.deviceCount, 2)
        let renamed = try await api.updateHousehold(UpdateHouseholdRequest(displayLabel: "Renamed", isp: .xfinity))
        XCTAssertEqual(renamed.displayLabel, "Renamed")
        XCTAssertEqual(renamed.isp, .xfinity)

        // Device update (its own record only), push token, preferences
        let device = try await api.registerDevice(RegisterDeviceRequest(
            deviceID: deviceID, platform: .ios, osVersion: "18.3", appVersion: "0.1.1 (2)", capabilities: .iOS))
        XCTAssertEqual(device.deviceID, deviceID)
        XCTAssertEqual(device.osVersion, "18.3")
        do {
            _ = try await api.registerDevice(RegisterDeviceRequest(
                deviceID: UUID(), platform: .ios, osVersion: "18.3", appVersion: "0.1.1 (2)", capabilities: .iOS))
            XCTFail("a phone cannot register another device id")
        } catch {
            XCTAssertEqual(APIError.from(error).code, "device_conflict")
        }
        _ = try await api.registerPushToken(RegisterPushTokenRequest(
            deviceID: deviceID, provider: .apns, token: String(repeating: "ab", count: 32), apnsEnvironment: .sandbox))
        let preferences = try await api.notificationPreferences()
        let saved = try await api.updateNotificationPreferences(preferences)
        XCTAssertEqual(saved, preferences)
        let devices = try await api.devices()
        XCTAssertEqual(Set(devices.map(\.deviceID)), [deviceID, streetOnlyEnrolment.device.deviceID])

        // Events: every diagnostics field the app can fill, plus a gap event with dates
        let now = Date()
        var full = Diagnostics()
        full.wifiAssociated = false
        full.wifiValidated = false
        full.homeNetworkMatch = true
        full.internetReachable = true
        full.cellularAvailable = true
        full.defaultTransport = .cellular
        full.networkChangedRecently = true
        full.externalPowerLostRecently = false
        full.batterySaver = false
        full.userInitiated = false
        var gap = Diagnostics()
        gap.gapStartedAt = now.addingTimeInterval(-600)
        gap.gapEndedAt = now.addingTimeInterval(-30)
        gap.gapReason = .appSuspended
        func event(_ type: EventType, _ state: ConnectivityState, _ sequence: Int, _ offset: TimeInterval,
                   _ diagnostics: Diagnostics, _ confidence: ObservationConfidence = .observed) -> ConnectivityEvent {
            ConnectivityEvent(eventID: UUID(), sequence: sequence, eventType: type, connectivityState: state,
                              occurredAt: now.addingTimeInterval(offset), osVersion: "18.2", appVersion: "0.1.0 (1)",
                              confidence: confidence, diagnostics: diagnostics)
        }
        let events = [
            event(.monitoringGapDetected, .unknown, 1, -30, gap),
            event(.monitoringResumed, .healthy, 2, -29, Diagnostics()),
            event(.heartbeat, .healthy, 3, -20, Diagnostics()),
            event(.wifiLost, .wifiDisconnected, 4, -10, full),
            event(.wifiLost, .wifiDisconnected, 5, -9, full, .reconciled),
        ]
        let request = SubmitEventsRequest(deviceID: deviceID, reportedVia: .cellular, sentAt: now, events: events)
        let response = try await api.submitEvents(request)
        XCTAssertEqual(response.outcomes.map(\.result), Array(repeating: .accepted, count: events.count))
        let again = try await api.submitEvents(request)
        XCTAssertEqual(again.outcomes.map(\.result), Array(repeating: .duplicate, count: events.count))

        // Status, neighborhood, incidents
        let status = try await api.connectivityStatus()
        XCTAssertEqual(status.devices.count, 1)
        XCTAssertFalse(status.statusExplanation.isEmpty)
        let neighborhood = try await api.myNeighborhood()
        XCTAssertGreaterThan(neighborhood.participatingHouseholds, 20)
        let incidents = try await api.incidents(openOnly: false)
        let neighborhoodIncident = try XCTUnwrap(incidents.incidents.first { $0.scope == .neighborhood },
                                                 "the fixture replays a correlated disruption")
        let detail = try await api.incident(id: neighborhoodIncident.incidentID)
        XCTAssertFalse(detail.scoreBreakdown.isEmpty)
        XCTAssertFalse(detail.explanation.isEmpty)
        XCTAssertFalse(detail.recommendedActions.isEmpty)
        for incident in incidents.incidents {
            _ = try await api.incident(id: incident.incidentID)
        }
        _ = try await api.incidents(openOnly: true)

        // Leaving deletes the phone and what it reported; its secret is then refused (401).
        let secret = try XCTUnwrap(store.token.value)
        let left = try await api.leaveHousehold()
        XCTAssertTrue(left.ok)
        XCTAssertNil(store.token.value)
        let afterLeaving = MemoryDeviceTokenStore(secret)
        let unenrolled = Locked(0)
        let leftClient = APIClient(baseURL: base, transport: transport, credentials: afterLeaving,
                                   onUnenrolled: { [unenrolled] in unenrolled.mutate { $0 += 1 } })
        await expectNotEnrolled({ _ = try await leftClient.myHousehold() }, "401 after leaving")
        XCTAssertNil(afterLeaving.token.value, "the app removes the secret and shows the join screen")
        XCTAssertEqual(unenrolled.value, 1)

        // The other phone is untouched by that, and leaves too so the run cleans up after itself.
        _ = try await streetOnly.connectivityStatus()
        _ = try await streetOnly.leaveHousehold()
        let streetOnlyAfter = MemoryDeviceTokenStore(streetOnlyEnrolment.deviceToken)
        await expectNotEnrolled({ _ = try await APIClient(baseURL: base, transport: transport, credentials: streetOnlyAfter).myHousehold() },
                                "401 after the second phone left")
    }
}
