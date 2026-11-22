import SwiftUI

enum AppRoute: Hashable {
    case incidents
    case incident(UUID)
    case settings
    case gaps
}

/// The main screen: household status, this phone's connection, last observation, notification
/// permission and the neighborhood status. Deliberately one simple list.
struct MainView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var path: [AppRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                householdSection
                phoneSection
                notificationSection
                neighborhoodSection
            }
            .navigationTitle("Watchdog")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        path.append(.settings)
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                    }
                }
            }
            .refreshable { await model.refresh() }
            .navigationDestination(for: AppRoute.self) { route in
                switch route {
                case .incidents: IncidentListView()
                case let .incident(id): IncidentDetailView(incidentID: id)
                case .settings: SettingsView()
                case .gaps: MonitoringGapsView()
                }
            }
        }
        .onChange(of: model.pendingIncidentID, initial: true) { _, incidentID in
            guard let incidentID else { return }
            path = [.incident(incidentID)]
            model.pendingIncidentID = nil
        }
    }

    // MARK: Sections

    @ViewBuilder private var householdSection: some View {
        Section {
            if !model.monitoring.userEnabled {
                StatusCard(color: .grey, title: "Your household",
                           explanation: "Monitoring is switched off on this phone, so it is not watching or reporting. Grey means not known; it does not mean everything is fine. Switch monitoring on under Settings.")
            } else if let status = model.status {
                StatusCard(color: status.statusColor, title: "Your household", explanation: status.statusExplanation)
                InfoRow(label: "Monitoring", value: status.householdMonitoringStatus.displayName, symbol: "waveform.path.ecg")
            } else {
                StatusCard(color: .grey, title: "Your household",
                           explanation: model.serviceError == nil
                               ? "Loading the household status from the service…"
                               : "The app cannot reach the Neighborhood Watchdog service, so the household status is not known. Below is only what this phone sees itself.")
            }
            if let error = model.serviceError {
                ErrorBanner(message: error)
            }
        }
    }

    @ViewBuilder private var phoneSection: some View {
        let monitoring = model.monitoring
        Section {
            InfoRow(label: "Wi-Fi",
                    value: DisplayFormat.yesNoUnknown(monitoring.wifiAssociated, yes: "Connected", no: "Not connected"),
                    symbol: "wifi", tint: tint(monitoring.wifiAssociated))
            InfoRow(label: "Internet",
                    value: DisplayFormat.yesNoUnknown(monitoring.internetReachable, yes: "Reachable (test request answered)",
                                                      no: "Not reachable (test request failed)", unknown: "Not checked yet"),
                    symbol: "globe", tint: tint(monitoring.internetReachable))
            InfoRow(label: "Cellular data",
                    value: DisplayFormat.yesNoUnknown(monitoring.cellularAvailable, yes: "Available", no: "Not available"),
                    symbol: "antenna.radiowaves.left.and.right", tint: tint(monitoring.cellularAvailable))
            InfoRow(label: "This phone's state", value: monitoring.state.displayName, symbol: "iphone")
            InfoRow(label: "Last successful observation", value: DisplayFormat.dateTime(monitoring.lastObservationAt), symbol: "clock")
            NavigationLink(value: AppRoute.gaps) {
                InfoRow(label: "Known monitoring gaps", value: "\(monitoring.gaps.count)", symbol: "pause.circle")
            }
        } header: {
            Text("This phone")
        } footer: {
            Text("Monitoring on iPhone is not continuous. iOS pauses this app when you are not using it, and the app cannot see connection changes while paused. A connected Wi-Fi alone does not prove the internet works, so the app also sends a small test request.")
        }
    }

    @ViewBuilder private var notificationSection: some View {
        Section {
            InfoRow(label: "Notifications", value: model.notificationPermission.label, symbol: "bell",
                    tint: model.notificationPermission == .authorized ? .green : .orange)
            if model.notificationPermission != .authorized {
                Text("Without notifications you only see alerts when you open the app.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if model.notificationPermission == .denied {
                    Button("Open iPhone Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    }
                } else {
                    Button("Allow notifications") {
                        Task { await model.requestNotificationPermission() }
                    }
                }
            }
            if !model.backgroundRefreshAvailable {
                Label("Background App Refresh is off for this app, so iOS will not wake it in the background. Monitoring gaps will be longer.",
                      systemImage: "exclamationmark.triangle")
                    .font(.footnote)
            }
        } header: {
            Text("Permissions")
        }
    }

    @ViewBuilder private var neighborhoodSection: some View {
        Section {
            if let neighborhood = model.neighborhood {
                StatusCard(color: neighborhood.statusColor, title: neighborhood.name, explanation: neighborhood.statusExplanation)
                InfoRow(label: "Households confirmed active",
                        value: "\(neighborhood.confirmedActiveHouseholds) of \(neighborhood.participatingHouseholds)", symbol: "house")
                if neighborhood.confirmedActiveHouseholds < neighborhood.participatingHouseholds {
                    Text("The other households have not reported recently. Their state is unknown, not confirmed fine.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } else {
                StatusCard(color: .grey, title: "Neighborhood",
                           explanation: model.serviceError == nil ? "Loading the neighborhood status…"
                               : "The neighborhood status is not known because the service cannot be reached.")
            }
            NavigationLink(value: AppRoute.incidents) {
                InfoRow(label: "Open incidents", value: model.neighborhood == nil ? "Not known" : "\(model.openIncidents.count)",
                        symbol: "list.bullet.rectangle")
            }
            ForEach(model.openIncidents.prefix(3)) { incident in
                NavigationLink(value: AppRoute.incident(incident.incidentID)) {
                    IncidentRow(incident: incident)
                }
            }
        } header: {
            Text("Neighborhood")
        } footer: {
            if let refreshed = model.lastRefreshAt {
                Text("Updated \(DisplayFormat.dateTime(refreshed)). Pull down to refresh.")
            }
        }
    }

    private func tint(_ value: Bool?) -> Color {
        switch value {
        case .some(true): return .green
        case .some(false): return .orange
        case .none: return .gray
        }
    }
}

struct MonitoringGapsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            Section {
                if model.monitoring.gaps.isEmpty {
                    Text("No gaps longer than two minutes have been recorded on this phone yet.")
                        .foregroundStyle(.secondary)
                }
                ForEach(model.monitoring.gaps) { gap in
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(DisplayFormat.dateTime(gap.startedAt)) to \(DisplayFormat.dateTime(gap.endedAt))")
                            .font(.subheadline.weight(.semibold))
                        Text("Not watching for \(DisplayFormat.duration(gap.duration)). \(gap.reason.displayName).")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
            } footer: {
                Text("During a gap the app was not running and saw nothing. Anything that changed in that time is reported afterwards as \"found on resume\", with the resume time, because the real time is not known. The most recent \(MonitoringCoordinator.maxStoredGaps) gaps are kept.")
            }
        }
        .navigationTitle("Monitoring gaps")
    }
}
