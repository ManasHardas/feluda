import Foundation

/// Typed client for /api/v1. Every authenticated request carries `Authorization: Bearer <token>`.
/// On a 401 the token is refreshed once (serialised by `AuthService`) and the request retried once.
struct APIClient: Sendable {
    let baseURL: URL
    let transport: HTTPTransport
    let auth: AuthService

    private struct NoBody: Encodable, Sendable {}

    // MARK: Endpoints

    func health() async throws -> HealthOut {
        try await call("GET", "monitoring/health", authenticated: false)
    }

    func verifyInvitation(token: String) async throws -> VerifyInvitationResponse {
        try await call("POST", "auth/verify-invitation", body: VerifyInvitationRequest(invitationToken: token), authenticated: false)
    }

    func joinHousehold(_ request: JoinHouseholdRequest) async throws -> HouseholdOut {
        try await call("POST", "households/join", body: request)
    }

    func myHousehold() async throws -> HouseholdOut {
        try await call("GET", "households/me")
    }

    func updateHousehold(_ request: UpdateHouseholdRequest) async throws -> HouseholdOut {
        try await call("PATCH", "households/me", body: request)
    }

    func leaveHousehold() async throws -> OkResponse {
        try await call("POST", "households/leave")
    }

    func deleteAccount() async throws -> OkResponse {
        try await call("DELETE", "account")
    }

    func myNeighborhood() async throws -> NeighborhoodOut {
        try await call("GET", "neighborhoods/me")
    }

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
            token = try await auth.validAccessToken()
        }
        var (data, response) = try await perform(request, token: token)

        if response.statusCode == 401, authenticated, let rejected = token {
            // Refresh once, retry once. A second 401 is reported to the caller.
            let fresh = try await auth.accessTokenAfterUnauthorized(rejectedToken: rejected)
            (data, response) = try await perform(request, token: fresh)
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

    private func perform(_ request: URLRequest, token: String?) async throws -> (Data, HTTPURLResponse) {
        var request = request
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        do {
            return try await transport.send(request)
        } catch {
            throw APIError.from(error)
        }
    }
}

/// Accepts either a full invitation link (`https://host/join#t=<token>`) or the bare token.
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
