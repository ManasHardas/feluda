import BackgroundTasks
import Observation
import SwiftUI
import UIKit
import UserNotifications

enum NotificationPermission: String, Sendable {
    case notDetermined, denied, authorized

    var label: String {
        switch self {
        case .notDetermined: return "Not asked yet"
        case .denied: return "Off"
        case .authorized: return "On"
        }
    }
}

/// App-wide state and the flows that span screens (onboarding, enrolment, monitoring switch,
/// leaving). Screen-specific loading lives in the small view models next to each screen.
@MainActor
@Observable
final class AppModel {
    static let shared = AppModel()
    /// The unit-test bundle is hosted by the app. Under test the app must not start networking.
    /// Nothing below `bootstrap` runs then: no services, no monitors, no probes, no uploads.
    static let isRunningTests: Bool = {
        let environment = ProcessInfo.processInfo.environment
        return environment["XCTestConfigurationFilePath"] != nil || environment["XCTestBundlePath"] != nil
            || environment["XCTestSessionIdentifier"] != nil || NSClassFromString("XCTestCase") != nil
    }()

    enum Phase: Equatable {
        case loading
        case configurationError(String)
        /// Introduction and privacy summary.
        case education
        /// Invitation link, then street and optional house number. There is no sign-in.
        case join
        case settingUp
        case notifications
        case homeNetwork
        case main
    }

    private(set) var phase: Phase = .loading
    private(set) var services: Services?

    var monitoring = MonitoringCoordinator.Snapshot()
    private(set) var household: HouseholdOut?
    private(set) var neighborhood: NeighborhoodOut?
    private(set) var status: ConnectivityStatusOut?
    private(set) var openIncidents: [IncidentOut] = []
    /// Set when the last attempt to load status from the service failed.
    private(set) var serviceError: String?
    private(set) var lastRefreshAt: Date?
    private(set) var isRefreshing = false
    private(set) var setupError: String?
    /// Invitation token from a link that opened the app (`feluda://join#t=<token>`), waiting for
    /// the join screen.
    private(set) var pendingInvitation: String?
    /// Shown on the join screen, for example after the server said this phone is not enrolled.
    private(set) var joinNotice: String?
    private(set) var notificationPermission: NotificationPermission = .notDetermined
    private(set) var backgroundRefreshAvailable = true
    /// Incident to open because the resident tapped a notification.
    var pendingIncidentID: UUID?

    @ObservationIgnored private var bootstrapTask: Task<Void, Never>?
    @ObservationIgnored private var monitorStartTask: Task<Void, Never>?
    @ObservationIgnored private var batteryObserver: NSObjectProtocol?
    /// The Keychain could not be read at launch (phone not unlocked since it restarted), so it is
    /// not known yet whether the device secret is still there.
    @ObservationIgnored private var enrolmentUnknown = false

    private init() {}

    // MARK: Launch

    func bootstrap() async {
        if let bootstrapTask {
            await bootstrapTask.value
            return
        }
        let task = Task { await self.performBootstrap() }
        bootstrapTask = task
        await task.value
    }

    private func performBootstrap() async {
        guard !Self.isRunningTests else { return }
        let settings = AppSettings()
        let config: AppConfig
        do {
            config = try AppConfig.load()
        } catch {
            phase = .configurationError("\(error)")
            return
        }
        if !settings.hasLaunchedBefore, UIApplication.shared.isProtectedDataAvailable {
            // Keychain items survive deleting the app. A new install starts clean.
            Services.clearKeychain()
            settings.hasLaunchedBefore = true
        }
        let services = Services.make(config: config, settings: settings)
        self.services = services

        await services.coordinator.setOnSnapshot { snapshot in
            Task { @MainActor in AppModel.shared.monitoring = snapshot }
        }
        await services.reporter.setOnChange { [coordinator = services.coordinator] in
            let snapshot = await coordinator.snapshot()
            await MainActor.run { AppModel.shared.monitoring = snapshot }
        }
        services.uploadPath.start { [reporter = services.reporter] in
            Task { await reporter.connectivityChanged() }
        }
        startBatteryMonitoring()

        await routeAtLaunch()
        monitoring = await services.coordinator.snapshot()
        await refreshPermissions()
    }

    /// Chooses the first screen from what is stored on the phone. Enrolled means: the Keychain
    /// holds a device secret.
    private func routeAtLaunch() async {
        guard let services else { return }
        let settings = services.settings
        if services.api.isEnrolled {
            pendingInvitation = nil
            if settings.onboardingComplete {
                // Monitoring keeps working without a connection; events wait in the queue.
                phase = .main
                startMonitoring(reason: .processLaunch)
                Task { await self.syncAfterLaunch() }
            } else {
                await completeMembership()
            }
        } else if settings.registeredSignature != nil, !UIApplication.shared.isProtectedDataAvailable {
            // Launched in the background before the first unlock: the Keychain cannot be read yet.
            // Nothing is decided or removed until the app is opened.
            enrolmentUnknown = true
            if settings.onboardingComplete {
                phase = .main
                startMonitoring(reason: .processLaunch)
            }
        } else if settings.registeredSignature != nil || settings.onboardingComplete {
            // This phone was enrolled but holds no secret any more: start again with a clean slate.
            await wipeLocalData(keepIntroSeen: true)
        } else {
            phase = settings.introSeen ? .join : .education
        }
    }

