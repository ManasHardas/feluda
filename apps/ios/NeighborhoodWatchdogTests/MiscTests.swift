import XCTest
@testable import NeighborhoodWatchdog

final class InvitationTokenTests: XCTestCase {
    func testAcceptsLinkOrBareToken() {
        let token = "AbCdEfGhIjKlMnOpQrStUvWxYz_0123-456"
        XCTAssertEqual(InvitationToken.parse(token), token)
        XCTAssertEqual(InvitationToken.parse("  \(token)\n"), token)
        XCTAssertEqual(InvitationToken.parse("https://watchdog.example.org/join#t=\(token)"), token)
        XCTAssertEqual(InvitationToken.parse("https://watchdog.example.org/join#x=1&t=\(token)"), token)
        XCTAssertEqual(InvitationToken.parse("feluda://join#t=\(token)"), token)
    }

    func testRejectsAnythingElse() {
        XCTAssertNil(InvitationToken.parse(""))
        XCTAssertNil(InvitationToken.parse("short"))
        XCTAssertNil(InvitationToken.parse("https://watchdog.example.org/join"))
        XCTAssertNil(InvitationToken.parse("https://watchdog.example.org/join#other=1"))
        XCTAssertNil(InvitationToken.parse("this has spaces and is long enough"))
        XCTAssertNil(InvitationToken.parse(String(repeating: "a", count: 129)))
    }
}

final class AppConfigTests: XCTestCase {
    private func info(api: String) -> [String: Any] {
        ["WatchdogAPIBaseURL": api,
         "WatchdogSourceRepoURL": "", "WatchdogProbeFallbackURL": "https://www.apple.com/library/test/success.html",
         "WatchdogAPNsEnvironment": "production"]
    }

    func testReleaseRequiresHTTPS() {
        XCTAssertThrowsError(try AppConfig.make(from: info(api: "http://localhost:8000"), requireHTTPS: true))
        XCTAssertThrowsError(try AppConfig.make(from: info(api: "http://api.example.org"), requireHTTPS: true))
        XCTAssertNoThrow(try AppConfig.make(from: info(api: "https://api.example.org"), requireHTTPS: true))
    }

    func testDebugAllowsPlainHTTPOnlyForLocalhost() throws {
        let config = try AppConfig.make(from: info(api: "http://localhost:8000"), requireHTTPS: false)
        XCTAssertEqual(config.healthURL.absoluteString, "http://localhost:8000/api/v1/monitoring/health")
        XCTAssertNil(config.sourceRepoURL, "an empty value means not configured")
        XCTAssertEqual(config.apnsEnvironment, .production)
        XCTAssertThrowsError(try AppConfig.make(from: info(api: "http://192.168.1.20:8000"), requireHTTPS: false))
    }

    func testMissingValuesAreAnError() {
        XCTAssertThrowsError(try AppConfig.make(from: [:], requireHTTPS: false))
        // An xcconfig URL written without the $() workaround is cut off after "https:".
        XCTAssertThrowsError(try AppConfig.make(from: info(api: "https:"), requireHTTPS: true))
    }

    func testBuiltInfoPlistCarriesTheConfiguredURLs() throws {
        // The test host is the Debug app: this checks the xcconfig -> Info.plist injection, "//" included.
        let config = try AppConfig.load(bundle: .main)
        XCTAssertNotNil(config.apiBaseURL.host)
        // There is no sign-in: no auth service is configured in the build.
        for key in ["WatchdogAuthBaseURL", "WatchdogAuthPublicKey"] {
            XCTAssertNil(Bundle.main.object(forInfoDictionaryKey: key), "\(key) must be gone")
        }
        // The web join page hands the invitation over with feluda://join#t=<token>.
        let urlTypes = Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]]
        XCTAssertEqual(urlTypes?.compactMap { $0["CFBundleURLSchemes"] as? [String] }, [[InvitationLink.scheme]])
        XCTAssertEqual(config.probeFallbackURL?.scheme, "https")
        let identifiers = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String]
        XCTAssertEqual(identifiers, [BackgroundRefresh.identifier])
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String]
        XCTAssertEqual(Set(modes ?? []), ["fetch", "remote-notification"], "no other background mode is declared")
        for key in ["NSLocationWhenInUseUsageDescription", "NSLocationAlwaysAndWhenInUseUsageDescription", "NSLocalNetworkUsageDescription"] {
            XCTAssertNil(Bundle.main.object(forInfoDictionaryKey: key), "\(key) must not be requested")
        }
    }
}

final class ReachabilityProbeTests: XCTestCase {
    private let health = URL(string: "https://api.example.org/api/v1/monitoring/health")!
    private let fallback = URL(string: "https://www.apple.com/library/test/success.html")!

