import XCTest
@testable import NeighborhoodWatchdog

// There is no sign-in (decision 0014). These tests cover enrolment with an invitation link, the
// single device secret and the API client. (The file keeps its old name; the class says what it tests.)

private func body(_ request: URLRequest) -> [String: Any] {
    (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: Any] ?? [:]
}

private let householdJSON = #"{"household_id":"11111111-2222-3333-4444-555555555555","neighborhood_id":"21111111-2222-3333-4444-555555555555","anonymous_reference":"Household 7","display_label":null,"house_number":null,"street_name":"Elm Street","isp":"unknown","membership_status":"active","device_count":1}"#
private let deviceJSON = #"{"device_id":"31111111-2222-3333-4444-555555555555","platform":"ios","os_version":"17.4","app_version":"1.0.0 (1)","status":"active","monitoring_enabled":true,"last_seen_at":null,"created_at":"2026-11-01T10:00:00Z"}"#

private func enrolled(_ secret: String) -> FakeResponse {
    .json(201, #"{"device_token":"\#(secret)","household":\#(householdJSON),"device":\#(deviceJSON)}"#)
}

private let unauthorized = FakeResponse.json(401, #"{"error":{"code":"unauthorized","message":"This phone is not enrolled. Use an invitation link to join.","request_id":"r"}}"#)

final class EnrolmentAndAPITests: XCTestCase {
    private let apiURL = URL(string: "https://api.example.org")!
    private let deviceID = UUID(uuidString: "31111111-2222-3333-4444-555555555555")!
    private let invitation = "AbCdEfGhIjKlMnOpQrStUvWxYz_0123-456"

    private func enrollRequest(houseNumber: String? = nil) -> EnrollRequest {
        EnrollRequest(invitationToken: invitation, streetName: "Elm Street", houseNumber: houseNumber, privacyAcknowledged: true,
                      deviceID: deviceID, platform: .ios, osVersion: "17.4", appVersion: "1.0.0 (1)", capabilities: .iOS)
    }

    func testEnrolmentSendsOnlyWhatIsAskedAndStoresTheSecret() async throws {
        let store = MemoryDeviceTokenStore()
        let transport = FakeTransport { _ in enrolled("secret-1") }
        let api = APIClient(baseURL: apiURL, transport: transport, credentials: store)
        XCTAssertFalse(api.isEnrolled)

        let response = try await api.enroll(enrollRequest())

        XCTAssertEqual(response.deviceToken, "secret-1")
        XCTAssertEqual(response.device.deviceID, deviceID)
        XCTAssertNil(response.household.houseNumber)
        XCTAssertEqual(store.token.value, "secret-1", "the secret is stored as soon as the server issues it")
        XCTAssertTrue(api.isEnrolled)

        let request = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(request.url?.absoluteString, "https://api.example.org/api/v1/enroll")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"), "enrolment is public: there is no credential yet")
        XCTAssertNil(request.value(forHTTPHeaderField: "apikey"))
        let sent = body(request)
        XCTAssertEqual(Set(sent.keys), ["invitation_token", "street_name", "privacy_acknowledged", "device_id", "platform",
                                        "os_version", "app_version", "capabilities"],
                       "no house number given: the field is left out; nothing else is sent")
        XCTAssertEqual(sent["invitation_token"] as? String, invitation)
        XCTAssertEqual(sent["street_name"] as? String, "Elm Street")
        XCTAssertEqual(sent["privacy_acknowledged"] as? Bool, true)

        _ = try await api.enroll(enrollRequest(houseNumber: "12"))
        XCTAssertEqual(body(transport.requests[1])["house_number"] as? String, "12")
    }

    func testEnrollingAgainReplacesTheSecret() async throws {
        let store = MemoryDeviceTokenStore("secret-1")
        let api = APIClient(baseURL: apiURL, transport: FakeTransport { _ in enrolled("secret-2") }, credentials: store)
        _ = try await api.enroll(enrollRequest())
        XCTAssertEqual(store.token.value, "secret-2")
    }

    func testEnrolmentErrorsAreReportedAndNothingIsStored() async {
        let store = MemoryDeviceTokenStore()
        let transport = FakeTransport { _ in
            .json(409, #"{"error":{"code":"household_already_registered","message":"This home is already taking part. Ask your organizer for its link."}}"#)
        }
        let api = APIClient(baseURL: apiURL, transport: transport, credentials: store)
        do {
            _ = try await api.enroll(enrollRequest(houseNumber: "12"))
            XCTFail("expected an error")
        } catch {
            let apiError = APIError.from(error)
            XCTAssertEqual(apiError.code, "household_already_registered")
            XCTAssertEqual(apiError.userMessage, "This home is already taking part. Ask your organizer for its link.")
        }
        XCTAssertNil(store.token.value)

        for (status, code) in [(404, "invitation_invalid"), (409, "device_conflict"), (422, "validation_error"), (429, "rate_limited")] {
            transport.setHandler { _ in .json(status, #"{"error":{"code":"\#(code)","message":""}}"#) }
            do {
                _ = try await api.enroll(enrollRequest())
                XCTFail("expected \(code)")
            } catch {
                XCTAssertEqual(APIError.from(error).code, code)
                XCTAssertFalse(APIError.from(error).userMessage.isEmpty)
            }
        }
        XCTAssertNil(store.token.value)
    }

    func testSecretThatCannotBeStoredIsAnError() async {
        let store = MemoryDeviceTokenStore()
        store.failSaving.value = true
        let api = APIClient(baseURL: apiURL, transport: FakeTransport { _ in enrolled("secret-1") }, credentials: store)
        do {
            _ = try await api.enroll(enrollRequest())
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .credentialStorage, "enrolling again with the same link is safe, so the resident retries")
        }
        XCTAssertFalse(api.isEnrolled)
    }

    func testEveryAuthenticatedCallCarriesTheDeviceSecret() async throws {
        let transport = FakeTransport { _ in .json(200, householdJSON) }
        let api = APIClient(baseURL: apiURL, transport: transport, credentials: MemoryDeviceTokenStore("secret-1"))
        _ = try await api.myHousehold()
        _ = try await api.myHousehold()
        XCTAssertEqual(transport.requests.map { $0.value(forHTTPHeaderField: "Authorization") }, ["Bearer secret-1", "Bearer secret-1"])
        XCTAssertEqual(transport.requests.count, 2, "the secret never expires: there is no refresh call")
    }

    func testUnauthorizedMeansNotEnrolledAndRemovesTheSecret() async {
        let store = MemoryDeviceTokenStore("secret-1")
        let notified = Locked(0)
        let transport = FakeTransport { _ in unauthorized }
        let api = APIClient(baseURL: apiURL, transport: transport, credentials: store,
                            onUnenrolled: { [notified] in notified.mutate { $0 += 1 } })
        do {
            _ = try await api.myHousehold()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .notEnrolled)
        }
        XCTAssertNil(store.token.value, "the refused secret is removed")
        XCTAssertEqual(transport.requests.count, 1, "no retry and no refresh")
        XCTAssertEqual(notified.value, 1, "the app is told once, so it can show the join screen")

        // Without a secret nothing is sent at all.
        do {
            _ = try await api.connectivityStatus()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .notEnrolled)
        }
        XCTAssertEqual(transport.requests.count, 1)
        XCTAssertEqual(notified.value, 1)
    }

    func testLateUnauthorizedForAnOldSecretDoesNotRemoveANewOne() async {
        let store = MemoryDeviceTokenStore("old-secret")
        let notified = Locked(0)
        let transport = FakeTransport { [store] _ in
            // The phone is enrolled again while the request with the old secret is on its way.
            store.token.value = "new-secret"
            return unauthorized
        }
        let api = APIClient(baseURL: apiURL, transport: transport, credentials: store,
                            onUnenrolled: { [notified] in notified.mutate { $0 += 1 } })
        do {
            _ = try await api.myHousehold()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .notEnrolled)
        }
        XCTAssertEqual(store.token.value, "new-secret")
        XCTAssertEqual(notified.value, 0)
    }

    func testOtherErrorsKeepTheSecret() async {
        let store = MemoryDeviceTokenStore("secret-1")
        let transport = FakeTransport { _ in
            .json(403, #"{"error":{"code":"household_suspended","message":"This household's membership is suspended."}}"#)
        }
        let api = APIClient(baseURL: apiURL, transport: transport, credentials: store)
        do {
            _ = try await api.connectivityStatus()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual((error as? APIError)?.code, "household_suspended")
        }
        // A 401 that is not the API's own answer (for example from a proxy) is not proof either.
        transport.setHandler { _ in FakeResponse(status: 401, body: Data("<html>".utf8)) }
        do {
            _ = try await api.connectivityStatus()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual((error as? APIError)?.status, 401)
        }
        transport.setHandler { _ in throw URLError(.notConnectedToInternet) }
        do {
            _ = try await api.connectivityStatus()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .transport(.notConnectedToInternet))
        }
        XCTAssertEqual(store.token.value, "secret-1")
    }

    func testLeavingRemovesTheSecret() async throws {
        let store = MemoryDeviceTokenStore("secret-1")
        let transport = FakeTransport { _ in .json(200, #"{"ok":true}"#) }
        let api = APIClient(baseURL: apiURL, transport: transport, credentials: store)
        let result = try await api.leaveHousehold()
        XCTAssertTrue(result.ok)
        XCTAssertEqual(transport.requests[0].url?.absoluteString, "https://api.example.org/api/v1/households/leave")
        XCTAssertEqual(transport.requests[0].httpMethod, "POST")
        XCTAssertEqual(transport.requests[0].value(forHTTPHeaderField: "Authorization"), "Bearer secret-1")
        XCTAssertNil(store.token.value, "the server deleted this phone; its secret is worthless")
    }

    func testEndpointsPathsAndBodies() async throws {
        let transport = FakeTransport { request in
            switch request.url!.path {
            case "/api/v1/incidents": return .json(200, #"{"incidents":[]}"#)
            case "/api/v1/devices": return .json(200, "[]")
            case "/api/v1/devices/register": return .json(200, deviceJSON)
            case "/api/v1/monitoring/health": return .json(200, #"{"status":"ok","api":"ok","database":"ok","version":"1","server_time":"2026-11-21T03:54:07Z"}"#)
            case "/api/v1/auth/verify-invitation":
                return .json(200, #"{"valid":true,"neighborhood_name":"Maple Grove","expires_at":"2026-11-24T03:54:07+00:00"}"#)
            case "/api/v1/connectivity/events":
                return .json(200, #"{"server_time":"2026-11-21T03:54:07Z","outcomes":[],"retry_after_seconds":null}"#)
            default: return .json(200, #"{"ok":true}"#)
            }
        }
        let api = APIClient(baseURL: apiURL, transport: transport, credentials: MemoryDeviceTokenStore("secret-1"))

        _ = try await api.incidents(openOnly: true, limit: 25)
        _ = try await api.incidents(openOnly: false)
        _ = try await api.devices()
        _ = try await api.health()
        let verified = try await api.verifyInvitation(token: invitation)
        _ = try await api.revokeDevice(id: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!)
        _ = try await api.submitEvents(SubmitEventsRequest(deviceID: deviceID, reportedVia: .wifi, sentAt: Date(timeIntervalSince1970: 1_800_000_000),
                                                           events: [makeEvent()]))
        _ = try await api.registerDevice(RegisterDeviceRequest(deviceID: deviceID, platform: .ios, osVersion: "17.5",
                                                               appVersion: "1.0.1 (2)", capabilities: .iOS))

        let requests = transport.requests
        XCTAssertEqual(requests[0].url?.absoluteString, "https://api.example.org/api/v1/incidents?status=open&limit=25")
        XCTAssertEqual(requests[1].url?.query, "status=all&limit=50")
        XCTAssertEqual(requests[2].httpMethod, "GET")
        XCTAssertNil(requests[3].value(forHTTPHeaderField: "Authorization"), "the health endpoint needs no credential")
        XCTAssertEqual(verified.neighborhoodName, "Maple Grove")
        XCTAssertNil(requests[4].value(forHTTPHeaderField: "Authorization"), "an invitation is checked before the phone is enrolled")
        XCTAssertEqual(Set(body(requests[4]).keys), ["invitation_token"])
        XCTAssertEqual(Set(body(requests[5]).keys), ["device_id"])
        XCTAssertEqual(requests[6].url?.path, "/api/v1/connectivity/events")
        XCTAssertEqual(requests[6].value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(Set(body(requests[6]).keys), ["device_id", "reported_via", "sent_at", "events"])
        XCTAssertEqual(requests[6].value(forHTTPHeaderField: "Authorization"), "Bearer secret-1")
        XCTAssertEqual(requests[7].url?.path, "/api/v1/devices/register")
        XCTAssertEqual((body(requests[7])["device_id"] as? String)?.lowercased(), deviceID.uuidString.lowercased(),
                       "a phone only ever updates its own record")
        XCTAssertFalse(requests.contains { $0.url!.path.contains("/auth/v1") || $0.url!.path.hasSuffix("/account") },
                       "no sign-in, refresh or account endpoint is called")
    }

    func testInvitationLinkFromTheJoinPage() throws {
        XCTAssertEqual(InvitationLink.token(from: try XCTUnwrap(URL(string: "feluda://join#t=\(invitation)"))), invitation)
        XCTAssertEqual(InvitationLink.token(from: try XCTUnwrap(URL(string: "FELUDA://join#t=\(invitation)"))), invitation)
        for other in ["feluda://join", "feluda://join#t=short", "feluda://other#t=\(invitation)", "https://example.org/join#t=\(invitation)",
                      "feluda://join?t=\(invitation)"] {
            XCTAssertNil(InvitationLink.token(from: try XCTUnwrap(URL(string: other))), other)
        }
        // Pasted into the field on the join screen, the https link and the bare token both work.
        XCTAssertEqual(InvitationToken.parse("https://watchdog.example.org/join#t=\(invitation)"), invitation)
        XCTAssertEqual(InvitationToken.parse(invitation), invitation)
    }
}
