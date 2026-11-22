import XCTest
@testable import NeighborhoodWatchdog

private func session(_ access: String, _ refresh: String, expiresIn: Int = 3600) -> FakeResponse {
    .json(200, #"{"access_token":"\#(access)","refresh_token":"\#(refresh)","expires_in":\#(expiresIn),"token_type":"bearer","user":{"id":"user-1","email":"resident@example.org"}}"#)
}

private func body(_ request: URLRequest) -> [String: Any] {
    (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: Any] ?? [:]
}

final class AuthAndAPITests: XCTestCase {
    private let authURL = URL(string: "https://auth.example.org/auth/v1")!
    private let apiURL = URL(string: "https://api.example.org")!

    private func tokens(_ clock: TestClock, access: String = "access-1", refresh: String = "refresh-1", expiresIn: TimeInterval) -> AuthTokens {
        AuthTokens(accessToken: access, refreshToken: refresh, expiresAt: clock.now.addingTimeInterval(expiresIn),
                   userID: "user-1", email: "resident@example.org")
    }

    func testEmailCodeSignIn() async throws {
        let clock = TestClock()
        let store = MemoryTokenStore()
        let transport = FakeTransport { request in
            request.url!.path.hasSuffix("/otp") ? .json(200, "{}") : session("access-1", "refresh-1")
        }
        let auth = AuthService(baseURL: authURL, publicKey: "public-key", transport: transport, store: store, now: clock.provider)

        try await auth.requestCode(email: "resident@example.org")
        try await auth.verify(email: "resident@example.org", code: "123456")

        let requests = transport.requests
        XCTAssertEqual(requests.map { $0.url!.absoluteString },
                       ["https://auth.example.org/auth/v1/otp", "https://auth.example.org/auth/v1/verify"])
        XCTAssertTrue(requests.allSatisfy { $0.httpMethod == "POST" && $0.value(forHTTPHeaderField: "apikey") == "public-key" })
        XCTAssertEqual(body(requests[0])["email"] as? String, "resident@example.org")
        XCTAssertEqual(body(requests[0])["create_user"] as? Bool, true)
        XCTAssertEqual(body(requests[1])["type"] as? String, "email")
        XCTAssertEqual(body(requests[1])["token"] as? String, "123456")
        XCTAssertEqual(store.tokens.value, tokens(clock, expiresIn: 3600))
        let signedIn = await auth.isSignedIn
        XCTAssertTrue(signedIn)
    }

    func testTokenIsRefreshedOnlyWhenLessThanSixtySecondsRemain() async throws {
        let clock = TestClock()
        let store = MemoryTokenStore(tokens(clock, expiresIn: 600))
        let transport = FakeTransport { _ in session("access-2", "refresh-2") }
        let auth = AuthService(baseURL: authURL, publicKey: "k", transport: transport, store: store, now: clock.provider)

        var token = try await auth.validAccessToken()
        XCTAssertEqual(token, "access-1")
        XCTAssertTrue(transport.requests.isEmpty)

        clock.advance(545) // 55 seconds left
        token = try await auth.validAccessToken()
        XCTAssertEqual(token, "access-2")
        XCTAssertEqual(transport.requests.count, 1)
        XCTAssertEqual(transport.requests[0].url?.absoluteString, "https://auth.example.org/auth/v1/token?grant_type=refresh_token")
        XCTAssertEqual(body(transport.requests[0])["refresh_token"] as? String, "refresh-1")
        XCTAssertEqual(store.tokens.value?.refreshToken, "refresh-2", "the rotated refresh token replaces the old one")
    }

    func testConcurrentCallersShareOneRefresh() async throws {
        let clock = TestClock()
        let store = MemoryTokenStore(tokens(clock, expiresIn: 10))
        let refreshCalls = Locked(0)
        let transport = FakeTransport { request in
            let call = refreshCalls.mutate { $0 += 1; return $0 }
            // Keep the refresh in flight long enough for every caller to arrive.
            try await Task.sleep(nanoseconds: 150_000_000)
            // A rotated refresh token is single-use: a second refresh with the old one would fail.
            guard body(request)["refresh_token"] as? String == "refresh-1", call == 1 else {
                return .json(400, #"{"error":"invalid_grant","error_description":"Invalid Refresh Token: Already Used"}"#)
            }
            return session("access-2", "refresh-2")
        }
        let auth = AuthService(baseURL: authURL, publicKey: "k", transport: transport, store: store, now: clock.provider)

        let results = await withTaskGroup(of: String?.self) { group -> [String?] in
            for _ in 0..<12 { group.addTask { try? await auth.validAccessToken() } }
            var collected: [String?] = []
            for await value in group { collected.append(value) }
            return collected
        }
        XCTAssertEqual(results.count, 12)
        XCTAssertTrue(results.allSatisfy { $0 == "access-2" })
        XCTAssertEqual(refreshCalls.value, 1, "refreshes are serialised: exactly one call to the auth service")
        XCTAssertEqual(store.tokens.value?.refreshToken, "refresh-2")
    }

    func testRefusedRefreshEndsTheSessionButNetworkFailureDoesNot() async {
        let clock = TestClock()
        let store = MemoryTokenStore(tokens(clock, expiresIn: 10))
        let transport = FakeTransport { _ in throw URLError(.notConnectedToInternet) }
        let auth = AuthService(baseURL: authURL, publicKey: "k", transport: transport, store: store, now: clock.provider)

        do {
            _ = try await auth.validAccessToken()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .transport(.notConnectedToInternet))
        }
        XCTAssertNotNil(store.tokens.value, "offline: the session is kept")

        transport.setHandler { _ in .json(400, #"{"error":"invalid_grant"}"#) }
        do {
            _ = try await auth.validAccessToken()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .sessionExpired)
        }
        XCTAssertNil(store.tokens.value, "refused refresh token: tokens are removed")
        let signedIn = await auth.isSignedIn
        XCTAssertFalse(signedIn)
    }

    func testUnauthorizedResponseRefreshesOnceAndRetriesOnce() async throws {
        let clock = TestClock()
        let store = MemoryTokenStore(tokens(clock, expiresIn: 3_000))
        let authTransport = FakeTransport { _ in session("access-2", "refresh-2") }
        let auth = AuthService(baseURL: authURL, publicKey: "k", transport: authTransport, store: store, now: clock.provider)
        let apiTransport = FakeTransport { request in
            request.value(forHTTPHeaderField: "Authorization") == "Bearer access-2"
                ? .json(200, #"{"ok":true}"#)
                : .json(401, #"{"error":{"code":"unauthorized","message":"expired","request_id":"r"}}"#)
        }
        let api = APIClient(baseURL: apiURL, transport: apiTransport, auth: auth)

        let result = try await api.leaveHousehold()
        XCTAssertTrue(result.ok)
        XCTAssertEqual(apiTransport.requests.map { $0.value(forHTTPHeaderField: "Authorization") }, ["Bearer access-1", "Bearer access-2"])
        XCTAssertEqual(authTransport.requests.count, 1)
        XCTAssertEqual(apiTransport.requests[0].url?.absoluteString, "https://api.example.org/api/v1/households/leave")
        XCTAssertEqual(apiTransport.requests[0].httpMethod, "POST")
    }

    func testSecondUnauthorizedIsNotRetriedAgain() async {
        let clock = TestClock()
        let store = MemoryTokenStore(tokens(clock, expiresIn: 3_000))
        let authTransport = FakeTransport { _ in session("access-2", "refresh-2") }
        let auth = AuthService(baseURL: authURL, publicKey: "k", transport: authTransport, store: store, now: clock.provider)
        let apiTransport = FakeTransport { _ in .json(401, #"{"error":{"code":"unauthorized","message":"no"}}"#) }
        let api = APIClient(baseURL: apiURL, transport: apiTransport, auth: auth)
        do {
            _ = try await api.myHousehold()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual((error as? APIError)?.status, 401)
        }
        XCTAssertEqual(apiTransport.requests.count, 2, "one retry, not a loop")
        XCTAssertEqual(authTransport.requests.count, 1)
    }

    func testEndpointsPathsAndBodies() async throws {
        let clock = TestClock()
        let auth = AuthService(baseURL: authURL, publicKey: "k", transport: FakeTransport(), store: MemoryTokenStore(tokens(clock, expiresIn: 3_000)),
                               now: clock.provider)
        let transport = FakeTransport { request in
            switch request.url!.path {
            case "/api/v1/incidents": return .json(200, #"{"incidents":[]}"#)
            case "/api/v1/devices": return .json(200, "[]")
            case "/api/v1/monitoring/health": return .json(200, #"{"status":"ok","api":"ok","database":"ok","version":"1","server_time":"2026-11-21T03:54:07Z"}"#)
            case "/api/v1/connectivity/events":
                return .json(200, #"{"server_time":"2026-11-21T03:54:07Z","outcomes":[],"retry_after_seconds":null}"#)
            default: return .json(200, #"{"ok":true}"#)
            }
        }
        let api = APIClient(baseURL: apiURL, transport: transport, auth: auth)

        _ = try await api.incidents(openOnly: true, limit: 25)
        _ = try await api.incidents(openOnly: false)
        _ = try await api.devices()
        _ = try await api.health()
        _ = try await api.deleteAccount()
        _ = try await api.revokeDevice(id: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!)
        _ = try await api.submitEvents(SubmitEventsRequest(deviceID: UUID(), reportedVia: .wifi, sentAt: clock.now, events: [makeEvent()]))

        let requests = transport.requests
        XCTAssertEqual(requests[0].url?.absoluteString, "https://api.example.org/api/v1/incidents?status=open&limit=25")
        XCTAssertEqual(requests[1].url?.query, "status=all&limit=50")
        XCTAssertEqual(requests[2].httpMethod, "GET")
        XCTAssertNil(requests[3].value(forHTTPHeaderField: "Authorization"), "the health endpoint needs no token")
        XCTAssertEqual(requests[4].httpMethod, "DELETE")
        XCTAssertEqual(requests[4].url?.path, "/api/v1/account")
        XCTAssertEqual(Set(body(requests[5]).keys), ["device_id"])
        XCTAssertEqual(requests[6].url?.path, "/api/v1/connectivity/events")
        XCTAssertEqual(requests[6].value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(Set(body(requests[6]).keys), ["device_id", "reported_via", "sent_at", "events"])
        XCTAssertEqual(requests[6].value(forHTTPHeaderField: "Authorization"), "Bearer access-1")
    }

    func testNotSignedIn() async {
        let auth = AuthService(baseURL: authURL, publicKey: "k", transport: FakeTransport(), store: MemoryTokenStore())
        let api = APIClient(baseURL: apiURL, transport: FakeTransport(), auth: auth)
        do {
            _ = try await api.myHousehold()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? APIError, .notSignedIn)
        }
    }

    func testSignOutCallsLogoutAndClearsTokens() async {
        let clock = TestClock()
        let store = MemoryTokenStore(tokens(clock, expiresIn: 3_000))
        let transport = FakeTransport { _ in FakeResponse(status: 204) }
        let auth = AuthService(baseURL: authURL, publicKey: "k", transport: transport, store: store, now: clock.provider)
        await auth.signOut()
        XCTAssertEqual(transport.requests.first?.url?.path, "/auth/v1/logout")
        XCTAssertEqual(transport.requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer access-1")
        XCTAssertNil(store.tokens.value)
    }
}
