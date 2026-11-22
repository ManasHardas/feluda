import Foundation
import XCTest
@testable import NeighborhoodWatchdog

/// A value guarded by a lock, for state shared with @Sendable closures in tests.
final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Value

    init(_ value: Value) { stored = value }

    var value: Value {
        get { lock.lock(); defer { lock.unlock() }; return stored }
        set { lock.lock(); stored = newValue; lock.unlock() }
    }

    @discardableResult
    func mutate<Result>(_ body: (inout Value) -> Result) -> Result {
        lock.lock(); defer { lock.unlock() }
        return body(&stored)
    }
}

final class TestClock: @unchecked Sendable {
    private let time: Locked<Date>

    init(_ start: Date = Date(timeIntervalSince1970: 1_800_000_000)) { time = Locked(start) }

    var now: Date { time.value }
    func advance(_ seconds: TimeInterval) { time.mutate { $0 = $0.addingTimeInterval(seconds) } }
    var provider: @Sendable () -> Date { { [time] in time.value } }
}

struct FakeResponse: Sendable {
    var status = 200
    var body = Data()
    var headers: [String: String] = [:]

    static func json(_ status: Int = 200, _ text: String, headers: [String: String] = [:]) -> FakeResponse {
        FakeResponse(status: status, body: Data(text.utf8), headers: headers)
    }
}

/// Stands in for the network. Records every request and answers with the handler.
final class FakeTransport: HTTPTransport, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) async throws -> FakeResponse

    private let recorded = Locked<[URLRequest]>([])
    private let handler: Locked<Handler>

    init(_ handler: @escaping Handler = { _ in FakeResponse() }) {
        self.handler = Locked(handler)
    }

    var requests: [URLRequest] { recorded.value }
    func setHandler(_ handler: @escaping Handler) { self.handler.value = handler }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        recorded.mutate { $0.append(request) }
        let response = try await handler.value(request)
        let http = HTTPURLResponse(url: request.url!, statusCode: response.status, httpVersion: "HTTP/1.1",
                                   headerFields: response.headers)!
        return (response.body, http)
    }
}

final class MemoryTokenStore: TokenStore, @unchecked Sendable {
    let tokens: Locked<AuthTokens?>
    init(_ tokens: AuthTokens? = nil) { self.tokens = Locked(tokens) }
    func load() -> AuthTokens? { tokens.value }
    func save(_ tokens: AuthTokens) throws { self.tokens.value = tokens }
    func clear() { tokens.value = nil }
}

final class MemoryFingerprintStore: FingerprintStore, @unchecked Sendable {
    let fingerprint = Locked<HomeNetworkFingerprint?>(nil)
    func load() -> HomeNetworkFingerprint? { fingerprint.value }
    func save(_ fingerprint: HomeNetworkFingerprint) throws { self.fingerprint.value = fingerprint }
    func clear() { fingerprint.value = nil }
}

final class FakePath: PathObserving, @unchecked Sendable {
    let snapshot: Locked<PathSnapshot>
    let started = Locked(false)

    init(_ snapshot: PathSnapshot) { self.snapshot = Locked(snapshot) }

    func start(onChange: @escaping @Sendable (PathSnapshot) -> Void) { started.value = true }
    func stop() { started.value = false }
    func current() -> PathSnapshot { snapshot.value }
}

final class FakeProbe: ReachabilityProbing, @unchecked Sendable {
    let result: Locked<ProbeResult>
    init(_ result: ProbeResult) { self.result = Locked(result) }
    func probe(testWithoutCellular: Bool) async -> ProbeResult {
        var value = result.value
        if !testWithoutCellular { value.withoutCellular = nil }
        return value
    }
}

enum TestFiles {
    static func temporaryDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("watchdog-tests", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

extension PathSnapshot {
    static let homeWifi = PathSnapshot(wifiAvailable: true, cellularAvailable: true, defaultTransport: .wifi,
                                       otherPathUsable: false, wifiGateways: ["192.168.1.1", "fe80::1%en0"])
    static let cellularOnly = PathSnapshot(wifiAvailable: false, cellularAvailable: true, defaultTransport: .cellular,
                                           otherPathUsable: false, wifiGateways: [])
    static let nothing = PathSnapshot(wifiAvailable: false, cellularAvailable: false, defaultTransport: Transport.none,
                                      otherPathUsable: false, wifiGateways: [])
}

extension MonitorInputs {
    static let healthy = MonitorInputs(wifiAssociated: true, cellularAvailable: true, defaultTransport: .wifi,
                                       wifiInternetReachable: true, internetReachable: true)
}

func makeEvent(_ type: EventType = .wifiLost, sequence: Int = 0, at date: Date = Date(timeIntervalSince1970: 1_800_000_000),
               state: ConnectivityState = .wifiDisconnected) -> ConnectivityEvent {
    ConnectivityEvent(eventID: UUID(), sequence: sequence, eventType: type, connectivityState: state, occurredAt: date,
                      osVersion: "17.4", appVersion: "1.0.0 (1)", confidence: .observed, diagnostics: Diagnostics())
}

func jsonObject(_ data: Data) throws -> [String: Any] {
    try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
}
