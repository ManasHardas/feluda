import Foundation

/// A period during which the monitor was not running, so nothing was observed.
struct MonitoringGap: Codable, Sendable, Equatable, Identifiable {
    /// Last persisted "alive" time before the gap.
    let startedAt: Date
    /// When the monitor ran again.
    let endedAt: Date
    let reason: GapReason

    var id: Date { endedAt }
    var duration: TimeInterval { endedAt.timeIntervalSince(startedAt) }
}

/// iOS suspends and terminates apps. The monitor writes an "alive" timestamp while it runs; on
/// every resume or launch the distance between that timestamp and now tells how long it was blind.
enum GapDetector {
    /// Gaps longer than this are reported (CLIENT_PROTOCOL.md section 4: more than 2 minutes).
    static let threshold: TimeInterval = 120

    /// - Parameters:
    ///   - lastAliveAt: last persisted alive time, nil when the monitor never ran.
    ///   - coldLaunch: true when this is the first check in a new process.
    ///   - systemUptime: wall-clock time since the device booted, nil when not available.
    static func detect(lastAliveAt: Date?, now: Date, coldLaunch: Bool, systemUptime: TimeInterval?,
                       threshold: TimeInterval = GapDetector.threshold) -> MonitoringGap? {
        guard let lastAliveAt else { return nil }
        let length = now.timeIntervalSince(lastAliveAt)
        guard length > threshold else { return nil }
        let reason: GapReason
        if let systemUptime, systemUptime >= 0, systemUptime < length {
            // The device booted after the monitor was last alive.
            reason = .deviceReboot
        } else if coldLaunch {
            reason = .processTerminated
        } else {
            reason = .appSuspended
        }
        return MonitoringGap(startedAt: lastAliveAt, endedAt: now, reason: reason)
    }
}
