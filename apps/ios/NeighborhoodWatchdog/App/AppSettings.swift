import Foundation

/// Small app-private flags in UserDefaults. No secrets and no addresses are stored here.
final class AppSettings: @unchecked Sendable {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private enum Key: String, CaseIterable {
        case hasLaunchedBefore = "watchdog.hasLaunchedBefore"
        case introSeen = "watchdog.introSeen"
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
        case updateRequiredForVersion = "watchdog.updateRequiredForVersion"
    }

    var hasLaunchedBefore: Bool {
        get { defaults.bool(forKey: Key.hasLaunchedBefore.rawValue) }
        set { defaults.set(newValue, forKey: Key.hasLaunchedBefore.rawValue) }
    }

    /// The resident has read the introduction and privacy summary and tapped "Continue".
    var introSeen: Bool {
        get { defaults.bool(forKey: Key.introSeen.rawValue) }
        set { defaults.set(newValue, forKey: Key.introSeen.rawValue) }
    }

    /// When the resident tapped "Join" under the privacy summary, which is the acknowledgement.
    /// Monitoring never starts while this is nil.
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

    /// Random UUID generated once per install. Not a hardware identifier; removed on leaving,
    /// when the phone is no longer enrolled and when the app is deleted.
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

    /// "<install id>|<os version>|<app version>" the backend last received, at enrolment or with a
    /// later device update. Nil while this phone is not enrolled.
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

    /// The app version (`SystemInfo.appVersion`) the server last refused with 426. While it equals
    /// the running version nothing is sent, also after a relaunch in the background; installing
    /// the update changes the version and so lifts it.
    var updateRequiredForVersion: String? {
        get { defaults.string(forKey: Key.updateRequiredForVersion.rawValue) }
        set { defaults.set(newValue, forKey: Key.updateRequiredForVersion.rawValue) }
    }

    /// Removes everything except the "launched before" marker (used when leaving, and when the
    /// server says this phone is no longer enrolled). `keepIntroSeen` skips the introduction the
    /// next time, for a phone that did not choose to leave.
    func resetForLeaving(keepIntroSeen: Bool = false) {
        for key in Key.allCases where key != .hasLaunchedBefore && !(keepIntroSeen && key == .introSeen) {
            defaults.removeObject(forKey: key.rawValue)
        }
    }
}
