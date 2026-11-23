import Foundation

/// Build-time configuration, injected from Config/*.xcconfig into Info.plist. Contains no secrets:
/// the auth key is the publishable key, which is public by design.
struct AppConfig: Sendable, Equatable {
    var apiBaseURL: URL
    var authBaseURL: URL
    var authPublicKey: String
    var sourceRepoURL: URL?
    var privacyURL: URL?
    var securityReviewURL: URL?
    var securityContact: String?
    var probeFallbackURL: URL?
    var apnsEnvironment: ApnsEnvironment

    struct ConfigurationError: Error, Equatable, CustomStringConvertible {
        let description: String
    }

    static func load(bundle: Bundle = .main) throws -> AppConfig {
        #if DEBUG
        let requireHTTPS = false
        #else
        let requireHTTPS = true
        #endif
        return try make(from: bundle.infoDictionary ?? [:], requireHTTPS: requireHTTPS)
    }

    /// - Parameter requireHTTPS: true for Release builds. When false (Debug), plain HTTP is
    ///   accepted for `localhost` only, matching the Debug-only App Transport Security exception.
    static func make(from info: [String: Any], requireHTTPS: Bool) throws -> AppConfig {
        func text(_ key: String) -> String? {
            guard let value = (info[key] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
            return value
        }
        func serviceURL(_ key: String, setting: String) throws -> URL {
            guard let value = text(key), let url = URL(string: value), let scheme = url.scheme?.lowercased(), let host = url.host else {
                throw ConfigurationError(description: "\(setting) is missing or is not a URL.")
            }
            let localhost = host == "localhost" || host == "127.0.0.1"
            guard scheme == "https" || (!requireHTTPS && scheme == "http" && localhost) else {
                throw ConfigurationError(description: "\(setting) must use HTTPS.")
            }
            return url
        }
        func linkURL(_ key: String) -> URL? {
            guard let value = text(key), let url = URL(string: value), url.scheme?.lowercased() == "https" else { return nil }
            return url
        }
        return AppConfig(
            apiBaseURL: try serviceURL("WatchdogAPIBaseURL", setting: "API_BASE_URL"),
            authBaseURL: try serviceURL("WatchdogAuthBaseURL", setting: "AUTH_BASE_URL"),
            authPublicKey: text("WatchdogAuthPublicKey") ?? "",
            sourceRepoURL: linkURL("WatchdogSourceRepoURL"),
            privacyURL: linkURL("WatchdogPrivacyURL"),
            securityReviewURL: linkURL("WatchdogSecurityReviewURL"),
            securityContact: text("WatchdogSecurityContact"),
            probeFallbackURL: linkURL("WatchdogProbeFallbackURL"),
            apnsEnvironment: ApnsEnvironment(rawValue: text("WatchdogAPNsEnvironment") ?? "") ?? (requireHTTPS ? .production : .sandbox)
        )
    }

    var healthURL: URL {
        apiBaseURL.appendingPathComponent("api/v1/monitoring/health")
    }
}
