import Foundation
import Security

/// Minimal Keychain wrapper for generic-password items. Items are readable after the first unlock
/// (so background work can use them) and never leave this device (no iCloud Keychain, no backup
/// restore to another device).
struct KeychainStore: Sendable {
    let service: String

    enum KeychainError: Error { case unexpectedStatus(OSStatus) }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    func read(account: String) -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else { return nil }
        return item as? Data
    }

    func write(_ data: Data, account: String) throws {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(baseQuery(account: account) as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            let add = baseQuery(account: account).merging(attributes) { _, new in new }
            status = SecItemAdd(add as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw KeychainError.unexpectedStatus(status) }
    }

    func delete(account: String) {
        SecItemDelete(baseQuery(account: account) as CFDictionary)
    }
}

/// Access and refresh tokens live only in the Keychain.
struct KeychainTokenStore: TokenStore {
    let keychain: KeychainStore
    private let account = "auth-session"

    func load() -> AuthTokens? {
        guard let data = keychain.read(account: account) else { return nil }
        return try? WatchdogJSON.decoder().decode(AuthTokens.self, from: data)
    }

    func save(_ tokens: AuthTokens) throws {
        try keychain.write(try WatchdogJSON.encoder().encode(tokens), account: account)
    }

    func clear() {
        keychain.delete(account: account)
    }
}

/// The home-network fingerprint (salt and hashes) lives only in the Keychain of this device.
struct KeychainFingerprintStore: FingerprintStore {
    let keychain: KeychainStore
    private let account = "home-network-fingerprint"

    func load() -> HomeNetworkFingerprint? {
        guard let data = keychain.read(account: account) else { return nil }
        return try? WatchdogJSON.decoder().decode(HomeNetworkFingerprint.self, from: data)
    }

    func save(_ fingerprint: HomeNetworkFingerprint) throws {
        try keychain.write(try WatchdogJSON.encoder().encode(fingerprint), account: account)
    }

    func clear() {
        keychain.delete(account: account)
    }
}
