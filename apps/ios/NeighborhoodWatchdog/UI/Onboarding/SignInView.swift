import Observation
import SwiftUI

@MainActor
@Observable
final class SignInViewModel {
    enum Step { case email, code }

    var step: Step = .email
    var email = ""
    var code = ""
    var isBusy = false
    var errorMessage: String?

    var trimmedEmail: String { email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
    var emailLooksValid: Bool {
        let value = trimmedEmail
        guard let at = value.firstIndex(of: "@"), at != value.startIndex else { return false }
        return value[value.index(after: at)...].contains(".") && !value.contains(" ")
    }
    var trimmedCode: String { code.filter { !$0.isWhitespace } }

    func sendCode(model: AppModel) async {
        guard emailLooksValid else { return }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            try await model.requestSignInCode(email: trimmedEmail)
            code = ""
            step = .code
        } catch {
            errorMessage = APIError.from(error).userMessage
        }
    }

    func verify(model: AppModel) async {
        guard !trimmedCode.isEmpty else { return }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            try await model.verifySignInCode(email: trimmedEmail, code: trimmedCode)
        } catch {
            let apiError = APIError.from(error)
            if let status = apiError.status, (400..<500).contains(status), status != 429 {
                errorMessage = "That code is not correct or has expired. Check the email, or ask for a new code."
            } else {
                errorMessage = apiError.userMessage
            }
        }
    }
}

/// Onboarding step 2: sign in with a one-time code sent by email. There is no password.
struct SignInView: View {
    @Environment(AppModel.self) private var model
    @State private var viewModel = SignInViewModel()
    @FocusState private var focused: Bool

    var body: some View {
        @Bindable var viewModel = viewModel
        NavigationStack {
            Form {
                switch viewModel.step {
                case .email:
                    Section {
                        TextField("Email address", text: $viewModel.email)
                            .textContentType(.emailAddress)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($focused)
                            .submitLabel(.send)
                            .onSubmit { Task { await viewModel.sendCode(model: model) } }
                    } header: {
                        Text("Your email")
                    } footer: {
                        Text("We email you a one-time code. Use the address you registered with, or the one your invitation was sent to.")
                    }
                    Section {
                        PrimaryButton(title: "Email me a code", isBusy: viewModel.isBusy) {
                            Task { await viewModel.sendCode(model: model) }
                        }
                        .disabled(!viewModel.emailLooksValid)
                    }
                case .code:
                    Section {
                        TextField("Code from the email", text: $viewModel.code)
                            .textContentType(.oneTimeCode)
                            .keyboardType(.numberPad)
                            .focused($focused)
                    } header: {
                        Text("Enter the code")
                    } footer: {
                        Text("Sent to \(viewModel.trimmedEmail). It can take a minute to arrive.")
                    }
                    Section {
                        PrimaryButton(title: "Sign in", isBusy: viewModel.isBusy) {
                            Task { await viewModel.verify(model: model) }
                        }
                        .disabled(viewModel.trimmedCode.isEmpty)
                        Button("Use a different email") {
                            viewModel.step = .email
                            viewModel.errorMessage = nil
                        }
                        Button("Send a new code") {
                            Task { await viewModel.sendCode(model: model) }
                        }
                        .disabled(viewModel.isBusy)
                    }
                }
                if let error = viewModel.errorMessage {
                    Section { ErrorBanner(message: error) }
                }
            }
            .navigationTitle("Sign in")
            .onAppear { focused = true }
        }
    }
}
