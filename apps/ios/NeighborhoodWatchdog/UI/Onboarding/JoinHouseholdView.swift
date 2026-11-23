import SwiftUI

/// Onboarding step 3 (only when the email is not a member yet): invitation and household.
struct JoinHouseholdView: View {
    @Environment(AppModel.self) private var model
    @State private var invitation = ""
    @State private var houseNumber = ""
    @State private var streetName = ""
    @State private var label = ""
    @State private var isp: Isp?
    @State private var privacyAcknowledged = false
    @State private var isBusy = false
    @State private var errorMessage: String?

    private var invitationLooksValid: Bool { InvitationToken.parse(invitation) != nil }
    private var canSubmit: Bool {
        invitationLooksValid && !houseNumber.trimmingCharacters(in: .whitespaces).isEmpty
            && !streetName.trimmingCharacters(in: .whitespaces).isEmpty && privacyAcknowledged
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Invitation link or code", text: $invitation, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .lineLimit(1...4)
                    if !invitation.isEmpty, !invitationLooksValid {
                        Text("This does not look like an invitation link or code.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Invitation")
                } footer: {
                    Text("Paste the link you received from your neighborhood administrator, or just the code at the end of it.")
                }

                Section {
                    TextField("House number", text: $houseNumber)
                        .textContentType(.streetAddressLine1)
                    TextField("Street name", text: $streetName)
                        .textContentType(.streetAddressLine2)
                } header: {
                    Text("Your household")
                } footer: {
                    Text("Used to match you to a household your administrator approved. Other residents never see your address.")
                }

                Section {
                    TextField("Label (optional)", text: $label)
                    Picker("Internet provider", selection: $isp) {
                        Text("Prefer not to say").tag(Isp?.none)
                        ForEach(Isp.allCases, id: \.self) { provider in
                            Text(provider.displayName).tag(Isp?.some(provider))
                        }
                    }
                } header: {
                    Text("Optional")
                } footer: {
                    Text("The provider helps to tell a provider outage from other causes.")
                }

                Section {
                    Toggle(isOn: $privacyAcknowledged) {
                        Text("I have read the privacy summary shown earlier.")
                    }
                    .toggleStyle(CheckboxToggleStyle())
                }

                if let errorMessage {
                    Section { ErrorBanner(message: errorMessage) }
                }

                Section {
                    PrimaryButton(title: "Join neighborhood", isBusy: isBusy) {
                        Task { await submit() }
                    }
                    .disabled(!canSubmit)
                    Button("Sign out") { Task { await model.signOut() } }
                }
            }
            .navigationTitle("Enter invitation")
        }
    }

    private func submit() async {
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            try await model.joinHousehold(invitation: invitation, houseNumber: houseNumber, streetName: streetName,
                                          label: label, isp: isp, privacyAcknowledged: privacyAcknowledged)
        } catch {
            let apiError = APIError.from(error)
            if apiError == .sessionExpired || apiError == .notSignedIn {
                model.handleServiceError(apiError)
            } else {
                errorMessage = apiError.userMessage
            }
        }
    }
}