    func testSecondTargetIsUsedWhenTheHealthEndpointDoesNotAnswer() async {
        let any = FakeTransport { request in
            if request.url!.host == "api.example.org" { throw URLError(.cannotConnectToHost) }
            return FakeResponse(status: 200)
        }
        let restricted = FakeTransport { _ in throw URLError(.notConnectedToInternet) }
        let probe = ReachabilityProbe(healthURL: health, fallbackURL: fallback, anyPath: any, withoutCellular: restricted)
        let result = await probe.probe(testWithoutCellular: true)
        XCTAssertEqual(result, ProbeResult(anyPath: true, withoutCellular: false),
                       "reachable over some path, but not without cellular: Wi-Fi has no internet")
        XCTAssertEqual(any.requests.map { $0.url!.host }, ["api.example.org", "www.apple.com"])
        XCTAssertEqual(restricted.requests.count, 2)
    }

    func testNoWifiCheckWhenNotOnWifi() async {
        let any = FakeTransport { _ in FakeResponse(status: 503) }
        let restricted = FakeTransport()
        let probe = ReachabilityProbe(healthURL: health, fallbackURL: fallback, anyPath: any, withoutCellular: restricted)
        let result = await probe.probe(testWithoutCellular: false)
        XCTAssertEqual(result, ProbeResult(anyPath: true, withoutCellular: nil), "any HTTP answer proves the request got out and back")
        XCTAssertTrue(restricted.requests.isEmpty)
        XCTAssertEqual(any.requests.count, 1)
    }

    func testProbeSessionsAreConfiguredAsDocumented() {
        let reporting = WatchdogSessions.reportingConfiguration()
        XCTAssertTrue(reporting.allowsCellularAccess)
        XCTAssertTrue(reporting.allowsExpensiveNetworkAccess)
        XCTAssertTrue(reporting.allowsConstrainedNetworkAccess)
        XCTAssertTrue(reporting.waitsForConnectivity)
        XCTAssertLessThanOrEqual(reporting.timeoutIntervalForResource, 60, "the wait for connectivity is bounded")
        XCTAssertFalse(WatchdogSessions.probeConfiguration(allowCellular: false).allowsCellularAccess)
        XCTAssertTrue(WatchdogSessions.probeConfiguration(allowCellular: true).allowsCellularAccess)
        XCTAssertFalse(WatchdogSessions.probeConfiguration(allowCellular: true).waitsForConnectivity)
    }
}

/// Wording rules: the app reports anomalies. It never states a cause as established and never
/// makes absolute safety claims.
final class TerminologyTests: XCTestCase {
    private let banned = ["jamming confirmed", "confirmed jamming", "jamming detected", "jammer detected", "completely safe",
                          "unhackable", "guaranteed", "100% secure"]

    private func assertClean(_ text: String, _ origin: String, file: StaticString = #filePath, line: UInt = #line) {
        let lowered = text.lowercased()
        for phrase in banned {
            XCTAssertFalse(lowered.contains(phrase), "\"\(phrase)\" found in \(origin)", file: file, line: line)
        }
    }

    func testDisplayNamesAndEducationText() {
        let classifications: [Classification] = [.normal, .householdConnectivityIssue, .possibleIspOutage, .possibleRouterOrPowerFailure,
                                                 .possibleWirelessInterference, .correlatedNeighborhoodDisruption, .highPrioritySuspiciousDisruption]
        for classification in classifications {
            assertClean(classification.displayName, "Classification.\(classification)")
            XCTAssertFalse(classification.displayName.lowercased().contains("jam"))
        }
        for color in [StatusColor.green, .yellow, .orange, .red, .grey] {
            assertClean(color.meaning, "StatusColor.\(color)")
        }
        XCTAssertFalse(StatusColor.grey.meaning.lowercased().contains("safe"), "grey is never presented as safe")
        XCTAssertFalse(StatusColor.grey.meaning.lowercased().contains("healthy"))
        for state in ConnectivityState.allCases { assertClean(state.displayName, "ConnectivityState.\(state)") }
        for topic in EducationContent.topics {
            assertClean(([topic.title] + topic.paragraphs + topic.bullets).joined(separator: " "), "education topic \(topic.id)")
        }
        // The eight things the resident must be told before joining.
        XCTAssertEqual(EducationContent.topics.count, 8)
        assertClean(EducationContent.privacySummary.joined(separator: " "), "privacy summary on the join screen")
        XCTAssertFalse(EducationContent.privacySummary.isEmpty)
    }

    func testUserInterfaceSourcesContainNoBannedPhrases() throws {
        // The simulator shares the Mac's file system, so the sources next to this test file can be read.
        let uiDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("NeighborhoodWatchdog/UI")
        guard let enumerator = FileManager.default.enumerator(at: uiDirectory, includingPropertiesForKeys: nil) else {
            throw XCTSkip("UI sources are not readable from this test run")
        }
        var scanned = 0
        for case let url as URL in enumerator where url.pathExtension == "swift" {
            assertClean(try String(contentsOf: url, encoding: .utf8), url.lastPathComponent)
            scanned += 1
        }
        if scanned == 0 { throw XCTSkip("UI sources are not readable from this test run") }
    }
}
