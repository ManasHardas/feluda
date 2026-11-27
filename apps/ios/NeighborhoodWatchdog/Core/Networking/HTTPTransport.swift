import Foundation

/// The single seam between the app and the network. Tests replace it with a fake.
protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

struct URLSessionTransport: HTTPTransport {
    let session: URLSession

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        return (data, http)
    }
}

enum WatchdogSessions {
    /// Session used for API calls and event uploads.
    ///
    /// Cellular, expensive and constrained (Low Data Mode) networks are all allowed, so that an
    /// upload can leave over cellular when Wi-Fi is unusable. `waitsForConnectivity` makes a
    /// request wait for a usable path instead of failing immediately; the wait is bounded by
    /// `timeoutIntervalForResource`.
    ///
    /// Important: this does NOT force or guarantee cellular routing. iOS chooses the interface.
    /// When Wi-Fi is down or the system considers it unusable, requests normally go over cellular
    /// if cellular data is available and allowed for this app. An app cannot pick the interface
    /// for a URLSession request.
    static func reportingConfiguration(protocolClasses: [AnyClass]? = nil) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.allowsCellularAccess = true
        configuration.allowsExpensiveNetworkAccess = true
        configuration.allowsConstrainedNetworkAccess = true
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 45
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        if let protocolClasses { configuration.protocolClasses = protocolClasses }
        return configuration
    }

    /// Session for requests a person is waiting for on screen (joining, loading status). It fails
    /// quickly when there is no connection instead of waiting, so the screen can say so.
    static func interactiveConfiguration() -> URLSessionConfiguration {
        let configuration = reportingConfiguration()
        configuration.waitsForConnectivity = false
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 30
        return configuration
    }

    /// Session used for reachability probes: fails fast, never waits for connectivity, never caches.
    /// With `allowCellular == false` the request cannot use the cellular interface, which is how
    /// the app tests "does the internet work over Wi-Fi" without the system quietly falling back
    /// to cellular.
    static func probeConfiguration(allowCellular: Bool) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.allowsCellularAccess = allowCellular
        configuration.allowsExpensiveNetworkAccess = true
        configuration.allowsConstrainedNetworkAccess = true
        configuration.waitsForConnectivity = false
        configuration.timeoutIntervalForRequest = 6
        configuration.timeoutIntervalForResource = 8
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        return configuration
    }
}

enum APIError: Error, Sendable, Equatable {
    /// The server answered with an error status. `code` is the contract error code when the body
    /// had one (for example "not_a_member"), otherwise "http_<status>".
    case server(status: Int, code: String, message: String, requestID: String?, retryAfter: TimeInterval?)
    /// The request did not complete (no network, timeout, TLS failure...).
    case transport(URLError.Code)
    case decoding(String)
    /// This phone is not enrolled: it holds no device secret, or the server answered 401
    /// `unauthorized` for the one it had (removed, revoked or reinstalled). The join screen is next.
    case notEnrolled
    /// Enrolment succeeded but the device secret could not be written to the Keychain.
    case credentialStorage
    case invalidConfiguration(String)
    /// The server answered 426 `update_required`: this app version is no longer accepted. Nothing
    /// is sent until the app is updated (events stay queued on the phone).
    case updateRequired

    var code: String? {
        if case let .server(_, code, _, _, _) = self { return code }
        return nil
    }

    var status: Int? {
        if case let .server(status, _, _, _, _) = self { return status }
        return nil
    }

    /// True when retrying later may succeed without the resident doing anything.
    var isTransient: Bool {
        switch self {
        case .transport: return true
        case let .server(status, _, _, _, _): return status >= 500 || status == 429 || status == 408
        default: return false
        }
    }

    /// Text safe to show to a resident.
    var userMessage: String {
        switch self {
        case let .server(_, code, message, _, _):
            switch code {
            case "invitation_invalid": return "This invitation is not valid. It may have expired, been used already or been withdrawn. Ask your neighborhood organizer for a new one."
            case "household_already_registered":
                // The server's own sentence says what to do; the fallback is only for an empty body.
                return message.isEmpty ? "This home is already taking part. Ask your neighborhood organizer for a link for this household." : message
            case "device_conflict": return "This phone cannot be enrolled with this link. Delete the app, install it again and open the link once more."
            case "household_suspended": return "This household has been suspended by the neighborhood organizer."
            case "validation_error": return message.isEmpty ? "The service did not accept what was entered. Check the street name and try again." : message
            case "rate_limited": return "Too many attempts. Please wait a little and try again."
            default: return message.isEmpty ? "The service returned an error (\(code))." : message
            }
        case .transport:
            return "Could not reach the Neighborhood Watchdog service. Check your connection and try again."
        case .decoding:
            return "The service sent a response this version of the app does not understand."
        case .notEnrolled:
            return "This phone is not enrolled in a neighborhood. Open your invitation link to join."
        case .credentialStorage:
            return "This phone could not store its enrolment in the Keychain. Unlock the phone and tap Join again."
        case let .invalidConfiguration(detail):
            return "This build is not configured correctly: \(detail)"
        case .updateRequired:
            return "This version of the app is no longer supported. Install the update to keep taking part."
        }
    }

    static func from(_ error: Error) -> APIError {
        if let api = error as? APIError { return api }
        if let url = error as? URLError { return .transport(url.code) }
        if error is CancellationError { return .transport(.cancelled) }
        return .transport(.unknown)
    }
}

extension APIError {
    /// Builds an error from a non-2xx response. Understands the API error envelope
    /// `{"error": {"code", "message", "request_id"}}`; anything else becomes "http_<status>".
    static func fromResponse(data: Data, response: HTTPURLResponse) -> APIError {
        var code = "http_\(response.statusCode)"
        var message = ""
        var requestID: String?
        if let envelope = try? WatchdogJSON.decoder().decode(APIErrorEnvelope.self, from: data) {
            code = envelope.error.code
            message = envelope.error.message
            requestID = envelope.error.requestID
        }
        var retryAfter: TimeInterval?
        if let header = response.value(forHTTPHeaderField: "Retry-After"), let seconds = TimeInterval(header.trimmingCharacters(in: .whitespaces)), seconds >= 0 {
            retryAfter = seconds
        }
        if response.statusCode == 429, code.hasPrefix("http_") { code = "rate_limited" }
        return .server(status: response.statusCode, code: code, message: message, requestID: requestID, retryAfter: retryAfter)
    }
}
