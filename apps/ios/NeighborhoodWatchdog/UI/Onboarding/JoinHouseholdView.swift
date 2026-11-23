import SwiftUI

/// Onboarding step 2: the invitation, then the street (required) and the house number (optional).
/// Nothing else is asked. There is no sign-in; tapping "Join" is the privacy acknowledgement.
struct JoinHouseholdView: View {
    @Environment(AppModel.self) private var model
    @State private var invitationText = ""
    @State private var invitation: VerifiedInvitation?
    @State private var streetName = ""
    @State private var houseNumber = ""
    @State private var isBusy = false
    @State private var errorMessage: String?

    private var pastedInvitationLooksValid: Bool { InvitationToken.parse(invitationText) != nil }
    private var trimmedStreet: String { streetName.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form {
                if let invitation {
                    homeSections(invitation)
                } else {
                    invitationSections
                }
                if let errorMessage {
                    Section { ErrorBanner(message: errorMessage) }
                }
            }
            .navigationTitle(invitation == nil ? "Your invitation" : "Join")
            // A link that opened the app (feluda://join#t=...) is checked right away.
            .task(id: model.pendingInvitation) {
                if let token = model.pendingInvitation { await verify(token) }
            }
        }
    }

    // MARK: Step 1, only when the app was opened without a link

    @ViewBuilder private var invitationSections: some View {
        if let notice = model.joinNotice {
            Section { Text(notice) }
        }
        if model.pendingInvitation != nil {
            Section { ProgressView("Checking your invitation…") }
        } else {
            Section {
                TextField("Invitation link", text: $invitationText, axis: .vertical)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .lineLimit(1...4)
                if !invitationText.isEmpty, !pastedInvitationLooksValid {
                    Text("This does not look like an invitation link.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Invitation")
            } footer: {
                Text("Open the invitation link your neighborhood organizer sent you on this phone, or paste it here.")
            }
            Section {
                PrimaryButton(title: "Continue", isBusy: isBusy) {
                    Task { await verify(invitationText) }
                }
                .disabled(!pastedInvitationLooksValid)
            }
        }
    }

    // MARK: Step 2

    @ViewBuilder private func homeSections(_ invitation: VerifiedInvitation) -> some View {
        Section {
            Label("You are invited to \(invitation.neighborhoodName)", systemImage: "person.3")
        }
        Section {
            TextField("Street", text: $streetName)
                .textContentType(.streetAddressLine1)
                .onChange(of: streetName) { _, value in
                    if value.count > 80 { streetName = String(value.prefix(80)) }
                }
            TextField("House number (optional)", text: $houseNumber)
                .onChange(of: houseNumber) { _, value in
                    if value.count > 16 { houseNumber = String(value.prefix(16)) }
                }
        } header: {
            Text("Your home")
        } footer: {
            Text("The street is needed. The house number is optional; you can leave it empty.")
        }
        Section {
            ForEach(EducationContent.privacySummary, id: \.self) { paragraph in
                Text(paragraph).font(.subheadline)
            }
            NavigationLink("Read the full privacy information") { PrivacyInfoView() }
        } header: {
            Text("Privacy summary")
        }
        Section {
            PrimaryButton(title: "Join", isBusy: isBusy) {
                Task { await join(invitation) }
            }
            .disabled(trimmedStreet.isEmpty)
            Button("Use a different invitation") {
                self.invitation = nil
                errorMessage = nil
            }
            .disabled(isBusy)
        } footer: {
            Text("By tapping Join you confirm that you have read the privacy summary.")
        }
    }

    // MARK: Actions

    private func verify(_ input: String) async {
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            invitation = try await model.verifyInvitation(input)
            invitationText = ""
        } catch {
            // Keep what was received, so "Continue" can try again (for example after a lost connection).
            invitationText = input
            errorMessage = APIError.from(error).userMessage
        }
        model.clearPendingInvitation()
    }

    private func join(_ invitation: VerifiedInvitation) async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            try await model.enroll(invitation: invitation, streetName: streetName, houseNumber: houseNumber)
        } catch {
            let apiError = APIError.from(error)
            if apiError.code == "invitation_invalid" { self.invitation = nil }
            errorMessage = apiError.userMessage
        }
    }
}
