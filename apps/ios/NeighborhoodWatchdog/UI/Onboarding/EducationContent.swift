import Foundation

/// The plain-language explanation shown before joining and again under Settings > Privacy.
struct EducationTopic: Identifiable {
    let id: String
    let title: String
    let symbol: String
    let paragraphs: [String]
    var bullets: [String] = []
}

enum EducationContent {
    static let topics: [EducationTopic] = [
        EducationTopic(
            id: "what", title: "What this app does", symbol: "dot.radiowaves.left.and.right",
            paragraphs: [
                "Neighborhood Watchdog notices when this phone loses its home Wi-Fi or its internet connection. If several nearby households report the same thing at about the same time, everyone in your private neighborhood group gets an alert.",
                "It detects connectivity anomalies. It cannot tell you why a connection dropped, and it never claims that interference was confirmed. A lost connection usually has an ordinary cause such as a router restart, a power cut or a provider outage.",
            ]),
        EducationTopic(
            id: "monitored", title: "What is monitored", symbol: "wifi",
            paragraphs: ["Only the connection state of this phone:"],
            bullets: [
                "whether Wi-Fi is connected",
                "whether the internet can be reached (tested with a small request to our service)",
                "whether cellular data is available",
                "whether the Wi-Fi you are on is the one you marked as home (yes, no or unknown)",
                "times when the app could not watch (monitoring gaps)",
                "whether the phone was unplugged from its charger at the same moment, and whether Low Power Mode is on",
            ]),
        EducationTopic(
            id: "transmitted", title: "What is sent", symbol: "arrow.up.circle",
            paragraphs: [
                "Each change is sent as a small event: the kind of change, the time, the connection state, the diagnostic values listed above, the app and iOS version, and a random id for this installation.",
                "There is no account: the app never asks for an email address, a name or a password. When you join you give your street and, only if you wish, a house number. They are used to group the phones of one home. Other residents never see them.",
                "You can read every event the app has queued or sent under Settings > Transparency.",
            ]),
        EducationTopic(
            id: "notCollected", title: "What is not collected", symbol: "hand.raised",
            paragraphs: ["The app does not collect, and has no permission to read:"],
            bullets: [
                "your location or GPS position",
                "Wi-Fi network names, passwords or router logins",
                "websites you visit or anything you send over the network",
                "messages, contacts, photos, camera or microphone",
                "advertising or hardware identifiers",
            ]),
        EducationTopic(
            id: "cellular", title: "Why cellular data is used", symbol: "antenna.radiowaves.left.and.right",
            paragraphs: [
                "When your Wi-Fi is down, a report can only leave the phone over cellular data. The app allows its reports to use cellular. iOS decides which connection is actually used; the app cannot force it.",
                "If no connection works at all, events are kept on the phone and sent later with their original time. Each event is small, so data use is low.",
            ]),
        EducationTopic(
            id: "background", title: "Monitoring is not continuous on iPhone", symbol: "moon.zzz",
            paragraphs: [
                "iOS pauses apps shortly after you leave them. While this app is paused it cannot see connection changes. iOS wakes it from time to time, but decides on its own when, and sometimes not for hours.",
                "The app never pretends otherwise. When it runs again it reports the gap, and then reports what changed in the meantime without guessing when exactly it happened. The main screen shows the time of the last successful observation and the known gaps.",
                "Opening the app now and then, and leaving Background App Refresh on, helps. Swiping the app away from the app switcher stops background wake-ups until you open it again.",
            ]),
        EducationTopic(
            id: "correlation", title: "How neighborhood alerts work", symbol: "person.3",
            paragraphs: [
                "The service compares reports from households in your private neighborhood that are close to each other and arrive within a short time window. One household losing Wi-Fi is treated as a household matter. Several at once, especially across different internet providers, raises a neighborhood alert.",
                "Alerts describe what was observed, which evidence supports it and which evidence is missing. Households that are not reporting are counted as unknown, never as fine.",
            ]),
        EducationTopic(
            id: "off", title: "Turning it off and leaving", symbol: "power",
            paragraphs: [
                "You can switch monitoring off at any time under Settings. The app then reports that monitoring is paused and stops watching.",
                "You can leave the neighborhood under Settings. The service then deletes this phone and everything it reported. The app stops, and removes its enrolment, its stored events, the home-network fingerprint and its installation id from this phone.",
            ]),
    ]

    /// The short version shown on the join screen, next to a link to everything above.
    static let privacySummary = [
        "This phone reports changes in its Wi-Fi and internet connection to your private neighborhood group, so that several homes losing their connection at the same time can be noticed.",
        "Your street, and your house number if you give one, are only used to group the phones of one home. Other residents never see them. No email address, name, location or Wi-Fi name is collected.",
        "You can leave at any time under Settings. Leaving deletes what this phone reported.",
    ]
}
