import Observation
import SwiftUI

@MainActor
@Observable
final class NotificationPreferencesViewModel {
    var preferences = NotificationPreferences()
    var loaded = false
    var isBusy = false
    var message: String?
    var errorMessage: String?

    var quietStart: Date {
        get { Self.date(from: preferences.quietHoursStart) }
        set { preferences.quietHoursStart = Self.time(from: newValue) }
    }

    var quietEnd: Date {
        get { Self.date(from: preferences.quietHoursEnd) }
        set { preferences.quietHoursEnd = Self.time(from: newValue) }
    }

    static func date(from time: TimeOfDay) -> Date {
        Calendar.current.date(bySettingHour: time.hour, minute: time.minute, second: 0, of: Date()) ?? Date()
    }

    static func time(from date: Date) -> TimeOfDay {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        return TimeOfDay(hour: parts.hour ?? 0, minute: parts.minute ?? 0)
    }

    func load(model: AppModel) async {
        guard let api = model.services?.api else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            preferences = try await api.notificationPreferences()
            loaded = true
            errorMessage = nil
        } catch {
            errorMessage = APIError.from(error).userMessage
        }
    }

    func save(model: AppModel) async {
        guard let api = model.services?.api else { return }
        isBusy = true
        message = nil
        defer { isBusy = false }
        var toSend = preferences
        // Quiet hours are wall-clock times in the phone's current time zone.
        toSend.timezone = TimeZone.current.identifier
        do {
            preferences = try await api.updateNotificationPreferences(toSend)
            errorMessage = nil
            message = "Saved."
        } catch {
            errorMessage = APIError.from(error).userMessage
        }
    }
}

struct NotificationPreferencesView: View {
    @Environment(AppModel.self) private var model
    @State private var viewModel = NotificationPreferencesViewModel()

    var body: some View {
        @Bindable var viewModel = viewModel
        Form {
            if let error = viewModel.errorMessage {
                Section { ErrorBanner(message: error) }
            }
            if viewModel.loaded {
                Section {
                    Toggle("Alerts about my household", isOn: $viewModel.preferences.householdAlerts)
                    Toggle("Alerts about the neighborhood", isOn: $viewModel.preferences.neighborhoodAlerts)
                } header: {
                    Text("Which alerts")
                }
                Section {
                    Toggle("Quiet hours", isOn: $viewModel.preferences.quietHoursEnabled)
                    if viewModel.preferences.quietHoursEnabled {
                        DatePicker("From", selection: $viewModel.quietStart, displayedComponents: .hourAndMinute)
                        DatePicker("Until", selection: $viewModel.quietEnd, displayedComponents: .hourAndMinute)
                        InfoRow(label: "Time zone", value: TimeZone.current.identifier)
                    }
                } header: {
                    Text("Quiet hours")
                } footer: {
                    Text("During quiet hours the service holds back notifications according to its rules. The alert is still visible in the app.")
                }
                Section {
                    PrimaryButton(title: "Save", isBusy: viewModel.isBusy) {
                        Task { await viewModel.save(model: model) }
                    }
                    if let message = viewModel.message {
                        Text(message).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            } else if viewModel.isBusy {
                Section { ProgressView("Loading…") }
            } else {
                Section { Button("Try again") { Task { await viewModel.load(model: model) } } }
            }
        }
        .navigationTitle("Notification preferences")
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.load(model: model) }
    }
}

/// The resident's own household. The address shown here is their own and is not shown to others.
struct HouseholdView: View {
    @Environment(AppModel.self) private var model
    @State private var label = ""
    @State private var isp: Isp = .unknown
    @State private var isBusy = false
    @State private var message: String?
    @State private var errorMessage: String?

