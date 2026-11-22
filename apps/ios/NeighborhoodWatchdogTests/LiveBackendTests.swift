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

    func testWholeResidentJourneyAgainstTheRealBackend() async throws {
        let origin = try env("WATCHDOG_LIVE_API")  // throws XCTSkip when not configured
        let base = try XCTUnwrap(URL(string: origin))
        let invitation = try env("WATCHDOG_LIVE_INVITATION")
        let email = try env("WATCHDOG_LIVE_EMAIL")
        let code = try env("WATCHDOG_LIVE_CODE")
        let houseNumber = try env("WATCHDOG_LIVE_HOUSE_NUMBER")
        let street = try env("WATCHDOG_LIVE_STREET")
        let token = try XCTUnwrap(invitation.components(separatedBy: "#t=").last)

        let transport = URLSessionTransport(session: URLSession(configuration: .ephemeral))
        let store = MemoryTokenStore()
        let auth = AuthService(baseURL: base.appendingPathComponent("auth/v1"), publicKey: "local", transport: transport, store: store)
        let api = APIClient(baseURL: base, transport: transport, auth: auth)

        // Public endpoints
        let health = try await api.health()
        XCTAssertEqual(health.database, "ok")
        let verified = try await api.verifyInvitation(token: token)
        XCTAssertTrue(verified.householdBound)

        // Sign in with the e-mail code
        try await auth.requestCode(email: email)
        try await auth.verify(email: email, code: code)
        let signedIn = await auth.isSignedIn
        XCTAssertTrue(signedIn)

        // Not a member yet
        do {
            _ = try await api.myHousehold()
            XCTFail("expected not_a_member")
        } catch let error as APIError {
            XCTAssertEqual(error.code, "not_a_member")
        }

        // Join
        let joined = try await api.joinHousehold(JoinHouseholdRequest(
            invitationToken: token, houseNumber: houseNumber, streetName: street, displayLabel: "Live test",
            isp: .xfinity, privacyAcknowledged: true))
        XCTAssertEqual(joined.houseNumber, houseNumber)
        let household = try await api.myHousehold()
        XCTAssertEqual(household.householdID, joined.householdID)
        _ = try await api.updateHousehold(UpdateHouseholdRequest(displayLabel: "Renamed", isp: nil))

        // Device, push token, preferences
        let deviceID = UUID()
        let device = try await api.registerDevice(RegisterDeviceRequest(
            deviceID: deviceID, platform: .ios, osVersion: "18.2", appVersion: "0.1.0 (1)", capabilities: .iOS))
        XCTAssertEqual(device.deviceID, deviceID)
        _ = try await api.registerPushToken(RegisterPushTokenRequest(
            deviceID: deviceID, provider: .apns, token: String(repeating: "ab", count: 32), apnsEnvironment: .sandbox))
        let preferences = try await api.notificationPreferences()
        let saved = try await api.updateNotificationPreferences(preferences)
        XCTAssertEqual(saved, preferences)
        let devices = try await api.devices()
        XCTAssertEqual(devices.count, 1)

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

        // Token refresh with rotation against the auth endpoints
        let before = try XCTUnwrap(store.load())
        _ = try await auth.accessTokenAfterUnauthorized(rejectedToken: before.accessToken)
        let after = try XCTUnwrap(store.load())
        XCTAssertNotEqual(after.refreshToken, before.refreshToken)
        _ = try await api.myHousehold()

        // Revoke and leave
        _ = try await api.revokeDevice(id: deviceID)
        _ = try await api.leaveHousehold()
        do {
            _ = try await api.myHousehold()
            XCTFail("expected not_a_member after leaving")
        } catch let error as APIError {
            XCTAssertEqual(error.code, "not_a_member")
        }
        await auth.signOut()
    }
}
