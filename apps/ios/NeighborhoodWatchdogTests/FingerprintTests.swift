import XCTest
@testable import NeighborhoodWatchdog

final class HomeNetworkFingerprintTests: XCTestCase {
    private let salt = Data(repeating: 7, count: 32)

    func testSameGatewaysMatch() throws {
        let fingerprint = try XCTUnwrap(HomeNetworkFingerprint.make(gateways: ["192.168.1.1", "fe80::1a2b:3cff:fe4d:5e6f%en0"], salt: salt))
        XCTAssertEqual(fingerprint.matches(gateways: ["fe80::1a2b:3cff:fe4d:5e6f%en0", "192.168.1.1"]), true)
        // Zone id and letter case do not matter.
        XCTAssertEqual(fingerprint.matches(gateways: ["FE80::1A2B:3CFF:FE4D:5E6F%en1"]), true)
    }

    func testDifferentRouterDoesNotMatchEvenWithTheSameIPv4Gateway() throws {
        let fingerprint = try XCTUnwrap(HomeNetworkFingerprint.make(gateways: ["192.168.1.1", "fe80::1a2b:3cff:fe4d:5e6f"], salt: salt))
        XCTAssertEqual(fingerprint.matches(gateways: ["192.168.1.1", "fe80::aaaa:bbff:fecc:dddd"]), false)
        XCTAssertEqual(fingerprint.matches(gateways: ["10.0.0.1"]), false)
    }

    func testIPv4OnlyFallback() throws {
        let fingerprint = try XCTUnwrap(HomeNetworkFingerprint.make(gateways: ["192.168.50.1"], salt: salt))
        XCTAssertEqual(fingerprint.matches(gateways: ["192.168.50.1", "fe80::1"]), true)
        XCTAssertEqual(fingerprint.matches(gateways: ["192.168.0.1"]), false)
        // The home network is known by both families, the current one only shows IPv4.
        let both = try XCTUnwrap(HomeNetworkFingerprint.make(gateways: ["192.168.50.1", "fe80::1"], salt: salt))
        XCTAssertEqual(both.matches(gateways: ["192.168.50.1"]), true)
    }

    func testUnknownWhenGatewaysAreNotAvailable() throws {
        XCTAssertNil(HomeNetworkFingerprint.make(gateways: [], salt: salt))
        XCTAssertNil(HomeNetworkFingerprint.make(gateways: ["  "], salt: salt))
        let fingerprint = try XCTUnwrap(HomeNetworkFingerprint.make(gateways: ["192.168.1.1"], salt: salt))
        XCTAssertNil(fingerprint.matches(gateways: []))
        XCTAssertNil(fingerprint.matches(gateways: ["fe80::1"]), "nothing comparable: unknown, not false")
    }

    func testHashIsSaltedSHA256AndDoesNotContainTheAddress() throws {
        let hash = HomeNetworkFingerprint.hash(address: "192.168.1.1", salt: salt)
        XCTAssertEqual(hash.count, 64)
        XCTAssertEqual(hash, HomeNetworkFingerprint.hash(address: "192.168.1.1", salt: salt))
        XCTAssertNotEqual(hash, HomeNetworkFingerprint.hash(address: "192.168.1.1", salt: Data(repeating: 8, count: 32)))
        // SHA-256("") with an empty salt is the well-known digest.
        XCTAssertEqual(HomeNetworkFingerprint.hash(address: "", salt: Data()),
                       "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

        let fingerprint = try XCTUnwrap(HomeNetworkFingerprint.make(gateways: ["192.168.1.1"], salt: salt))
        let stored = String(decoding: try WatchdogJSON.encoder().encode(fingerprint), as: UTF8.self)
        XCTAssertFalse(stored.contains("192.168"))
    }

    func testRandomSaltsDiffer() {
        XCTAssertNotEqual(HomeNetworkFingerprint.randomSalt(), HomeNetworkFingerprint.randomSalt())
        XCTAssertEqual(HomeNetworkFingerprint.randomSalt().count, 32)
        let a = HomeNetworkFingerprint.make(gateways: ["192.168.1.1"])
        let b = HomeNetworkFingerprint.make(gateways: ["192.168.1.1"])
        XCTAssertNotEqual(a?.ipv4Hashes, b?.ipv4Hashes, "two installs do not produce the same fingerprint")
    }

    func testStoreRoundTrip() throws {
        let store = MemoryFingerprintStore()
        let fingerprint = try XCTUnwrap(HomeNetworkFingerprint.make(gateways: ["192.168.1.1"], salt: salt))
        try store.save(fingerprint)
        let data = try WatchdogJSON.encoder().encode(try XCTUnwrap(store.load()))
        let decoded = try WatchdogJSON.decoder().decode(HomeNetworkFingerprint.self, from: data)
        XCTAssertEqual(decoded.matches(gateways: ["192.168.1.1"]), true)
        store.clear()
        XCTAssertNil(store.load())
    }
}
