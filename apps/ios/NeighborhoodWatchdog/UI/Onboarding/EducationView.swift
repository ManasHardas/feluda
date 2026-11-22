import SwiftUI

/// Onboarding step 1: education and explicit consent. Monitoring never starts before this.
struct EducationView: View {
    @Environment(AppModel.self) private var model
    @State private var consentChecked = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text("Please read this before you start. It explains what the app watches, what it sends and what it cannot do.")
                        .font(.body)
                    ForEach(EducationContent.topics) { topic in
                        EducationTopicView(topic: topic)
                    }
                    Divider()
                    Toggle(isOn: $consentChecked) {
                        Text("I have read this and I agree that this app monitors this phone's connection state and reports it to my private neighborhood group as described above.")
                            .font(.body)
                    }
                    .toggleStyle(CheckboxToggleStyle())
                    .accessibilityHint("Required before monitoring can start")
                    PrimaryButton(title: "Agree and continue") {
                        model.giveConsent()
                    }
                    .disabled(!consentChecked)
                    Text("Nothing is monitored or sent until you agree and finish setting up.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .padding()
            }
            .navigationTitle("Welcome")
        }
    }
}

struct EducationTopicView: View {
    let topic: EducationTopic

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(topic.title, systemImage: topic.symbol)
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            ForEach(topic.paragraphs, id: \.self) { paragraph in
                Text(paragraph)
            }
            if !topic.bullets.isEmpty {
                BulletList(items: topic.bullets)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct CheckboxToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .font(.title2)
                    .foregroundStyle(configuration.isOn ? Color.accentColor : .secondary)
                    .accessibilityHidden(true)
                configuration.label
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(configuration.isOn ? [.isSelected] : [])
        .accessibilityValue(configuration.isOn ? "Checked" : "Not checked")
    }
}
