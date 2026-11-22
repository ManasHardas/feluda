import SwiftUI

@main
struct NeighborhoodWatchdogApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = AppModel.shared

    var body: some Scene {
        WindowGroup {
            if AppModel.isRunningTests {
                Color.clear
            } else {
                RootView()
                    .environment(model)
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            guard !AppModel.isRunningTests else { return }
            switch newPhase {
            case .active:
                Task { await model.appBecameActive() }
            case .background:
                Task { await model.appEnteredBackground() }
            default:
                break
            }
        }
    }
}