    private func resolveUnknownEnrolment() async {
        guard enrolmentUnknown, let services else { return }
        let enrolled = services.api.isEnrolled
        guard enrolled || UIApplication.shared.isProtectedDataAvailable else { return }
        enrolmentUnknown = false
        if !enrolled {
            await wipeLocalData(keepIntroSeen: true)
        } else if services.settings.onboardingComplete {
            await syncAfterLaunch()
        } else {
            await completeMembership()
        }
    }

    private func syncAfterLaunch() async {
        guard let services else { return }
        await DeviceRegistration.register(api: services.api, settings: services.settings, force: false)
        await sendPushTokenIfNeeded()
        await refresh()
    }

    private func startMonitoring(reason: MonitoringCoordinator.StartReason) {
        guard let services, services.settings.consentGivenAt != nil, services.settings.onboardingComplete else { return }
        let previous = monitorStartTask
        monitorStartTask = Task {
            await previous?.value
            await services.coordinator.start(reason: reason)
        }
    }

    // MARK: Onboarding

    /// The resident read the introduction and privacy summary. Nothing is monitored or sent yet.
    func finishIntroduction() {
        guard let services else { return }
        services.settings.introSeen = true
        phase = .join
    }

    /// A link opened the app. Only `feluda://join#t=<token>` (and the same link as https) is
    /// understood; anything else is ignored. A phone that is already enrolled ignores it too.
    func handleIncomingURL(_ url: URL) {
        guard !Self.isRunningTests, let token = InvitationLink.token(from: url) else { return }
        if let services, services.api.isEnrolled { return }
        pendingInvitation = token
        joinNotice = nil
    }

    func clearPendingInvitation() {
        pendingInvitation = nil
    }

    /// Checks an invitation (pasted link, bare token or the token from a link) with the service
    /// and returns the name of the neighborhood it belongs to.
    func verifyInvitation(_ input: String) async throws -> VerifiedInvitation {
        guard let services, let token = InvitationToken.parse(input) else {
            throw APIError.server(status: 404, code: "invitation_invalid", message: "", requestID: nil, retryAfter: nil)
        }
        let response = try await services.api.verifyInvitation(token: token)
        return VerifiedInvitation(token: token, neighborhoodName: response.neighborhoodName)
    }

    /// Enrols this phone. The resident gives only the street and, if they wish, a house number;
    /// tapping "Join" under the privacy summary is the acknowledgement.
    func enroll(invitation: VerifiedInvitation, streetName: String, houseNumber: String) async throws {
        guard let services else { return }
        let settings = services.settings
        let number = houseNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        let signature = DeviceRegistration.signature(settings: settings)
        let response = try await services.api.enroll(EnrollRequest(
            invitationToken: invitation.token,
            streetName: streetName.trimmingCharacters(in: .whitespacesAndNewlines),
            houseNumber: number.isEmpty ? nil : number,
            privacyAcknowledged: true,
            deviceID: settings.installIDCreatingIfNeeded(),
            platform: .ios, osVersion: SystemInfo.osVersion, appVersion: SystemInfo.appVersion, capabilities: .iOS))
        household = response.household
        settings.introSeen = true
        settings.consentGivenAt = Date()
        // Enrolment created the device with these values; nothing more to send until they change.
        settings.registeredSignature = signature
        pendingInvitation = nil
        joinNotice = nil
        await completeMembership()
    }

    /// This phone is enrolled: bring its device record up to date if the OS or app version
    /// changed, then continue with the remaining steps.
    func completeMembership() async {
        guard let services else { return }
        phase = .settingUp
        setupError = nil
        do {
            try await DeviceRegistration.registerThrowing(api: services.api, settings: services.settings, force: false)
        } catch {
            setupError = APIError.from(error).userMessage
            return
        }
        await sendPushTokenIfNeeded()
        let settings = services.settings
        if settings.onboardingComplete {
            phase = .main
            startMonitoring(reason: .processLaunch)
            await refresh()
        } else if !settings.notificationStepDone {
            phase = .notifications
        } else if !settings.homeStepDone {
            await services.coordinator.beginPathPreview()
            phase = .homeNetwork
        } else {
            await finishOnboarding()
        }
    }

    func retrySetup() async {
        await completeMembership()
    }

