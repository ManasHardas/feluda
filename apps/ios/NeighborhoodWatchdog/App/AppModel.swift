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

/// App-wide state and the flows that span screens (onboarding, session, monitoring switch,
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
        case education
        case signIn
        case settingUp
        case joinHousehold
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
    private(set) var signedInEmail: String?
    private(set) var notificationPermission: NotificationPermission = .notDetermined
    private(set) var backgroundRefreshAvailable = true
    /// Incident to open because the resident tapped a notification.
    var pendingIncidentID: UUID?

    @ObservationIgnored private var bootstrapTask: Task<Void, Never>?
    @ObservationIgnored private var monitorStartTask: Task<Void, Never>?
    @ObservationIgnored private var batteryObserver: NSObjectProtocol?

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

        signedInEmail = await services.auth.signedInEmail
        let signedIn = await services.auth.isSignedIn

        if settings.consentGivenAt == nil {
            phase = .education
        } else if settings.onboardingComplete {
            // Monitoring keeps working without a connection and without a valid session; events
            // wait in the queue.
            phase = signedIn ? .main : .signIn
            startMonitoring(reason: .processLaunch)
            if signedIn {
                Task { await self.syncAfterLaunch() }
            }
        } else if !signedIn {
            phase = .signIn
        } else {
            await continueAfterSignIn()
        }
        monitoring = await services.coordinator.snapshot()
        await refreshPermissions()
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

    /// The resident read the explanation and gave explicit consent.
    func giveConsent() {
        guard let services else { return }
        services.settings.consentGivenAt = Date()
        phase = .signIn
    }

    func requestSignInCode(email: String) async throws {
        guard let services else { return }
        do {
            try await services.auth.requestCode(email: email)
        } catch {
            throw APIError.from(error)
        }
    }

    func verifySignInCode(email: String, code: String) async throws {
        guard let services else { return }
        do {
            try await services.auth.verify(email: email, code: code)
        } catch {
            throw APIError.from(error)
        }
        signedInEmail = await services.auth.signedInEmail
        await continueAfterSignIn()
    }

    /// After sign-in: is this email a member of a household already?
    func continueAfterSignIn() async {
        guard let services else { return }
        phase = .settingUp
        setupError = nil
        do {
            household = try await services.api.myHousehold()
        } catch let error as APIError where error.isNotAMember {
            phase = .joinHousehold
            return
        } catch let error as APIError where error == .sessionExpired || error == .notSignedIn {
            phase = .signIn
            return
        } catch {
            setupError = APIError.from(error).userMessage
            return
        }
        await completeMembership()
    }

    func joinHousehold(invitation: String, houseNumber: String, streetName: String, label: String, isp: Isp?,
                       privacyAcknowledged: Bool) async throws {
        guard let services else { return }
        guard let token = InvitationToken.parse(invitation) else {
            throw APIError.server(status: 404, code: "invitation_invalid", message: "", requestID: nil, retryAfter: nil)
        }
        let trimmedLabel = label.trimmingCharacters(in: .whitespacesAndNewlines)
        _ = try await services.api.verifyInvitation(token: token)
        household = try await services.api.joinHousehold(JoinHouseholdRequest(
            invitationToken: token,
            houseNumber: houseNumber.trimmingCharacters(in: .whitespacesAndNewlines),
            streetName: streetName.trimmingCharacters(in: .whitespacesAndNewlines),
            displayLabel: trimmedLabel.isEmpty ? nil : trimmedLabel,
            isp: isp,
            privacyAcknowledged: privacyAcknowledged))
        await completeMembership()
    }

    /// Membership is confirmed: register this install, then continue with the remaining steps.
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
        if household == nil {
            await continueAfterSignIn()
        } else {
            await completeMembership()
        }
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
        switch error {
        case .sessionExpired, .notSignedIn:
            serviceError = error.userMessage
            phase = .signIn
        default:
            if error.isNotAMember {
                household = nil
                phase = .joinHousehold
            }
            serviceError = error.userMessage
        }
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

    func leaveNeighborhood() async throws {
        guard let services else { return }
        _ = try await services.api.leaveHousehold()
        await wipeLocalData()
    }

    func deleteAccount() async throws {
        guard let services else { return }
        _ = try await services.api.deleteAccount()
        await wipeLocalData()
    }

    /// Signs out on this phone: the monitor stops, this install's registration is withdrawn, and
    /// tokens, queue, home-network fingerprint and install id are removed.
    func signOut() async {
        guard let services else { return }
        await monitorStartTask?.value
        await services.coordinator.stop(userInitiated: true)
        await flushBriefly()
        if let installID = services.settings.installID, services.settings.registeredSignature != nil {
            _ = try? await services.api.revokeDevice(id: installID)
        }
        await services.auth.signOut()
        await wipeLocalData()
    }

    private func flushBriefly() async {
        guard let services else { return }
        let reporter = services.reporter
        let flush = Task { _ = await reporter.flush(ignoreBackoff: true) }
        let timeout = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            flush.cancel()
        }
        await flush.value
        timeout.cancel()
    }

    private func wipeLocalData() async {
        guard let services else { return }
        await monitorStartTask?.value
        await services.coordinator.wipe()
        await services.sentLog.clear()
        await services.auth.clearLocal()
        services.settings.resetForSignOut()
        UIApplication.shared.unregisterForRemoteNotifications()
        household = nil
        neighborhood = nil
        status = nil
        openIncidents = []
        serviceError = nil
        signedInEmail = nil
        pendingIncidentID = nil
        monitoring = await services.coordinator.snapshot()
        phase = .education
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
              await services.auth.isSignedIn else { return }
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
        (Bundle.main.bundleIdentifier ?? "org.neighborhoodwatchdog.ios") + ".refresh"
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
