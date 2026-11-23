import Foundation

/// JSON encoding and decoding for the API contract.
///
/// Field names are written out as explicit `CodingKeys` in every model instead of using the
/// snake-case key strategies, because those strategies also rewrite dictionary keys (for example
/// the provider names in `isp_diversity`).
enum WatchdogJSON {
    static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(WatchdogDate.string(from: date))
        }
        return encoder
    }

    static func prettyEncoder() -> JSONEncoder {
        let encoder = encoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes, .prettyPrinted]
        return encoder
    }

    static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let text = try container.decode(String.self)
            guard let date = WatchdogDate.date(from: text) else {
                throw DecodingError.dataCorruptedError(
                    in: container, debugDescription: "Not an ISO-8601 date-time: \(text)")
            }
            return date
        }
        return decoder
    }
}

/// ISO-8601 UTC date-times. Output always has milliseconds and "Z". Input accepts fractional
/// seconds of any length or none, "Z" or a numeric offset, and (leniently) no offset, read as UTC.
enum WatchdogDate {
    static func string(from date: Date) -> String {
        date.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true))
    }

    static func date(from text: String) -> Date? {
        var value = text.trimmingCharacters(in: .whitespaces)
        guard let tIndex = value.firstIndex(where: { $0 == "T" || $0 == "t" || $0 == " " }) else { return nil }
        if value[tIndex] != "T" {
            value.replaceSubrange(tIndex...tIndex, with: "T")
        }
        // Split "date T time" from the offset.
        let timeStart = value.index(after: tIndex)
        var offset = "Z"
        var body = value
        if let last = value.last, last == "Z" || last == "z" {
            body = String(value.dropLast())
        } else if let signIndex = value[timeStart...].lastIndex(where: { $0 == "+" || $0 == "-" }) {
            offset = String(value[signIndex...])
            body = String(value[..<signIndex])
            if !offset.contains(":"), offset.count == 5 {
                offset.insert(":", at: offset.index(offset.startIndex, offsetBy: 3))
            }
        }
        // Normalise the fraction to exactly three digits (Python sends microseconds).
        var hasFraction = false
        if let dot = body.firstIndex(where: { $0 == "." || $0 == "," }) {
            let digits = body[body.index(after: dot)...].prefix(while: \.isNumber)
            guard !digits.isEmpty else { return nil }
            let millis = String(digits.prefix(3)).padding(toLength: 3, withPad: "0", startingAt: 0)
            body = String(body[..<dot]) + "." + millis
            hasFraction = true
        }
        let style = Date.ISO8601FormatStyle(includingFractionalSeconds: hasFraction)
        return try? Date(body + offset, strategy: style)
    }
}

/// A wall-clock time of day as sent by the API ("HH:MM:SS"). Used for quiet hours.
struct TimeOfDay: Codable, Sendable, Equatable {
    var hour: Int
    var minute: Int
    var second: Int = 0

    init(hour: Int, minute: Int, second: Int = 0) {
        self.hour = hour
        self.minute = minute
        self.second = second
    }

    init?(string: String) {
        let main = string.split(whereSeparator: { $0 == "." || $0 == "+" || $0 == "Z" }).first.map(String.init) ?? string
        let parts = main.split(separator: ":").map { Int($0) }
        guard parts.count >= 2, parts.count <= 3, !parts.contains(nil) else { return nil }
        let hour = parts[0] ?? 0, minute = parts[1] ?? 0
        let second = parts.count == 3 ? (parts[2] ?? 0) : 0
        guard (0...23).contains(hour), (0...59).contains(minute), (0...59).contains(second) else { return nil }
        self.init(hour: hour, minute: minute, second: second)
    }

    var string: String { String(format: "%02d:%02d:%02d", hour, minute, second) }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let text = try container.decode(String.self)
        guard let value = TimeOfDay(string: text) else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Not a time of day: \(text)")
        }
        self = value
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(string)
    }
}
