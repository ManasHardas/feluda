import SwiftUI

/// Onboarding step 1: what the app does and the privacy summary. Nothing is monitored or sent
/// here; the acknowledgement is the "Join" button on the next screen.
struct EducationView: View {
    @Environment(AppModel.self) private var model

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
                    PrimaryButton(title: "Continue") {
                        model.finishIntroduction()
                    }
                    Text("Nothing is monitored or sent until you join with an invitation link.")
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
