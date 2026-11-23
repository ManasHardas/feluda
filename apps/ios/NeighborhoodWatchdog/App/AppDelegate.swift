import BackgroundTasks
import UIKit
import UserNotifications

/// Push notifications and the one background mechanism the app uses (BGAppRefreshTask, plus
/// ordinary remote notifications). No location, audio, VoIP or other background mode is used to
/// stay alive: iOS decides when this app runs in the background.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        guard !AppModel.isRunningTests else { return true }
        UNUserNotificationCenter.current().delegate = self
        // Registration has to happen before the app finishes launching.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: BackgroundRefresh.identifier, using: nil) { task in
            AppDelegate.run(task)
        }
        // Also covers launches in the background, where no scene becomes active.
        Task { await AppModel.shared.bootstrap() }
        return true
    }

    // MARK: Remote notifications

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { await AppModel.shared.didReceivePushToken(deviceToken) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // No token (for example in the simulator, or without a provisioning profile that has the
        // Push Notifications capability). The app works without push; alerts are then only
        // visible when the app is opened.
    }

    /// A remote notification arrived while the app was running or was woken for it. Used as one
    /// more opportunity to observe and upload. Delivery of such notifications is not guaranteed.
    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        await AppModel.shared.runBackgroundRefresh()
        await AppModel.shared.refresh()
        return .newData
    }

    // MARK: UNUserNotificationCenterDelegate

    /// Show alerts while the app is open too. The interruption level (for example time-sensitive)
    /// is whatever the server put in the payload; the app does not raise it.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        await AppModel.shared.refresh()
        return [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse) async {
        let payload = Self.stringPayload(response.notification.request.content.userInfo)
        await AppModel.shared.openIncident(fromNotification: payload)
    }

    /// Custom payload keys are strings. They are read from the top level of the payload and from
    /// a nested "data" object, whichever the server uses.
    nonisolated static func stringPayload(_ userInfo: [AnyHashable: Any]) -> [String: String] {
        var result: [String: String] = [:]
        if let nested = userInfo["data"] as? [AnyHashable: Any] {
            for (key, value) in nested {
                if let key = key as? String, let value = value as? String { result[key] = value }
            }
        }
        for (key, value) in userInfo {
            if let key = key as? String, let value = value as? String { result[key] = value }
        }
        return result
    }

    // MARK: Background refresh

    private final class Completion: @unchecked Sendable {
        private let lock = NSLock()
        private var task: BGTask?

        init(_ task: BGTask) { self.task = task }

        func finish(success: Bool) {
            lock.lock()
            let pending = task
            task = nil
            lock.unlock()
            pending?.setTaskCompleted(success: success)
        }
    }

    nonisolated private static func run(_ task: BGTask) {
        BackgroundRefresh.schedule()
        let completion = Completion(task)
        let work = Task { @MainActor in
            await AppModel.shared.runBackgroundRefresh()
        }
        task.expirationHandler = {
            work.cancel()
            completion.finish(success: false)
        }
        Task {
            await work.value
            completion.finish(success: true)
        }
    }
}