    var body: some View {
        Form {
            if let household = model.household {
                Section {
                    InfoRow(label: "Membership", value: household.membershipStatus.rawValue.capitalized)
                    InfoRow(label: "Your role", value: household.role.rawValue.capitalized)
                    InfoRow(label: "Shown to neighbors as", value: household.anonymousReference)
                    InfoRow(label: "Registered address", value: "\(household.houseNumber) \(household.streetName)")
                    InfoRow(label: "Residents", value: "\(household.memberCount)")
                    InfoRow(label: "Phones", value: "\(household.deviceCount)")
                    if let neighborhood = model.neighborhood {
                        InfoRow(label: "Neighborhood", value: neighborhood.name)
                    }
                } footer: {
                    Text("Your address is only visible to you and the neighborhood administrator. Incidents never show addresses.")
                }
                Section {
                    TextField("Label (optional)", text: $label)
                    Picker("Internet provider", selection: $isp) {
                        ForEach(Isp.allCases, id: \.self) { provider in
                            Text(provider.displayName).tag(provider)
                        }
                    }
                    PrimaryButton(title: "Save", isBusy: isBusy) { Task { await save() } }
                    if let message { Text(message).font(.footnote).foregroundStyle(.secondary) }
                    if let errorMessage { ErrorBanner(message: errorMessage) }
                } header: {
                    Text("Details you can change")
                }
            } else {
                Section {
                    Text("Household details are not available because the service cannot be reached.")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Household")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await model.refresh()
            label = model.household?.displayLabel ?? ""
            isp = model.household?.isp ?? .unknown
        }
    }

    private func save() async {
        guard let api = model.services?.api else { return }
        isBusy = true
        message = nil
        errorMessage = nil
        defer { isBusy = false }
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            _ = try await api.updateHousehold(UpdateHouseholdRequest(displayLabel: trimmed.isEmpty ? nil : trimmed, isp: isp))
            await model.refresh()
            message = "Saved."
        } catch {
            errorMessage = APIError.from(error).userMessage
        }
    }
}

@MainActor
@Observable
final class DevicesViewModel {
    var devices: [DeviceOut] = []
    var errorMessage: String?
    var isLoading = false

    func load(model: AppModel) async {
        guard let api = model.services?.api else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            devices = try await api.devices()
            errorMessage = nil
        } catch {
            errorMessage = APIError.from(error).userMessage
        }
    }

    func revoke(_ device: DeviceOut, model: AppModel) async {
        guard let api = model.services?.api else { return }
        do {
            _ = try await api.revokeDevice(id: device.deviceID)
            await load(model: model)
        } catch {
            errorMessage = APIError.from(error).userMessage
        }
    }
}

struct DevicesView: View {
    @Environment(AppModel.self) private var model
    @State private var viewModel = DevicesViewModel()

    var body: some View {
        let installID = model.services?.settings.installID
        List {
            Section {
                InfoRow(label: "Registered with the service", value: model.services?.settings.registeredSignature == nil ? "No" : "Yes")
                InfoRow(label: "Installation id", value: installID?.uuidString.lowercased() ?? "None")
                InfoRow(label: "iOS version", value: SystemInfo.osVersion)
                InfoRow(label: "App version", value: SystemInfo.appVersion)
            } header: {
                Text("This phone")
            } footer: {
                Text("The installation id is a random number created by the app. It is not a hardware identifier and is removed when you sign out or delete the app.")
            }
            if let error = viewModel.errorMessage {
                Section { ErrorBanner(message: error) }
            }
            Section("Phones in your household") {
                if viewModel.devices.isEmpty {
                    Text(viewModel.isLoading ? "Loading…" : "None listed").foregroundStyle(.secondary)
                }
                ForEach(viewModel.devices) { device in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(device.deviceID == installID ? "This phone" : (device.platform == .ios ? "iPhone" : "Android phone"))
                            .font(.subheadline.weight(.semibold))
                        Text("\(device.status.rawValue.capitalized) · monitoring \(device.monitoringEnabled ? "on" : "off") · last seen \(DisplayFormat.dateTime(device.lastSeenAt))")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .swipeActions {
                        if device.deviceID != installID, device.status == .active {
                            Button("Remove", role: .destructive) {
                                Task { await viewModel.revoke(device, model: model) }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Device registration")
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.load(model: model) }
    }
}
