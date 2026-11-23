import Foundation

/// Exponential backoff with jitter for upload retries: start 5 s, factor 2, cap 15 minutes.
struct BackoffPolicy: Sendable, Equatable {
    var base: TimeInterval = 5
    var factor: Double = 2
    var cap: TimeInterval = 15 * 60
    /// Fraction of the delay that is randomised in both directions (0.2 = plus or minus 20 %).
    var jitter: Double = 0.2

    /// Delay before retry number `attempt` (0 for the first retry).
    /// - Parameter random: a value in 0...1; injected so tests are deterministic.
    func delay(attempt: Int, random: Double = Double.random(in: 0...1)) -> TimeInterval {
        let exponent = Double(max(0, min(attempt, 30)))
        let raw = min(cap, base * pow(factor, exponent))
        let unit = max(0, min(1, random))
        let jittered = raw * (1 - jitter + 2 * jitter * unit)
        return min(cap, max(0, jittered))
    }
}
