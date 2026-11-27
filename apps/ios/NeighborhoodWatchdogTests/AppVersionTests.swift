import XCTest
@testable import NeighborhoodWatchdog

private let updateRequiredResponse = FakeResponse.json(
    426, #"{"error":{"code":"update_required","message":"This version of the app is no longer supported. Install the update to keep taking part.","request_id":"r"}}"#)

private func compatibilityJSON(supported: Bool, updateAvailable: Bool, updateURL: String? = nil) -> FakeResponse {
    let url = updateURL.map { "\"\($0)\"" } ?? "null"
    return .json(200, #"{"platform":"ios","version":"0.1.0","supported":\#(supported),"update_available":\#(updateAvailable),"minimum_version":"0.2.0","latest_version":"0.3.0","update_url":\#(url)}"#)
}

/// The server decides when an app version is too old (HTTP 426 `update_required` and
/// `GET /api/v1/app/compatibility`). These tests cover the header, the 426, the gate that stops
/// sending, and the compatibility check.
final class AppVersionTests: XCTestCase {
    private let apiURL = URL(string: "https://api.example.org")!
    private let header = "ios/0.1.0 (1)"

    private func client(_ transport: FakeTransport, gate: UpdateGate? = nil, token: String? = "secret") -> APIClient {
        APIClient(baseURL: apiURL, transport: transport, credentials: MemoryDeviceTokenStore(token),
                  appVersionHeader: header, updateGate: gate)
    }

    // MARK: Header

    func testHeaderFormat() {
        XCTAssertEqual(AppVersion.header(marketingVersion: "0.1.0", buildNumber: "1"), "ios/0.1.0 (1)")
        XCTAssertEqual(AppVersion.header(marketingVersion: "1.2", buildNumber: "45"), "ios/1.2.0 (45)", "padded to major.minor.patch")
        XCTAssertEqual(AppVersion.header(marketingVersion: "3", buildNumber: ""), "ios/3.0.0")
        XCTAssertNil(AppVersion.header(marketingVersion: "1.0-beta", buildNumber: "1"))
        XCTAssertNil(AppVersion.header(marketingVersion: "1.2.3.4", buildNumber: "1"))
        XCTAssertNil(AppVersion.header(marketingVersion: "", buildNumber: "1"))
        XCTAssertNil(AppVersion.semantic("1..2"))
        XCTAssertNil(AppVersion.semantic("١.٢.٣"), "only ASCII digits")
    }

    func testHeaderMatchesTheServerPattern() throws {
        // backend/watchdog/app_versions.py: ^(ios|android)/(\d{1,4}\.\d{1,4}\.\d{1,4})(?:\s.*)?$
        let pattern = try NSRegularExpression(pattern: #"^(ios|android)/(\d{1,4}\.\d{1,4}\.\d{1,4})(?:\s.*)?$"#)
        for value in ["ios/0.1.0 (1)", AppVersion.header(marketingVersion: "12.0", buildNumber: "300")!] {
            XCTAssertEqual(pattern.numberOfMatches(in: value, range: NSRange(value.startIndex..., in: value)), 1, value)
        }
    }

    func testEveryRequestCarriesTheVersionHeader() async throws {
        let transport = FakeTransport { request in
            request.url?.path.hasSuffix("compatibility") == true
                ? compatibilityJSON(supported: true, updateAvailable: false)
                : .json(200, #"{"ok":true}"#)
        }
        let api = client(transport)
        _ = try await api.compatibility(platform: .ios, version: "0.1.0")
        _ = try? await api.revokeDevice(id: UUID())
        _ = try? await api.verifyInvitation(token: "AbCdEfGhIjKlMnOpQrStUvWxYz")
        XCTAssertEqual(transport.requests.count, 3)
        for request in transport.requests {
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-App-Version"), header, request.url?.absoluteString ?? "")
        }
    }

    func testProbeSendsTheVersionOnlyToTheHealthEndpoint() async {
        let health = URL(string: "https://api.example.org/api/v1/monitoring/health")!
        let fallback = URL(string: "https://www.apple.com/library/test/success.html")!
        let transport = FakeTransport { request in
            if request.url == health { throw URLError(.timedOut) }
            return FakeResponse()
        }
        let probe = ReachabilityProbe(healthURL: health, fallbackURL: fallback, healthHeaders: ["X-App-Version": header],
                                      anyPath: transport, withoutCellular: transport)
        _ = await probe.probe(testWithoutCellular: false)
        XCTAssertEqual(transport.requests.count, 2)
        XCTAssertEqual(transport.requests[0].value(forHTTPHeaderField: "X-App-Version"), header)
        XCTAssertNil(transport.requests[1].value(forHTTPHeaderField: "X-App-Version"), "nothing about the app goes to a third party")
    }

    // MARK: 426

    func testUpdateRequiredSetsTheGateAndStopsFurtherRequests() async throws {
        let gate = UpdateGate()
        let changes = Locked<[Bool]>([])
        let observed = UpdateGate(onChange: { value in changes.mutate { $0.append(value) } })
        let transport = FakeTransport { _ in updateRequiredResponse }

        for gate in [gate, observed] {
            let api = client(transport, gate: gate)
            do {
                _ = try await api.myHousehold()
                XCTFail("expected update required")
            } catch {
                XCTAssertEqual(APIError.from(error), .updateRequired)
                XCTAssertFalse(APIError.updateRequired.isTransient)
            }
            XCTAssertTrue(gate.isUpdateRequired)
        }
        XCTAssertEqual(changes.value, [true])

        // Later calls are not sent at all.
        let sent = transport.requests.count
        let api = client(transport, gate: gate)
        do {
            _ = try await api.submitEvents(SubmitEventsRequest(deviceID: UUID(), reportedVia: .wifi, sentAt: Date(), events: [makeEvent()]))
            XCTFail("expected update required")
        } catch {
            XCTAssertEqual(APIError.from(error), .updateRequired)
        }
        XCTAssertEqual(transport.requests.count, sent, "nothing goes out while the update is required")

        // The compatibility check still goes out.
        transport.setHandler { _ in compatibilityJSON(supported: true, updateAvailable: false) }
        _ = try await api.compatibility(platform: .ios, version: "0.1.0")
        XCTAssertEqual(transport.requests.count, sent + 1)
    }

    func testA426WithAnotherCodeIsAnOrdinaryServerError() async {
        let gate = UpdateGate()
        let transport = FakeTransport { _ in .json(426, #"{"error":{"code":"something_else","message":""}}"#) }
        do {
            _ = try await client(transport, gate: gate).myHousehold()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(APIError.from(error).code, "something_else")
        }
        XCTAssertFalse(gate.isUpdateRequired)
    }

    func testA426KeepsTheDeviceSecret() async {
        let store = MemoryDeviceTokenStore("secret")
        let api = APIClient(baseURL: apiURL, transport: FakeTransport { _ in updateRequiredResponse }, credentials: store,
                            appVersionHeader: header, updateGate: UpdateGate())
        _ = try? await api.connectivityStatus()
        XCTAssertEqual(store.token.value, "secret", "an old version is not a removed phone")
    }

    // MARK: Reporter

    func testReporterKeepsEventsAndDoesNotRetryWhileUpdateIsRequired() async throws {
        let directory = TestFiles.temporaryDirectory()
        let queue = EventQueue(directory: directory)
        let sentLog = SentLog(directory: directory)
        await queue.enqueue { makeEvent(sequence: $0) }
        await queue.enqueue { makeEvent(.wifiRestored, sequence: $0) }

        let gate = UpdateGate()
        let transport = FakeTransport { _ in updateRequiredResponse }
        let api = client(transport, gate: gate)
        let deviceID = UUID()
        let reporter = EventReporter(queue: queue, sentLog: sentLog, dependencies: .init(
            submit: { try await api.submitEvents($0) },
            deviceID: { deviceID },
            currentTransport: { .wifi },
            isUpdateRequired: { gate.isUpdateRequired }
        ), schedulesRetries: false)

        let first = await reporter.flush()
        XCTAssertEqual(first.remaining, 2, "events are kept")
        XCTAssertNil(first.nextRetryAt, "no retry is scheduled")
        XCTAssertEqual(transport.requests.count, 1)
        let status = await reporter.currentStatus()
        XCTAssertEqual(status.lastErrorCode, "update_required")

        let second = await reporter.flush(ignoreBackoff: true)
        XCTAssertEqual(second.remaining, 2)
        XCTAssertEqual(transport.requests.count, 1, "the reporter does not send while the update is required")

        // Accepted again (the compatibility check cleared the gate): the queue drains.
        gate.setRequired(false)
        transport.setHandler { request in
            let body = try JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: Any]
            let events = body?["events"] as? [[String: Any]] ?? []
            let outcomes = events.map { #"{"event_id":"\#($0["event_id"] as? String ?? "")","result":"accepted"}"# }
            return .json(200, #"{"server_time":"2026-11-27T10:00:00Z","outcomes":[\#(outcomes.joined(separator: ","))],"retry_after_seconds":null}"#)
        }
        let third = await reporter.flush()
        XCTAssertEqual(third.remaining, 0)
    }

    // MARK: Compatibility

    func testCompatibilityResponseDecodes() async throws {
        let transport = FakeTransport { _ in
            compatibilityJSON(supported: true, updateAvailable: true, updateURL: "https://testflight.apple.com/join/abc")
        }
        let response = try await client(transport, token: nil).compatibility(platform: .ios, version: "0.1.0")
        XCTAssertEqual(response.platform, .ios)
        XCTAssertEqual(response.version, "0.1.0")
        XCTAssertTrue(response.supported)
        XCTAssertTrue(response.updateAvailable)
        XCTAssertEqual(response.minimumVersion, "0.2.0")
        XCTAssertEqual(response.latestVersion, "0.3.0")
        XCTAssertEqual(response.updateURL, "https://testflight.apple.com/join/abc")

        let request = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"), "no credential needed")
        let components = try XCTUnwrap(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.path, "/api/v1/app/compatibility")
        XCTAssertEqual(Set(components.queryItems ?? []), [URLQueryItem(name: "platform", value: "ios"), URLQueryItem(name: "version", value: "0.1.0")])

        let nulls = try WatchdogJSON.decoder().decode(AppCompatibilityOut.self, from: Data(
            #"{"platform":"ios","version":"0.1.0","supported":true,"update_available":false,"minimum_version":null,"latest_version":null,"update_url":null}"#.utf8))
        XCTAssertNil(nulls.updateURL)
        XCTAssertNil(nulls.minimumVersion)
    }

    func testUnsupportedVersionRequiresTheUpdate() async {
        let gate = UpdateGate()
        let api = client(FakeTransport { _ in compatibilityJSON(supported: false, updateAvailable: true, updateURL: "itms-beta://beta.itunes.apple.com/v1/app/1") }, gate: gate)
        let outcome = await Compatibility.check(api: api, gate: gate, version: "0.1.0")
        XCTAssertEqual(outcome?.supported, false)
        XCTAssertEqual(outcome?.updateAvailable, false, "the blocking screen, not the notice")
        XCTAssertEqual(outcome?.updateURL?.scheme, "itms-beta")
        XCTAssertTrue(gate.isUpdateRequired)
    }

    func testSupportedVersionClearsTheGateAndReportsANewerOne() async {
        let gate = UpdateGate(required: true)
        let api = client(FakeTransport { _ in compatibilityJSON(supported: true, updateAvailable: true) }, gate: gate)
        let outcome = await Compatibility.check(api: api, gate: gate, version: "0.1.0")
        XCTAssertEqual(outcome, CompatibilityOutcome(supported: true, updateAvailable: true, updateURL: nil))
        XCTAssertFalse(gate.isUpdateRequired)
    }

    func testFailedCheckChangesNothing() async {
        for required in [false, true] {
            for failure in 0..<3 {
                let transport = FakeTransport { _ in
                    switch failure {
                    case 0: throw URLError(.notConnectedToInternet)
                    case 1: return .json(500, #"{"error":{"code":"internal_error","message":""}}"#)
                    default: return .json(200, "not json")
                    }
                }
                let gate = UpdateGate(required: required)
                let outcome = await Compatibility.check(api: client(transport, gate: gate), gate: gate, version: "0.1.0")
                XCTAssertNil(outcome)
                XCTAssertEqual(gate.isUpdateRequired, required)
            }
        }
    }

    // MARK: Update link

    func testUpdateLinkFallsBackToTestFlight() {
        XCTAssertEqual(UpdateLink.candidates(serverURL: nil).map(\.absoluteString), ["itms-beta://", "https://testflight.apple.com/"])
        let server = URL(string: "https://testflight.apple.com/join/abc")!
        XCTAssertEqual(UpdateLink.candidates(serverURL: server).first, server)
        XCTAssertEqual(UpdateLink.serverURL("https://testflight.apple.com/join/abc"), server)
        XCTAssertNil(UpdateLink.serverURL("http://example.org/app"), "plain http is not opened")
        XCTAssertNil(UpdateLink.serverURL("javascript:alert(1)"))
        XCTAssertNil(UpdateLink.serverURL(""))
        XCTAssertNil(UpdateLink.serverURL(nil))
    }
}
