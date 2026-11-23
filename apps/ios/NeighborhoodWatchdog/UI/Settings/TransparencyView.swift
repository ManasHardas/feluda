import Observation
import SwiftUI

/// What the app is, what it sends and what it is allowed to do, in one place the resident can check.
struct TransparencyView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    var body: some View {
        let config = model.services?.config
        List {
            Section("Source and version") {
                linkRow("Source code", url: config?.sourceRepoURL)
                InfoRow(label: "App version", value: SystemInfo.marketingVersion)
                InfoRow(label: "Build", value: SystemInfo.buildNumber)
                linkRow("Privacy policy", url: config?.privacyURL)
            }

            Section {
                BulletList(items: [
                    "Your email address, to sign in.",
                    "House number and street, once, when you join with an invitation.",
                    "A random installation id, the iOS version and the app version.",
                    "Connectivity events: kind of change, time, connection state, and the diagnostic values (Wi-Fi connected, internet reachable, cellular available, default network type, home network yes/no/unknown, charger unplugged, Low Power Mode, monitoring gaps).",
                    "The notification token Apple gives this app, so alerts can be delivered.",
                    "Your notification preferences.",
                ])
                Text("When the service does not answer the reachability test, the app also sends one plain request to a second address (\(config?.probeFallbackURL?.host ?? "not configured")). That request carries no account or device information, but like any internet request it reveals the phone's IP address to that server.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                NavigationLink("See the events on this phone") { EventLogView() }
            } header: {
                Text("Data sent to the service")
            } footer: {
                Text("Never sent: location, Wi-Fi names, passwords, router addresses, browsing activity, contacts, photos, hardware identifiers. Values the phone could not measure are sent as empty, never as a guess.")
            }

            Section {
                InfoRow(label: "Notifications", value: model.notificationPermission.label)
                InfoRow(label: "Background App Refresh", value: model.backgroundRefreshAvailable ? "Available" : "Off or restricted")
                InfoRow(label: "Cellular data", value: "Allowed for reports (controlled in iPhone Settings)")
                InfoRow(label: "Location", value: "Not requested")
                InfoRow(label: "Local Network", value: "Not requested")
                InfoRow(label: "Bluetooth, camera, microphone, contacts, photos", value: "Not requested")
            } header: {
                Text("Permissions right now")
            }

            Section {
                InfoRow(label: "Monitoring", value: model.monitoring.state.displayName)
                InfoRow(label: "Last successful observation", value: DisplayFormat.dateTime(model.monitoring.lastObservationAt))
                InfoRow(label: "Last successful report", value: DisplayFormat.dateTime(model.monitoring.report.lastSuccessAt))
                if let via = model.monitoring.report.lastReportedVia {
                    InfoRow(label: "Network in use at that report", value: via.displayName)
                }
                InfoRow(label: "Events waiting to be sent", value: "\(model.monitoring.report.queuedCount)")
                if let error = model.monitoring.report.lastError {
                    InfoRow(label: "Last upload problem", value: error)
                }
                if let next = model.monitoring.report.nextRetryAt {
                    InfoRow(label: "Next upload attempt", value: DisplayFormat.dateTime(next))
                }
                if let queueError = model.monitoring.queueError {
                    ErrorBanner(message: queueError)
                }
            } header: {
                Text("Monitoring and reporting")
            }

            Section {
                BulletList(items: [
                    "Monitoring is not continuous. iOS pauses the app in the background and wakes it only when iOS chooses to. Gaps are reported, not hidden.",
                    "If you swipe the app away in the app switcher, iOS stops waking it until you open it again.",
                    "iOS does not let apps read Wi-Fi signal strength, the Wi-Fi band or whether Wi-Fi is switched off. Those values are always sent as empty.",
                    "The app cannot force a report to use cellular data. It allows cellular and iOS chooses the connection.",
                    "The app cannot tell why a connection dropped. It reports anomalies; it does not identify interference.",
                    "Notification delivery depends on Apple's service and is not assured.",
                ])
            } header: {
                Text("Known limits on iPhone")
            }

            Section("Security") {
                linkRow("Security review summary", url: config?.securityReviewURL)
                if let contact = config?.securityContact {
                    InfoRow(label: "Report a security issue", value: contact)
                } else {
                    InfoRow(label: "Report a security issue", value: "Not configured in this build")
                }
            }
        }
        .navigationTitle("Transparency")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder private func linkRow(_ title: String, url: URL?) -> some View {
        if let url {
            Button {
                openURL(url)
            } label: {
                InfoRow(label: title, value: url.host ?? url.absoluteString, symbol: "arrow.up.right.square")
            }
        } else {
            InfoRow(label: title, value: "Not configured in this build")
        }
    }
}

@MainActor
@Observable
final class EventLogViewModel {
    var queued: [EventQueue.Entry] = []
    var sent: [SentRecord] = []

