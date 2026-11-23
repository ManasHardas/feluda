import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var isTogglingMonitoring = false
    @State private var confirmLeave = false
    @State private var isWorking = false
    @State private var actionError: String?
    @State private var homeMessage: String?

    var body: some View {
        Form {
            monitoringSection
            notificationSection
            homeNetworkSection
            householdSection
            aboutSection
            leavingSection
        }
        .navigationTitle("Settings")
        .disabled(isWorking)
        .overlay { if isWorking { ProgressView().controlSize(.large) } }
    }

    private var monitoringSection: some View {
        Section {
            Toggle("Monitor this phone's connection", isOn: Binding(
                get: { model.monitoring.userEnabled },
                set: { newValue in
                    isTogglingMonitoring = true
                    Task {
                        await model.setMonitoringEnabled(newValue)
                        isTogglingMonitoring = false
                    }
                }))
                .disabled(isTogglingMonitoring)
            InfoRow(label: "Status", value: model.monitoring.state.displayName)
            InfoRow(label: "Last successful monitoring event", value: DisplayFormat.dateTime(model.monitoring.lastObservationAt))
            InfoRow(label: "Last successful report", value: DisplayFormat.dateTime(model.monitoring.report.lastSuccessAt))
            InfoRow(label: "Events waiting to be sent", value: "\(model.monitoring.report.queuedCount)")
        } header: {
            Text("Monitoring")
        } footer: {
            Text("When this is off, the app tells the service that monitoring is paused and stops watching. Your household then shows as not monitored, which is not the same as fine.")
        }
    }

    private var notificationSection: some View {
        Section("Notifications") {
            InfoRow(label: "Permission", value: model.notificationPermission.label)
            NavigationLink("Notification preferences") { NotificationPreferencesView() }
        }
    }

    private var homeNetworkSection: some View {
        Section {
            InfoRow(label: "Home network", value: model.monitoring.hasHomeFingerprint ? "Remembered on this phone" : "Not set")
            if model.monitoring.hasHomeFingerprint {
                InfoRow(label: "Current Wi-Fi is home",
                        value: DisplayFormat.yesNoUnknown(model.monitoring.homeNetworkMatch, yes: "Yes", no: "No"))
            }
            Button(model.monitoring.hasHomeFingerprint ? "Replace with the Wi-Fi I am on now" : "Remember the Wi-Fi I am on now") {
                Task {
                    guard let coordinator = model.services?.coordinator else { return }
                    homeMessage = HomeNetworkMessages.text(for: await coordinator.rememberCurrentNetworkAsHome()) ?? "Saved."
                }
            }
            .disabled(!model.monitoring.userEnabled)
            if model.monitoring.hasHomeFingerprint {
                Button("Forget home network", role: .destructive) {
                    Task {
                        await model.services?.coordinator.forgetHomeNetwork()
                        homeMessage = "Removed from this phone."
                    }
                }
            }
            if let homeMessage {
                Text(homeMessage).font(.footnote).foregroundStyle(.secondary)
            }
        } header: {
            Text("Home Wi-Fi")
        } footer: {
            Text("A scrambled fingerprint of your router's network address, kept only in this phone's Keychain. If your router only has a common address such as 192.168.1.1, another network with the same address can look like home.")
        }
    }

    private var householdSection: some View {
        Section("Household and device") {
            NavigationLink("Household membership") { HouseholdView() }
            NavigationLink("Device registration") { DevicesView() }
        }
    }

    private var aboutSection: some View {
        Section("About") {
            NavigationLink("Privacy information") { PrivacyInfoView() }
            NavigationLink("Transparency") { TransparencyView() }
            InfoRow(label: "App version", value: SystemInfo.appVersion)
        }
    }

    private var leavingSection: some View {
        Section {
            if let actionError {
                ErrorBanner(message: actionError)
            }
            Button("Leave neighborhood", role: .destructive) { confirmLeave = true }
                .confirmationDialog("Leave the neighborhood?", isPresented: $confirmLeave, titleVisibility: .visible) {
                    Button("Leave neighborhood", role: .destructive) { run { try await model.leaveNeighborhood() } }
                } message: {
                    Text("The service deletes this phone and everything it reported. You stop receiving alerts, and everything the app stored on this phone is removed. To join again you need an invitation link.")
                }
        } header: {
            Text("Leaving")
        } footer: {
            Text("Leaving deletes what this phone reported. There is no account, so nothing else remains.")
        }
    }

    private func run(_ action: @escaping @MainActor () async throws -> Void) {
        isWorking = true
        actionError = nil
        Task {
            do {
                try await action()
            } catch {
                actionError = APIError.from(error).userMessage
            }
            isWorking = false
        }
    }
}

struct PrivacyInfoView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                ForEach(EducationContent.topics) { topic in
                    EducationTopicView(topic: topic)
                }
                if let url = model.services?.config.privacyURL {
                    Button("Read the full privacy policy") { openURL(url) }
                }
            }
            .padding()
        }
        .navigationTitle("Privacy")
    }
}