    func requestNotificationPermission() async {
        let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        if granted {
            UIApplication.shared.registerForRemoteNotifications()
        }
        await finishNotificationStep()
    }

    func finishNotificationStep() async {
        guard let services else { return }
        services.settings.notificationStepDone = true
        await refreshPermissions()
        await completeMembership()
    }

    /// "This phone is on my home Wi-Fi now."
    func confirmHomeNetwork() async -> MonitoringCoordinator.HomeNetworkResult {
        guard let services else { return .storageFailed }
        let result = await services.coordinator.rememberCurrentNetworkAsHome()
        if result == .stored {
            await finishOnboarding()
        }
        return result
    }

    func finishOnboarding() async {
        guard let services else { return }
        services.settings.homeStepDone = true
        services.settings.onboardingComplete = true
        phase = .main
        startMonitoring(reason: .firstStart)
        await refresh()
    }

    // MARK: Status from the service

    func refresh() async {
        guard !Self.isRunningTests else { return }
        guard let services, phase == .main, !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        let api = services.api
        async let statusResult = Self.capture { try await api.connectivityStatus() }
        async let neighborhoodResult = Self.capture { try await api.myNeighborhood() }
        async let incidentsResult = Self.capture { try await api.incidents(openOnly: true) }
        async let householdResult = Self.capture { try await api.myHousehold() }
        let results = await (statusResult, neighborhoodResult, incidentsResult, householdResult)

        var firstError: APIError?
        switch results.0 {
        case let .success(value): status = value
        case let .failure(error): firstError = firstError ?? error
        }
        switch results.1 {
        case let .success(value): neighborhood = value
        case let .failure(error): firstError = firstError ?? error
        }
        switch results.2 {
        case let .success(value): openIncidents = value.incidents
        case let .failure(error): firstError = firstError ?? error
        }
        switch results.3 {
        case let .success(value): household = value
        case let .failure(error): firstError = firstError ?? error
        }
        if let firstError {
            // Do not keep showing a status the app can no longer vouch for.
            status = nil
            neighborhood = nil
            openIncidents = []
            handleServiceError(firstError)
        } else {
            serviceError = nil
            lastRefreshAt = Date()
        }
        monitoring = await services.coordinator.snapshot()
    }

    private nonisolated static func capture<Value: Sendable>(_ work: @Sendable () async throws -> Value) async -> Result<Value, APIError> {
        do {
            return .success(try await work())
        } catch {
            return .failure(APIError.from(error))
        }
    }

    func handleServiceError(_ error: APIError) {
        // `.notEnrolled` after a 401 is handled by `deviceWasUnenrolled`, which the API client
        // calls itself. Here it can also mean the Keychain was not readable; nothing is removed.
        serviceError = error.userMessage
    }

    /// The server answered 401: this phone is not enrolled any more (removed by the household or
    /// the organizer, or revoked). The API client has already removed the secret. Everything
    /// else the app stored goes too, and the join screen is shown.
    func deviceWasUnenrolled() async {
        guard services != nil, phase != .join, phase != .education else { return }
        await wipeLocalData(keepIntroSeen: true)
        joinNotice = "This phone is no longer enrolled in the neighborhood. To take part again, open an invitation link from your neighborhood organizer."
    }

    // MARK: Monitoring switch

    func setMonitoringEnabled(_ enabled: Bool) async {
        guard let services else { return }
        if enabled {
            startMonitoring(reason: .userEnabled)
            await monitorStartTask?.value
        } else {
            await monitorStartTask?.value
            await services.coordinator.disableByUser()
        }
        monitoring = await services.coordinator.snapshot()
    }

    // MARK: Leaving

    /// The server deletes this phone and everything it reported; then the phone is emptied too.
    func leaveNeighborhood() async throws {
        guard let services else { return }
        _ = try await services.api.leaveHousehold()
        await wipeLocalData()
    }

    /// Removes the device secret, queue, sent log, home-network fingerprint, installation id and
    /// flags. `keepIntroSeen` is for a phone that did not choose to leave: it goes straight to
    /// the join screen.
    private func wipeLocalData(keepIntroSeen: Bool = false) async {
        guard let services else { return }
        await monitorStartTask?.value
        await services.coordinator.wipe()
        await services.sentLog.clear()
        services.credentials.clear()
        services.settings.resetForLeaving(keepIntroSeen: keepIntroSeen)
        enrolmentUnknown = false
        UIApplication.shared.unregisterForRemoteNotifications()
        household = nil
        neighborhood = nil
        status = nil
        openIncidents = []
        serviceError = nil
        setupError = nil
        joinNotice = nil
        pendingIncidentID = nil
        monitoring = await services.coordinator.snapshot()
        phase = services.settings.introSeen ? .join : .education
    }

    // MARK: Push

