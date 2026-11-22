import CryptoKit
import Foundation

/// A salted hash of the home Wi-Fi network's gateway addresses. It lets the phone tell "my home
/// Wi-Fi went away" from "I left the house". It stays on the device: only the resulting
/// true / false / unknown (`home_network_match`) is ever reported.
///
/// No location permission is used, so the network name (SSID) and access point address (BSSID)
/// are never read. The inputs are the gateway addresses of the Wi-Fi path (`NWPath.gateways`).
///
/// Each gateway address is hashed separately (SHA-256 over salt + address) so that a network
/// still matches when one address family is temporarily missing. IPv6 link-local router addresses
/// identify a router well. An IPv4 gateway such as 192.168.1.1 is shared by many networks, so an
/// IPv4-only match is weaker: another network with the same gateway address would also match.
struct HomeNetworkFingerprint: Codable, Sendable, Equatable {
    let salt: Data
    let ipv4Hashes: Set<String>
    let ipv6Hashes: Set<String>
    let createdAt: Date

    /// Builds a fingerprint from the current gateways. Returns nil when there are none.
    static func make(gateways: [String], salt: Data = HomeNetworkFingerprint.randomSalt(), now: Date = Date()) -> HomeNetworkFingerprint? {
        let (v4, v6) = hashes(for: gateways, salt: salt)
        guard !v4.isEmpty || !v6.isEmpty else { return nil }
        return HomeNetworkFingerprint(salt: salt, ipv4Hashes: v4, ipv6Hashes: v6, createdAt: now)
    }

    /// true / false, or nil when the gateways were not available.
    func matches(gateways: [String]) -> Bool? {
        let (v4, v6) = Self.hashes(for: gateways, salt: salt)
        guard !v4.isEmpty || !v6.isEmpty else { return nil }
        if !ipv6Hashes.isEmpty, !v6.isEmpty {
            // Both sides know an IPv6 router address: that decides.
            return !ipv6Hashes.isDisjoint(with: v6)
        }
        if !ipv4Hashes.isEmpty, !v4.isEmpty {
            return !ipv4Hashes.isDisjoint(with: v4)
        }
        // Nothing comparable (stored only IPv6, now only IPv4, or the reverse).
        return nil
    }

    static func randomSalt() -> Data {
        var generator = SystemRandomNumberGenerator()
        return Data((0..<32).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
    }

    /// Lower-cases, trims and removes the IPv6 zone ("%en0") so the same router always gives the
    /// same text.
    static func canonical(_ address: String) -> String? {
        var value = address.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let percent = value.firstIndex(of: "%") { value = String(value[..<percent]) }
        if value.hasPrefix("["), value.hasSuffix("]") { value = String(value.dropFirst().dropLast()) }
        return value.isEmpty ? nil : value
    }

    static func hash(address: String, salt: Data) -> String {
        var hasher = SHA256()
        hasher.update(data: salt)
        hasher.update(data: Data(address.utf8))
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func hashes(for gateways: [String], salt: Data) -> (v4: Set<String>, v6: Set<String>) {
        var v4 = Set<String>(), v6 = Set<String>()
        for gateway in gateways {
            guard let address = canonical(gateway) else { continue }
            let digest = hash(address: address, salt: salt)
            if address.contains(":") { v6.insert(digest) } else { v4.insert(digest) }
        }
        return (v4, v6)
    }
}

/// Where the fingerprint is kept (Keychain in the app, memory in tests).
protocol FingerprintStore: Sendable {
    func load() -> HomeNetworkFingerprint?
    func save(_ fingerprint: HomeNetworkFingerprint) throws
    func clear()
}
