import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            switch model.phase {
            case .loading:
                ProgressView("Starting…")
            case let .configurationError(detail):
                ContentUnavailableView {
                    Label("This build is not configured", systemImage: "wrench.and.screwdriver")
                } description: {
                    Text(detail)
                }
            case .education:
                EducationView()
            case .signIn:
                SignInView()
            case .settingUp:
                SettingUpView()
            case .joinHousehold:
                JoinHouseholdView()
            case .notifications:
                NotificationPermissionView()
            case .homeNetwork:
                HomeNetworkView()
            case .main:
                MainView()
            }
        }
        .task { await model.bootstrap() }
    }
}

struct SettingUpView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 20) {
            if let error = model.setupError {
                ContentUnavailableView {
                    Label("Could not finish setting up", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(error)
                } actions: {
                    Button("Try again") { Task { await model.retrySetup() } }
                        .buttonStyle(.borderedProminent)
                    Button("Sign out") { Task { await model.signOut() } }
                }
            } else {
                ProgressView("Setting up this phone…")
            }
        }
        .padding()
    }
}