    func didReceivePushToken(_ token: Data) async {
        guard let services else { return }
        services.settings.pushToken = token.map { String(format: "%02x", $0) }.joined()
        await sendPushTokenIfNeeded()
    }

    private func sendPushTokenIfNeeded() async {
        guard let services else { return }
        let settings = services.settings
        guard let token = settings.pushToken, let installID = settings.installID, settings.registeredSignature != nil,
              services.api.isEnrolled else { return }
        let signature = "\(installID.uuidString)|\(token)"
        guard settings.pushTokenSentSignature != signature else { return }
        let request = RegisterPushTokenRequest(deviceID: installID, provider: .apns, token: token,
                                               apnsEnvironment: services.config.apnsEnvironment)
        if (try? await services.api.registerPushToken(request)) != nil {
            settings.pushTokenSentSignature = signature
        }
    }

    /// The resident tapped a notification. Payload keys are strings: incident_id, alert_level,
    /// kind, classification.
    func openIncident(fromNotification userInfo: [String: String]) {
        guard let text = userInfo["incident_id"], let id = UUID(uuidString: text) else { return }
        pendingIncidentID = id
    }

    // MARK: App lifecycle

    func appBecameActive() async {
        await bootstrap()
        guard let services else { return }
        await resolveUnknownEnrolment()
        await refreshPermissions()
        await services.coordinator.appBecameActive()
        await refresh()
    }

    func appEnteredBackground() async {
        guard !Self.isRunningTests else { return }
        guard let services else { return }
        BackgroundRefresh.schedule()
        // A short, documented extension of running time to finish uploads. iOS decides how long
        // (usually well under a minute); after that the app is suspended and monitoring pauses.
        let timeExtension = BackgroundTimeExtension()
        timeExtension.begin(name: "flush-events")
        await services.coordinator.prepareForBackground()
        timeExtension.end()
    }

    /// BGAppRefreshTask or a silent remote notification: one observation, a heartbeat, a flush.
    func runBackgroundRefresh() async {
        guard !Self.isRunningTests else { return }
        await bootstrap()
        guard let services else { return }
        await monitorStartTask?.value
        await services.coordinator.backgroundRefresh()
    }

    func refreshPermissions() async {
        guard let services else { return }
        let permission = await Self.currentNotificationPermission()
        notificationPermission = permission
        backgroundRefreshAvailable = UIApplication.shared.backgroundRefreshStatus == .available

        let settings = services.settings
        let authorized = permission == .authorized
        var changed = false
        if let previous = settings.notificationsAuthorized, previous != authorized { changed = true }
        if let previous = settings.backgroundRefreshAvailable, previous != backgroundRefreshAvailable { changed = true }
        settings.notificationsAuthorized = authorized
        settings.backgroundRefreshAvailable = backgroundRefreshAvailable
        if authorized {
            // Asking again on every launch is how the app learns about a refreshed token.
            UIApplication.shared.registerForRemoteNotifications()
        }
        if changed {
            await services.coordinator.permissionChanged()
        }
    }

    private nonisolated static func currentNotificationPermission() async -> NotificationPermission {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: return .authorized
        case .denied: return .denied
        case .notDetermined: return .notDetermined
        @unknown default: return .notDetermined
        }
    }

    // MARK: Power

    private func startBatteryMonitoring() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        reportBatteryState()
        batteryObserver = NotificationCenter.default.addObserver(
            forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in AppModel.shared.reportBatteryState() }
        }
    }

    private func reportBatteryState() {
        guard let services else { return }
        let state: PowerTracker.PowerState
        switch UIDevice.current.batteryState {
        case .charging, .full: state = .externalPower
        case .unplugged: state = .onBattery
        case .unknown: state = .unknown
        @unknown default: state = .unknown
        }
        Task { await services.coordinator.powerStateChanged(state) }
    }
}

/// An invitation the service accepted, with the neighborhood it belongs to.
struct VerifiedInvitation: Equatable, Sendable {
    let token: String
    let neighborhoodName: String
}

/// Wraps `beginBackgroundTask` / `endBackgroundTask` so the task is always ended exactly once.
@MainActor
final class BackgroundTimeExtension {
    private var identifier = UIBackgroundTaskIdentifier.invalid

    func begin(name: String) {
        identifier = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            // iOS calls the expiration handler on the main thread.
            MainActor.assumeIsolated { self?.end() }
        }
    }

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}

enum BackgroundRefresh {
    /// Must be listed in Info.plist under BGTaskSchedulerPermittedIdentifiers.
    static var identifier: String {
        (Bundle.main.bundleIdentifier ?? "app.feluda") + ".refresh"
    }

    /// Asks iOS for a background refresh no earlier than 15 minutes from now. iOS decides whether
    /// and when it happens; it can be hours, or never (Low Power Mode, Background App Refresh off,
    /// app rarely used, app force-quit).
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