    func load(model: AppModel) async {
        guard let services = model.services else { return }
        queued = await services.queue.entries()
        sent = await services.sentLog.records()
    }

    static func json(for event: ConnectivityEvent) -> String {
        guard let data = try? WatchdogJSON.prettyEncoder().encode(event) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }
}

/// Viewer of the local queue and of the last 50 sent events, exactly as they are reported.
struct EventLogView: View {
    @Environment(AppModel.self) private var model
    @State private var viewModel = EventLogViewModel()

    var body: some View {
        List {
            Section {
                if viewModel.queued.isEmpty {
                    Text("Nothing is waiting.").foregroundStyle(.secondary)
                }
                ForEach(viewModel.queued) { entry in
                    NavigationLink {
                        EventJSONView(event: entry.event, note: "Waiting to be sent. Upload attempts so far: \(entry.attempts).")
                    } label: {
                        row(entry.event, detail: "Waiting · attempts: \(entry.attempts)")
                    }
                }
            } header: {
                Text("Waiting to be sent (\(viewModel.queued.count))")
            }
            Section {
                if viewModel.sent.isEmpty {
                    Text("Nothing has been sent from this phone yet.").foregroundStyle(.secondary)
                }
                ForEach(viewModel.sent) { record in
                    NavigationLink {
                        EventJSONView(event: record.event, note: sentNote(record))
                    } label: {
                        row(record.event, detail: "\(record.result.rawValue.capitalized) · sent \(DisplayFormat.dateTime(record.sentAt))")
                    }
                }
            } header: {
                Text("Last \(SentLog.capacity) sent")
            } footer: {
                Text("\"Happened\" is when the phone saw the change. \"Sent\" is when it was uploaded; the two differ when there was no connection.")
            }
        }
        .navigationTitle("Events on this phone")
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.load(model: model) }
        .refreshable { await viewModel.load(model: model) }
    }

    private func row(_ event: ConnectivityEvent, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(event.eventType.displayName + (event.confidence == .reconciled ? " (found on resume)" : ""))
                .font(.subheadline.weight(.semibold))
            Text("Happened \(DisplayFormat.dateTime(event.occurredAt)) · \(event.connectivityState.displayName)")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text(detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private func sentNote(_ record: SentRecord) -> String {
        var note = "Sent \(DisplayFormat.dateTime(record.sentAt)). Service answer: \(record.result.rawValue)"
        if let reason = record.reason { note += " (\(reason))" }
        note += ". Network in use when sent, as far as the app could tell: \(record.reportedVia.displayName)."
        return note
    }
}

struct EventJSONView: View {
    let event: ConnectivityEvent
    let note: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(note)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Text(EventLogViewModel.json(for: event))
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("\"null\" means the phone did not or could not measure that value.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding()
        }
        .navigationTitle(event.eventType.displayName)
        .navigationBarTitleDisplayMode(.inline)
    }
}
