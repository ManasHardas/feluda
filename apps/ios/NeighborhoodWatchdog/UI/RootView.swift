import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            if model.updateRequired {
                // The server refuses this version: nothing else is shown until the update.
                UpdateRequiredView()
            } else {
                phaseView
                    .safeAreaInset(edge: .top, spacing: 0) {
                        if model.updateAvailable, !model.updateNoticeDismissed {
                            UpdateAvailableNotice()
                        }
                    }
            }
        }
        .task { await model.bootstrap() }
        // The web join page opens feluda://join#t=<token>.
        .onOpenURL { model.handleIncomingURL($0) }
    }

    @ViewBuilder private var phaseView: some View {
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
            case .join:
                JoinHouseholdView()
            case .settingUp:
                SettingUpView()
            case .notifications:
                NotificationPermissionView()
            case .homeNetwork:
                HomeNetworkView()
            case .main:
                MainView()
            }
        }
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
                }
            } else {
                ProgressView("Setting up this phone…")
            }
        }
        .padding()
    }
}
