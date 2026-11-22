import Foundation

/// Tracks whether the phone lost external power around the time of an event. A router and a
/// phone charger that go dark together point at a power cut rather than a wireless problem.
///
/// Rule (CLIENT_PROTOCOL.md section 4):
/// - true only if the phone was charging and the charger disconnected within 15 seconds either
///   side of the event;
/// - false if it was charging and stayed charging;
/// - nil if it was not charging, or the power state is not known.
struct PowerTracker: Sendable, Equatable {
    enum PowerState: String, Sendable, Codable { case unknown, onBattery, externalPower }

    static let window: TimeInterval = 15

    private(set) var state: PowerState = .unknown
    private(set) var lastDisconnectAt: Date?

    mutating func record(_ newState: PowerState, at time: Date) {
        if state == .externalPower, newState == .onBattery {
            lastDisconnectAt = time
        }
        state = newState
    }

    /// Evaluated when the event is built, which is a few seconds after `eventTime` for debounced
    /// events. A disconnect later than that cannot be known and is not guessed.
    func externalPowerLostRecently(eventTime: Date) -> Bool? {
        if let lastDisconnectAt, abs(lastDisconnectAt.timeIntervalSince(eventTime)) <= Self.window {
            return true
        }
        switch state {
        case .externalPower: return false
        case .onBattery, .unknown: return nil
        }
    }
}
