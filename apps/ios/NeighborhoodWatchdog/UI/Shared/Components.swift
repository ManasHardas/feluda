import SwiftUI

/// Colour, symbol, meaning and explanation together. The explanation is always shown: a colour
/// alone never stands for a status.
struct StatusCard: View {
    let color: StatusColor
    let title: String
    let explanation: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Image(systemName: color.symbolName)
                    .foregroundStyle(color.color)
                    .font(.title2)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.headline)
            }
            Text(color.meaning)
                .font(.subheadline.weight(.semibold))
            Text(explanation)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title). \(color.meaning). \(explanation)")
    }
}

/// A label with a value underneath or beside it, readable at large text sizes.
struct InfoRow: View {
    let label: String
    let value: String
    var symbol: String?
    var tint: Color = .secondary

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                icon
                Text(label)
                Spacer(minLength: 12)
                Text(value).foregroundStyle(.secondary).multilineTextAlignment(.trailing)
            }
            HStack(alignment: .top, spacing: 12) {
                icon
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                    Text(value).foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(value)")
    }

    @ViewBuilder private var icon: some View {
        if let symbol {
            Image(systemName: symbol)
                .foregroundStyle(tint)
                .frame(width: 26)
                .accessibilityHidden(true)
        }
    }
}

struct ErrorBanner: View {
    let message: String

    var body: some View {
        Label(message, systemImage: "exclamationmark.triangle")
            .font(.subheadline)
            .foregroundStyle(.primary)
            .accessibilityLabel("Problem: \(message)")
    }
}

struct BulletList: View {
    let items: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("•").accessibilityHidden(true)
                    Text(item)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A full-width primary button that shows progress while its action runs.
struct PrimaryButton: View {
    let title: String
    var isBusy = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                if isBusy { ProgressView().padding(.trailing, 4) }
                Text(title).fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity, minHeight: 28)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(isBusy)
    }
}
