import Foundation

/// The app version as the backend sees it.
///
/// Released apps are rarely updated, so the server, not the app, decides when a version is too
/// old. Every request carries `X-App-Version: ios/<major.minor.patch> (<build>)`. A refused version
/// gets HTTP 426 `update_required`; `GET /api/v1/app/compatibility` answers the same question
/// without a credential, at launch and when the app comes to the foreground.
enum AppVersion {
    static let headerName = "X-App-Version"

    /// "0.1" -> "0.1.0", "1.2.3" -> "1.2.3". Nil when the marketing version is not one to three
    /// groups of digits (the server then does not enforce a minimum).
    static func semantic(_ marketingVersion: String) -> String? {
        let parts = marketingVersion.trimmingCharacters(in: .whitespaces).split(separator: ".", omittingEmptySubsequences: false)
        guard (1...3).contains(parts.count),
              parts.allSatisfy({ (1...4).contains($0.count) && $0.allSatisfy { $0.isASCII && $0.isNumber } })
        else { return nil }
        let padded = parts.map(String.init) + Array(repeating: "0", count: 3 - parts.count)
        return padded.joined(separator: ".")
    }

    /// `ios/0.1.0 (1)`. Nil when the marketing version cannot be expressed as major.minor.patch.
    static func header(marketingVersion: String, buildNumber: String) -> String? {
        guard let version = semantic(marketingVersion) else { return nil }
        let build = buildNumber.trimmingCharacters(in: .whitespaces)
        return build.isEmpty ? "ios/\(version)" : "ios/\(version) (\(build))"
    }

    /// Paths answered whatever the version (mirrors the backend's exempt list).
    static func isExempt(path: String) -> Bool {
        path.hasPrefix("app/") || path.hasPrefix("monitoring/") || path.hasPrefix("admin/")
    }
}

/// Whether the server refused this app version. Shared by the API client (which sets it on a 426
/// and then stops sending), the event reporter (which does not upload while it is set) and the app
/// model (which shows the "Update required" screen). Events already queued are kept.
final class UpdateGate: @unchecked Sendable {
    private let lock = NSLock()
    private var required: Bool
    private let onChange: (@Sendable (Bool) -> Void)?

    init(required: Bool = false, onChange: (@Sendable (Bool) -> Void)? = nil) {
        self.required = required
        self.onChange = onChange
    }

    var isUpdateRequired: Bool {
        lock.lock(); defer { lock.unlock() }
        return required
    }

    func setRequired(_ value: Bool) {
        lock.lock()
        let changed = required != value
        required = value
        lock.unlock()
        if changed { onChange?(value) }
    }
}

/// What the compatibility check means for the app.
struct CompatibilityOutcome: Equatable, Sendable {
    /// False: this version is refused; the "Update required" screen is shown.
    var supported: Bool
    /// A newer version exists (only meaningful while supported).
    var updateAvailable: Bool
    /// Where the server says to get the update. Nil: open TestFlight.
    var updateURL: URL?

    init(supported: Bool, updateAvailable: Bool, updateURL: URL?) {
        self.supported = supported
        self.updateAvailable = updateAvailable
        self.updateURL = updateURL
    }

    init(_ response: AppCompatibilityOut) {
        supported = response.supported
        updateAvailable = response.supported && response.updateAvailable
        updateURL = UpdateLink.serverURL(response.updateURL)
    }
}

enum Compatibility {
    /// Asks the server about this version and updates the gate. Any failure (no network, server
    /// error, unexpected answer) returns nil and changes nothing: the check never blocks the app.
    static func check(api: APIClient, gate: UpdateGate, version: String) async -> CompatibilityOutcome? {
        guard let response = try? await api.compatibility(platform: .ios, version: version) else { return nil }
        let outcome = CompatibilityOutcome(response)
        gate.setRequired(!outcome.supported)
        return outcome
    }
}

/// Where the "Update" button goes: the server's link, else the TestFlight app, else the TestFlight
/// web page.
enum UpdateLink {
    static let testFlightApp = URL(string: "itms-beta://")!
    static let testFlightWeb = URL(string: "https://testflight.apple.com/")!

    /// Accepts only https and the App Store / TestFlight schemes from the server.
    static func serverURL(_ text: String?) -> URL? {
        guard let text, let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = url.scheme?.lowercased(), ["https", "itms-beta", "itms-apps"].contains(scheme) else { return nil }
        if scheme == "https", url.host?.isEmpty ?? true { return nil }
        return url
    }

    /// The links to try, in order, until one opens.
    static func candidates(serverURL: URL?) -> [URL] {
        var urls: [URL] = []
        if let serverURL { urls.append(serverURL) }
        for fallback in [testFlightApp, testFlightWeb] where !urls.contains(fallback) {
            urls.append(fallback)
        }
        return urls
    }
}
