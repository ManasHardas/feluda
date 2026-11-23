import Foundation

/// Where the single device secret lives. The app uses the Keychain; tests use memory.
protocol DeviceTokenStore: Sendable {
    func load() -> String?
    func save(_ token: String) throws
    func clear()
}

/// Typed client for /api/v1. There is no sign-in: a phone is enrolled once with an invitation link
/// and then sends its device secret as `Authorization: Bearer <secret>`. The secret never expires
/// and is never refreshed. When the server answers 401 `unauthorized` the phone is no longer
/// enrolled (removed, revoked or reinstalled): the stored secret is removed and the caller gets
/// `APIError.notEnrolled`.
struct APIClient: Sendable {
    let baseURL: URL
    let transport: HTTPTransport
    let credentials: DeviceTokenStore
    /// Called after a 401 removed the stored secret, so the app can show the join screen.
    var onUnenrolled: (@Sendable () async -> Void)?

    init(baseURL: URL, transport: HTTPTransport, credentials: DeviceTokenStore,
         onUnenrolled: (@Sendable () async -> Void)? = nil) {
        self.baseURL = baseURL
        self.transport = transport
        self.credentials = credentials
        self.onUnenrolled = onUnenrolled
    }

    private struct NoBody: Encodable, Sendable {}

    var isEnrolled: Bool { credentials.load() != nil }

    // MARK: Endpoints

    func health() async throws -> HealthOut {
        try await call("GET", "monitoring/health", authenticated: false)
    }

    func verifyInvitation(token: String) async throws -> VerifyInvitationResponse {
        try await call("POST", "auth/verify-invitation", body: VerifyInvitationRequest(invitationToken: token), authenticated: false)
    }

    /// Enrols this phone and stores the device secret. Safe to repeat with the same device id and
    /// link: the server then issues a fresh secret without using the link a second time.
    func enroll(_ request: EnrollRequest) async throws -> EnrollResponse {
        let response: EnrollResponse = try await call("POST", "enroll", body: request, authenticated: false)
        do {
            try credentials.save(response.deviceToken)
        } catch {
            throw APIError.credentialStorage
        }
        return response
    }

    func myHousehold() async throws -> HouseholdOut {
        try await call("GET", "households/me")
    }

    func updateHousehold(_ request: UpdateHouseholdRequest) async throws -> HouseholdOut {
        try await call("PATCH", "households/me", body: request)
    }

    /// Deletes this phone and everything it reported. The secret is invalid afterwards, so it is
    /// removed here as well.
    func leaveHousehold() async throws -> OkResponse {
        let response: OkResponse = try await call("POST", "households/leave")
        credentials.clear()
        return response
    }

    func myNeighborhood() async throws -> NeighborhoodOut {
        try await call("GET", "neighborhoods/me")
    }

    /// Updates this phone's own record (OS or app version, capabilities). Never creates a device.
    func registerDevice(_ request: RegisterDeviceRequest) async throws -> DeviceOut {
        try await call("POST", "devices/register", body: request)
    }

    func revokeDevice(id: UUID) async throws -> OkResponse {
        try await call("POST", "devices/revoke", body: RevokeDeviceRequest(deviceID: id))
    }

    func devices() async throws -> [DeviceOut] {
        try await call("GET", "devices")
    }

    func registerPushToken(_ request: RegisterPushTokenRequest) async throws -> OkResponse {
        try await call("POST", "notifications/register", body: request)
    }

    func notificationPreferences() async throws -> NotificationPreferences {
        try await call("GET", "notifications/preferences")
    }

    func updateNotificationPreferences(_ preferences: NotificationPreferences) async throws -> NotificationPreferences {
        try await call("PUT", "notifications/preferences", body: preferences)
    }

    func submitEvents(_ request: SubmitEventsRequest) async throws -> SubmitEventsResponse {
        try await call("POST", "connectivity/events", body: request)
    }

    func connectivityStatus() async throws -> ConnectivityStatusOut {
        try await call("GET", "connectivity/status")
    }

    func incidents(openOnly: Bool, limit: Int = 50) async throws -> IncidentListOut {
        try await call("GET", "incidents", query: [
            URLQueryItem(name: "status", value: openOnly ? "open" : "all"),
            URLQueryItem(name: "limit", value: String(limit)),
        ])
    }

    func incident(id: UUID) async throws -> IncidentDetailOut {
        try await call("GET", "incidents/\(id.uuidString.lowercased())")
    }

    // MARK: Plumbing

    private func call<Response: Decodable>(
        _ method: String, _ path: String, query: [URLQueryItem] = [], authenticated: Bool = true
    ) async throws -> Response {
        try await call(method, path, query: query, body: Optional<NoBody>.none, authenticated: authenticated)
    }

    private func call<Body: Encodable & Sendable, Response: Decodable>(
        _ method: String, _ path: String, query: [URLQueryItem] = [], body: Body?, authenticated: Bool = true
    ) async throws -> Response {
        var components = URLComponents(
            url: baseURL.appendingPathComponent("api/v1").appendingPathComponent(path), resolvingAgainstBaseURL: false)
        if !query.isEmpty { components?.queryItems = query }
        guard let url = components?.url else { throw APIError.invalidConfiguration("API_BASE_URL") }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            do {
                request.httpBody = try WatchdogJSON.encoder().encode(body)
            } catch {
                throw APIError.decoding("request body: \(error)")
            }
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        var token: String?
        if authenticated {
            guard let stored = credentials.load() else { throw APIError.notEnrolled }
            token = stored
            request.setValue("Bearer \(stored)", forHTTPHeaderField: "Authorization")
        }
        let data: Data, response: HTTPURLResponse
        do {
            (data, response) = try await transport.send(request)
        } catch {
            throw APIError.from(error)
        }

        if response.statusCode == 401, let rejected = token {
            let error = APIError.fromResponse(data: data, response: response)
            guard error.code == "unauthorized" else { throw error }
            // Remove the secret only if it is still the one that was refused (the phone may have
            // been enrolled again while this request was on its way).
            if credentials.load() == rejected {
                credentials.clear()
                await onUnenrolled?()
            }
            throw APIError.notEnrolled
        }

        guard (200..<300).contains(response.statusCode) else {
            throw APIError.fromResponse(data: data, response: response)
        }
        do {
            return try WatchdogJSON.decoder().decode(Response.self, from: data)
        } catch {
            throw APIError.decoding("\(method) \(path): \(error)")
        }
    }
}

/// Accepts a full invitation link (`https://host/join#t=<token>`), the link the web join page
/// opens (`feluda://join#t=<token>`) or the bare token.
enum InvitationToken {
    static func parse(_ input: String) -> String? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        var candidate = trimmed
        if let hash = trimmed.firstIndex(of: "#") {
            let fragment = trimmed[trimmed.index(after: hash)...]
            let pairs = fragment.split(separator: "&").map { $0.split(separator: "=", maxSplits: 1) }
            if let pair = pairs.first(where: { $0.count == 2 && $0[0] == "t" }) {
                candidate = String(pair[1])
            } else {
                return nil
            }
        }
        candidate = candidate.removingPercentEncoding ?? candidate
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")
        guard (20...128).contains(candidate.count),
              candidate.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        return candidate
    }
}

/// The link the web join page opens to hand an invitation to the app: `feluda://join#t=<token>`.
enum InvitationLink {
    static let scheme = "feluda"

    static func token(from url: URL) -> String? {
        guard url.scheme?.lowercased() == scheme, url.host?.lowercased() == "join", url.fragment != nil else { return nil }
        return InvitationToken.parse(url.absoluteString)
    }
}
