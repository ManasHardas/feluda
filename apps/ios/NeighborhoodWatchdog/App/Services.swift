import Foundation

/// Composition root: builds the long-lived objects and wires them together.
struct Services: Sendable {
    let config: AppConfig
    let settings: AppSettings
    let auth: AuthService
    let api: APIClient
    let queue: EventQueue
    let sentLog: SentLog
    let reporter: EventReporter
    let coordinator: MonitoringCoordinator
    let uploadPath: UploadPathObserver

    static var storageDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("Watchdog", isDirectory: true)
    }

    static func make(config: AppConfig, settings: AppSettings) -> Services {
        let keychain = KeychainStore(service: Bundle.main.bundleIdentifier ?? "com.manashardas.feluda")
        let interactive = URLSessionTransport(session: URLSession(configuration: WatchdogSessions.interactiveConfiguration()))
        let reporting = URLSessionTransport(session: URLSession(configuration: WatchdogSessions.reportingConfiguration()))
        let auth = AuthService(baseURL: config.authBaseURL, publicKey: config.authPublicKey, transport: interactive,
                               store: KeychainTokenStore(keychain: keychain))
        let api = APIClient(baseURL: config.apiBaseURL, transport: interactive, auth: auth)
        // Event uploads use the session that waits for connectivity and allows every network type.
        let reportingAPI = APIClient(baseURL: config.apiBaseURL, transport: reporting, auth: auth)

        let directory = storageDirectory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // Queue files are local working data, not user documents: keep them out of backups.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableDirectory = directory
        try? mutableDirectory.setResourceValues(values)

        let queue = EventQueue(directory: directory)
        let sentLog = SentLog(directory: directory)
        let uploadPath = UploadPathObserver()

        let reporter = EventReporter(
            queue: queue, sentLog: sentLog,
            dependencies: .init(
                submit: { try await reportingAPI.submitEvents($0) },
                deviceID: { settings.registeredSignature == nil ? nil : settings.installID },
                currentTransport: { uploadPath.currentTransport() },
                reRegisterDevice: { await DeviceRegistration.register(api: api, settings: settings, force: true) }
            ))

        let probe = ReachabilityProbe(
            healthURL: config.healthURL,
            fallbackURL: config.probeFallbackURL,
            anyPath: URLSessionTransport(session: URLSession(configuration: WatchdogSessions.probeConfiguration(allowCellular: true))),
            withoutCellular: URLSessionTransport(session: URLSession(configuration: WatchdogSessions.probeConfiguration(allowCellular: false))))

        let coordinator = MonitoringCoordinator(dependencies: .init(
            path: PathMonitorService(),
            probe: probe,
            queue: queue,
            reporter: reporter,
            stateStore: JSONFileStore(url: directory.appendingPathComponent("monitor-state.json")),
            fingerprintStore: KeychainFingerprintStore(keychain: keychain),
            osVersion: SystemInfo.osVersion,
            appVersion: SystemInfo.appVersion,
            systemUptime: { SystemInfo.uptime() },
            lowPowerMode: { ProcessInfo.processInfo.isLowPowerModeEnabled }
        ))

        return Services(config: config, settings: settings, auth: auth, api: api, queue: queue, sentLog: sentLog,
                        reporter: reporter, coordinator: coordinator, uploadPath: uploadPath)
    }

    static func clearKeychain() {
        let keychain = KeychainStore(service: Bundle.main.bundleIdentifier ?? "com.manashardas.feluda")
        KeychainTokenStore(keychain: keychain).clear()
        KeychainFingerprintStore(keychain: keychain).clear()
    }
}

enum DeviceRegistration {
    static func signature(settings: AppSettings) -> String {
        "\(settings.installIDCreatingIfNeeded().uuidString)|\(SystemInfo.osVersion)|\(SystemInfo.appVersion)"
    }

    /// Registers this install with the backend (random install UUID plus honest capability flags).
    /// Returns true when the backend knows the device afterwards.
    @discardableResult
    static func register(api: APIClient, settings: AppSettings, force: Bool) async -> Bool {
        (try? await registerThrowing(api: api, settings: settings, force: force)) != nil
    }

    static func registerThrowing(api: APIClient, settings: AppSettings, force: Bool) async throws {
        let signature = signature(settings: settings)
        if !force, settings.registeredSignature == signature { return }
        let request = RegisterDeviceRequest(
            deviceID: settings.installIDCreatingIfNeeded(), platform: .ios,
            osVersion: SystemInfo.osVersion, appVersion: SystemInfo.appVersion, capabilities: .iOS)
        _ = try await api.registerDevice(request)
        settings.registeredSignature = signature
    }
}
