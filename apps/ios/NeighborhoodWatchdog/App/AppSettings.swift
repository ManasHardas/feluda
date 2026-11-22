import Foundation

/// Small app-private flags in UserDefaults. No tokens and no addresses are stored here.
final class AppSettings: @unchecked Sendable {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private enum Key: String, CaseIterable {
        case hasLaunchedBefore = "watchdog.hasLaunchedBefore"
        case consentGivenAt = "watchdog.consentGivenAt"
        case onboardingComplete = "watchdog.onboardingComplete"
        case installID = "watchdog.installID"
        case registeredSignature = "watchdog.registeredSignature"
        case pushToken = "watchdog.pushToken"
        case pushTokenSentSignature = "watchdog.pushTokenSentSignature"
        case notificationsAuthorized = "watchdog.notificationsAuthorized"
        case backgroundRefreshAvailable = "watchdog.backgroundRefreshAvailable"
        case homeStepDone = "watchdog.homeStepDone"
        case notificationStepDone = "watchdog.notificationStepDone"
    }

    var hasLaunchedBefore: Bool {
        get { defaults.bool(forKey: Key.hasLaunchedBefore.rawValue) }
        set { defaults.set(newValue, forKey: Key.hasLaunchedBefore.rawValue) }
    }

    /// When the resident gave explicit consent. Monitoring never starts while this is nil.
    var consentGivenAt: Date? {
        get { defaults.object(forKey: Key.consentGivenAt.rawValue) as? Date }
        set { defaults.set(newValue, forKey: Key.consentGivenAt.rawValue) }
    }

    var onboardingComplete: Bool {
        get { defaults.bool(forKey: Key.onboardingComplete.rawValue) }
        set { defaults.set(newValue, forKey: Key.onboardingComplete.rawValue) }
    }

    var notificationStepDone: Bool {
        get { defaults.bool(forKey: Key.notificationStepDone.rawValue) }
        set { defaults.set(newValue, forKey: Key.notificationStepDone.rawValue) }
    }

    var homeStepDone: Bool {
        get { defaults.bool(forKey: Key.homeStepDone.rawValue) }
        set { defaults.set(newValue, forKey: Key.homeStepDone.rawValue) }
    }

    /// Random UUID generated once per install. Not a hardware identifier; removed on sign-out,
    /// on leaving and when the app is deleted.
    var installID: UUID? {
        get { defaults.string(forKey: Key.installID.rawValue).flatMap(UUID.init(uuidString:)) }
        set { defaults.set(newValue?.uuidString, forKey: Key.installID.rawValue) }
    }

    func installIDCreatingIfNeeded() -> UUID {
        if let installID { return installID }
        let fresh = UUID()
        installID = fresh
        return fresh
    }

    /// "<install id>|<os version>|<app version>" of the last successful device registration.
    var registeredSignature: String? {
        get { defaults.string(forKey: Key.registeredSignature.rawValue) }
        set { defaults.set(newValue, forKey: Key.registeredSignature.rawValue) }
    }

    var pushToken: String? {
        get { defaults.string(forKey: Key.pushToken.rawValue) }
        set { defaults.set(newValue, forKey: Key.pushToken.rawValue) }
    }

    /// "<install id>|<token>" of the last push token the backend acknowledged.
    var pushTokenSentSignature: String? {
        get { defaults.string(forKey: Key.pushTokenSentSignature.rawValue) }
        set { defaults.set(newValue, forKey: Key.pushTokenSentSignature.rawValue) }
    }

    var notificationsAuthorized: Bool? {
        get { defaults.object(forKey: Key.notificationsAuthorized.rawValue) as? Bool }
        set { defaults.set(newValue, forKey: Key.notificationsAuthorized.rawValue) }
    }

    var backgroundRefreshAvailable: Bool? {
        get { defaults.object(forKey: Key.backgroundRefreshAvailable.rawValue) as? Bool }
        set { defaults.set(newValue, forKey: Key.backgroundRefreshAvailable.rawValue) }
    }

    /// Removes everything except the "launched before" marker (used when leaving or signing out).
    func resetForSignOut() {
        for key in Key.allCases where key != .hasLaunchedBefore {
            defaults.removeObject(forKey: key.rawValue)
        }
    }
}
