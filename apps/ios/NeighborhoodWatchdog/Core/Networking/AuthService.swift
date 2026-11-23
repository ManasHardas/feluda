import Foundation

struct AuthTokens: Codable, Sendable, Equatable {
    var accessToken: String
    var refreshToken: String
    var expiresAt: Date
    var userID: String?
    var email: String?
}

/// Where the session lives. The app uses the Keychain; tests use memory.
protocol TokenStore: Sendable {
    func load() -> AuthTokens?
    func save(_ tokens: AuthTokens) throws
    func clear()
}

extension APIError {
    /// Builds an error from a non-2xx response. Understands the API error envelope
    /// `{"error": {"code", "message", "request_id"}}` and the shapes the auth service uses.
    static func fromResponse(data: Data, response: HTTPURLResponse) -> APIError {
        var code = "http_\(response.statusCode)"
        var message = ""
        var requestID: String?
        if let envelope = try? WatchdogJSON.decoder().decode(APIErrorEnvelope.self, from: data) {
            code = envelope.error.code
            message = envelope.error.message
            requestID = envelope.error.requestID
        } else if let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            if let value = object["error_code"] as? String { code = value } else if let value = object["error"] as? String { code = value }
            if let value = (object["msg"] ?? object["error_description"] ?? object["message"]) as? String { message = value }
        }
        var retryAfter: TimeInterval?
        if let header = response.value(forHTTPHeaderField: "Retry-After"), let seconds = TimeInterval(header.trimmingCharacters(in: .whitespaces)), seconds >= 0 {
            retryAfter = seconds
        }
        if response.statusCode == 429, code.hasPrefix("http_") { code = "rate_limited" }
        return .server(status: response.statusCode, code: code, message: message, requestID: requestID, retryAfter: retryAfter)
    }
}

/// Email one-time-code sign-in against the GoTrue-style auth service, and the token lifecycle.
///
/// This is an actor so that refreshes are serialised: refresh tokens rotate, so two refresh calls
/// running at once would invalidate one another. Every caller that needs a refresh while one is
/// in flight waits for that same call.
actor AuthService {
    /// Refresh when the access token has less than this many seconds left.
    static let refreshLeeway: TimeInterval = 60

    private let baseURL: URL
    private let publicKey: String
    private let transport: HTTPTransport
    private let store: TokenStore
    private let now: @Sendable () -> Date

    private var tokens: AuthTokens?
    private var loaded = false
    private var refreshTask: Task<AuthTokens, Error>?

    init(baseURL: URL, publicKey: String, transport: HTTPTransport, store: TokenStore,
         now: @escaping @Sendable () -> Date = { Date() }) {
        self.baseURL = baseURL
        self.publicKey = publicKey
        self.transport = transport
        self.store = store
        self.now = now
    }

    private func current() -> AuthTokens? {
        if !loaded {
            tokens = store.load()
            loaded = true
        }
        return tokens
    }

    var isSignedIn: Bool { current() != nil }
    var signedInEmail: String? { current()?.email }

    // MARK: Sign-in

    /// Step 1: ask the auth service to email a one-time code.
    func requestCode(email: String) async throws {
        _ = try await post(path: "otp", query: nil, body: ["email": email, "create_user": true], bearer: nil)
    }

    /// Step 2: exchange the emailed code for a session and store it.
    func verify(email: String, code: String) async throws {
        let data = try await post(path: "verify", query: nil, body: ["type": "email", "email": email, "token": code], bearer: nil)
        try adopt(sessionData: data, fallbackEmail: email)
    }

    // MARK: Tokens

    /// An access token with at least `refreshLeeway` seconds left, refreshing first when needed.
    func validAccessToken() async throws -> String {
        guard let tokens = current() else { throw APIError.notSignedIn }
        if tokens.expiresAt.timeIntervalSince(now()) >= Self.refreshLeeway {
            return tokens.accessToken
        }
        return try await refresh().accessToken
    }

    /// Called once after the API answered 401 for `rejectedToken`. If another caller already
    /// replaced that token, the newer one is returned without a second refresh.
    func accessTokenAfterUnauthorized(rejectedToken: String) async throws -> String {
        guard let tokens = current() else { throw APIError.notSignedIn }
        if tokens.accessToken != rejectedToken, refreshTask == nil {
            return tokens.accessToken
        }
        return try await refresh().accessToken
    }

    private func refresh() async throws -> AuthTokens {
        if let refreshTask {
            return try await refreshTask.value
        }
        guard let tokens = current() else { throw APIError.notSignedIn }
        let refreshToken = tokens.refreshToken
        let task = Task { try await self.performRefresh(refreshToken: refreshToken) }
        refreshTask = task
        defer { refreshTask = nil }
        return try await task.value
    }

    private func performRefresh(refreshToken: String) async throws -> AuthTokens {
        do {
            let data = try await post(path: "token", query: [URLQueryItem(name: "grant_type", value: "refresh_token")],
                                      body: ["refresh_token": refreshToken], bearer: nil)
            try adopt(sessionData: data, fallbackEmail: tokens?.email)
            guard let tokens else { throw APIError.sessionExpired }
            return tokens
        } catch let error as APIError {
            // The auth service refused the refresh token: the session is over. A network failure
            // or a 5xx keeps the session so a later attempt can succeed.
            if let status = error.status, [400, 401, 403].contains(status) {
                clearLocal()
                throw APIError.sessionExpired
            }
            throw error
        }
    }

    private func adopt(sessionData: Data, fallbackEmail: String?) throws {
        let session: AuthSessionResponse
        do {
            session = try WatchdogJSON.decoder().decode(AuthSessionResponse.self, from: sessionData)
        } catch {
            throw APIError.decoding("auth session: \(error)")
        }
        let newTokens = AuthTokens(
            accessToken: session.accessToken,
            refreshToken: session.refreshToken,
            expiresAt: now().addingTimeInterval(session.expiresIn),
            userID: session.user?.id ?? tokens?.userID,
            email: session.user?.email ?? fallbackEmail
        )
        // The rotated refresh token replaces the old one in memory first. If the Keychain write
        // fails the session still works until the process ends; the next launch asks to sign in.
        tokens = newTokens
        loaded = true
        try? store.save(newTokens)
    }

    // MARK: Sign-out

    /// Tells the auth service to end the session (best effort) and removes the local tokens.
    func signOut() async {
        if let tokens = current() {
            _ = try? await post(path: "logout", query: nil, body: [:], bearer: tokens.accessToken)
        }
        clearLocal()
    }

    func clearLocal() {
        tokens = nil
        loaded = true
        store.clear()
    }

    // MARK: HTTP

    private func post(path: String, query: [URLQueryItem]?, body: [String: any Sendable], bearer: String?) async throws -> Data {
        var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)
        components?.queryItems = query
        guard let url = components?.url else { throw APIError.invalidConfiguration("AUTH_BASE_URL") }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(publicKey, forHTTPHeaderField: "apikey")
        if let bearer { request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
        let data: Data, response: HTTPURLResponse
        do {
            (data, response) = try await transport.send(request)
        } catch {
            throw APIError.from(error)
        }
        guard (200..<300).contains(response.statusCode) else {
            throw APIError.fromResponse(data: data, response: response)
        }
        return data
    }
}
