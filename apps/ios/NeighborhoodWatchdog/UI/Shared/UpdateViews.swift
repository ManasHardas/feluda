import SwiftUI

/// Shown instead of every other screen while the server refuses this app version. It cannot be
/// closed: the only way on is the update. Events already recorded stay on the phone.
struct UpdateRequiredView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Image(systemName: "arrow.down.app")
                    .font(.system(size: 56))
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                Text("Update required")
                    .font(.title.bold())
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text("This version of the app is no longer supported. Install the update to keep taking part. Until then this phone sends nothing; what it already recorded stays on the phone.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    Task { await model.openUpdate() }
                } label: {
                    Text("Update")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .accessibilityHint(model.updateURL == nil ? "Opens TestFlight" : "Opens the page with the update")
                if model.updateURL == nil {
                    Text("The update is in TestFlight.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .interactiveDismissDisabled()
    }
}

/// A newer version exists and this one still works. Small, at the top, and can be closed.
struct UpdateAvailableNotice: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                message
                Spacer(minLength: 8)
                buttons
            }
            VStack(alignment: .leading, spacing: 8) {
                message
                HStack(spacing: 12) { buttons }
            }
        }
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal)
        .padding(.top, 4)
    }

    private var message: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text("A new version is available")
                    .font(.subheadline.weight(.semibold))
                if model.updateURL == nil {
                    Text("Open TestFlight to install it.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        } icon: {
            Image(systemName: "arrow.down.circle")
                .foregroundStyle(.tint)
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var buttons: some View {
        Button("Update") {
            Task { await model.openUpdate() }
        }
        .buttonStyle(.bordered)
        .accessibilityHint(model.updateURL == nil ? "Opens TestFlight" : "Opens the page with the update")
        Button {
            model.updateNoticeDismissed = true
        } label: {
            Image(systemName: "xmark")
        }
        .buttonStyle(.borderless)
        .accessibilityLabel("Close")
        .accessibilityHint("Hides this notice")
    }
}
