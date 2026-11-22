import SwiftUI

/// Onboarding step 5: explain why notifications are needed, then ask.
struct NotificationPermissionView: View {
    @Environment(AppModel.self) private var model
    @State private var isBusy = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Image(systemName: "bell.badge")
                        .font(.system(size: 48))
                        .foregroundStyle(Color.accentColor)
                        .accessibilityHidden(true)
                    Text("Alerts reach you as notifications")
                        .font(.title2.bold())
                    Text("When several nearby households report a connectivity interruption at about the same time, the service sends an alert to everyone in your neighborhood group. Without notifications you only see alerts when you open the app.")
                    Text("You can set quiet hours and choose which alerts you get under Settings. Delivery of notifications depends on Apple's service and your connection, so an alert can arrive late or not at all.")
                        .foregroundStyle(.secondary)
                    PrimaryButton(title: "Allow notifications", isBusy: isBusy) {
                        isBusy = true
                        Task {
                            await model.requestNotificationPermission()
                            isBusy = false
                        }
                    }
                    Button("Not now") {
                        Task { await model.finishNotificationStep() }
                    }
                    .frame(maxWidth: .infinity)
                }
                .padding()
            }
            .navigationTitle("Notifications")
        }
    }
}

/// Onboarding step 6: mark the Wi-Fi the phone is on right now as the home network. Skippable.
struct HomeNetworkView: View {
    @Environment(AppModel.self) private var model
    @State private var isBusy = false
    @State private var message: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Image(systemName: "house")
                        .font(.system(size: 48))
                        .foregroundStyle(Color.accentColor)
                        .accessibilityHidden(true)
                    Text("Are you on your home Wi-Fi now?")
                        .font(.title2.bold())
                    Text("A phone that leaves the house loses Wi-Fi too. So that only the loss of your home Wi-Fi counts, the app can remember the network you are on right now.")
                    Text("It stores a scrambled fingerprint of the router's network address in this phone's Keychain. The fingerprint never leaves the phone; reports only say whether the Wi-Fi was your home network: yes, no or unknown. The app does not read the network name and does not use your location.")
                        .foregroundStyle(.secondary)

                    InfoRow(label: "Wi-Fi right now",
                            value: DisplayFormat.yesNoUnknown(model.monitoring.onWifiNow, yes: "Connected", no: "Not connected", unknown: "Checking…"),
                            symbol: "wifi")

                    if let message {
                        ErrorBanner(message: message)
                    }

                    PrimaryButton(title: "Yes, this is my home Wi-Fi", isBusy: isBusy) {
                        Task { await confirm() }
                    }
                    Button("Skip for now") {
                        Task { await model.finishOnboarding() }
                    }
                    .frame(maxWidth: .infinity)
                    Text("If you skip this, reports say the home network is unknown. You can set it later under Settings.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .padding()
            }
            .navigationTitle("Home network")
        }
    }

    private func confirm() async {
        isBusy = true
        defer { isBusy = false }
        message = HomeNetworkMessages.text(for: await model.confirmHomeNetwork())
    }
}

enum HomeNetworkMessages {
    static func text(for result: MonitoringCoordinator.HomeNetworkResult) -> String? {
        switch result {
        case .stored:
            return nil
        case .notOnWifi:
            return "This phone is not connected to Wi-Fi right now. Connect to your home Wi-Fi and try again, or skip."
        case .gatewaysUnavailable:
            return "iOS did not provide the router address for this Wi-Fi, so the network cannot be remembered right now. You can try again later under Settings."
        case .storageFailed:
            return "The fingerprint could not be saved to the Keychain. You can try again later under Settings."
        }
    }
}
