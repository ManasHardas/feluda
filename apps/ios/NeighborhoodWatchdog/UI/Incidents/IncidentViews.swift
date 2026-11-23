import Observation
import SwiftUI

struct IncidentRow: View {
    let incident: IncidentOut

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: incident.statusColor.symbolName)
                .foregroundStyle(incident.statusColor.color)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(incident.classification.displayName)
                    .font(.subheadline.weight(.semibold))
                Text(incident.summary)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Text("\(DisplayFormat.dateTime(incident.startedAt)) · \(incident.status.displayName)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

@MainActor
@Observable
final class IncidentListViewModel {
    var showAll = false
    var incidents: [IncidentOut] = []
    var isLoading = false
    var errorMessage: String?
    var loadedOnce = false

    func load(model: AppModel) async {
        guard let api = model.services?.api else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            incidents = try await api.incidents(openOnly: !showAll).incidents
            errorMessage = nil
            loadedOnce = true
        } catch {
            let apiError = APIError.from(error)
            errorMessage = apiError.userMessage
            model.handleServiceError(apiError)
        }
    }
}

struct IncidentListView: View {
    @Environment(AppModel.self) private var model
    @State private var viewModel = IncidentListViewModel()

    var body: some View {
        @Bindable var viewModel = viewModel
        List {
            Section {
                Picker("Show", selection: $viewModel.showAll) {
                    Text("Open").tag(false)
                    Text("All").tag(true)
                }
                .pickerStyle(.segmented)
            }
            if let error = viewModel.errorMessage {
                Section { ErrorBanner(message: error) }
            }
            Section {
                if viewModel.incidents.isEmpty, viewModel.loadedOnce {
                    Text(viewModel.showAll ? "No incidents have been recorded." : "No open incidents right now.")
                        .foregroundStyle(.secondary)
                } else if viewModel.incidents.isEmpty, viewModel.isLoading {
                    ProgressView()
                }
                ForEach(viewModel.incidents) { incident in
                    NavigationLink(value: AppRoute.incident(incident.incidentID)) {
                        IncidentRow(incident: incident)
                    }
                }
            } footer: {
                Text("An incident is a connectivity anomaly seen by one or more households. It describes what was observed, not its cause.")
            }
        }
        .navigationTitle("Incidents")
        .task(id: viewModel.showAll) { await viewModel.load(model: model) }
        .refreshable { await viewModel.load(model: model) }
    }
}

@MainActor
@Observable
final class IncidentDetailViewModel {
    var detail: IncidentDetailOut?
    var isLoading = false
    var errorMessage: String?

    func load(id: UUID, model: AppModel) async {
        guard let api = model.services?.api else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            detail = try await api.incident(id: id)
            errorMessage = nil
        } catch {
            let apiError = APIError.from(error)
            errorMessage = apiError.code == "not_found" ? "This incident is no longer available." : apiError.userMessage
        }
    }
}

/// Incident detail. Shows counts and approximate scope only; never a household address.
struct IncidentDetailView: View {
    let incidentID: UUID
    @Environment(AppModel.self) private var model
    @State private var viewModel = IncidentDetailViewModel()

    var body: some View {
        List {
            if let error = viewModel.errorMessage {
                Section { ErrorBanner(message: error) }
            }
            if let detail = viewModel.detail {
                content(detail)
            } else if viewModel.isLoading {
                Section { ProgressView("Loading…") }
            }
        }
        .navigationTitle("Incident")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: incidentID) { await viewModel.load(id: incidentID, model: model) }
        .refreshable { await viewModel.load(id: incidentID, model: model) }
    }

    @ViewBuilder private func content(_ detail: IncidentDetailOut) -> some View {
        let incident = detail.incident
        Section {
            StatusCard(color: incident.statusColor, title: incident.classification.displayName, explanation: detail.explanation)
            InfoRow(label: "Status", value: incident.status.displayName)
            InfoRow(label: "Alert level", value: incident.alertLevel.displayName)
            InfoRow(label: "Your household", value: incident.myHouseholdAffected ? "Affected" : "Not reported as affected")
        }

        Section("What was observed") {
            InfoRow(label: "Started", value: DisplayFormat.dateTime(incident.startedAt))
            InfoRow(label: "Last report", value: DisplayFormat.dateTime(incident.lastEventAt))
            if let resolvedAt = incident.resolvedAt {
                InfoRow(label: "Closed", value: DisplayFormat.dateTime(resolvedAt))
            }
            InfoRow(label: "Affected households", value: "\(incident.affectedHouseholdCount) of \(incident.activeHouseholdCount) active")
            InfoRow(label: "Approximate area", value: incident.approximateScope)
            InfoRow(label: "Scope", value: incident.scope.displayName)
            InfoRow(label: "Correlation window", value: DisplayFormat.duration(TimeInterval(incident.correlationWindowS)))
            InfoRow(label: "Reports spread over", value: DisplayFormat.duration(incident.timeSpreadS))
            if detail.delayedReportCount > 0 {
                InfoRow(label: "Reports that arrived late", value: "\(detail.delayedReportCount)")
            }
        }

        Section {
            if incident.ispDiversity.isEmpty {
                Text("Not known").foregroundStyle(.secondary)
            }
            ForEach(incident.ispDiversity.sorted(by: { $0.key < $1.key }), id: \.key) { key, count in
                InfoRow(label: Isp.displayName(forKey: key), value: "\(count)")
            }
        } header: {
            Text("Internet providers of affected households")
        } footer: {
            Text("Several different providers failing together makes a single provider outage less likely.")
        }

        if !detail.failureModes.isEmpty {
            Section("Kind of interruption") {
                ForEach(detail.failureModes.sorted(by: { $0.key < $1.key }), id: \.key) { key, count in
                    InfoRow(label: FailureMode(rawValue: key)?.displayName ?? key, value: "\(count)")
                }
            }
        }

        Section("Evidence supporting this") {
            if detail.supportingEvidence.isEmpty {
                Text("None listed").foregroundStyle(.secondary)
            } else {
                BulletList(items: detail.supportingEvidence)
            }
        }

        Section {
            if detail.missingEvidence.isEmpty {
                Text("None listed").foregroundStyle(.secondary)
            } else {
                BulletList(items: detail.missingEvidence)
            }
        } header: {
            Text("Evidence that is missing")
        } footer: {
            Text("Phones cannot measure radio signals directly, so the cause of an interruption cannot be established by this app.")
        }

        Section("What you can do") {
            if detail.recommendedActions.isEmpty {
                Text("No recommendations").foregroundStyle(.secondary)
            } else {
                BulletList(items: detail.recommendedActions)
            }
        }

        Section {
            if let score = detail.score {
                InfoRow(label: "Total score", value: score.formatted(.number.precision(.fractionLength(0...1))))
            } else {
                InfoRow(label: "Total score", value: "Not scored")
            }
            ForEach(detail.scoreBreakdown) { component in
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(component.indicator).font(.subheadline.weight(.semibold))
                        Spacer()
                        Text("\(component.points.formatted(.number.precision(.fractionLength(0...1)))) of \(component.maxPoints.formatted(.number.precision(.fractionLength(0...1))))")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Text(component.detail)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
        } header: {
            Text("How the score was calculated")
        } footer: {
            Text("The score ranks how unusual the pattern is. It is not a probability and not proof of a cause.")
        }
    }
}
