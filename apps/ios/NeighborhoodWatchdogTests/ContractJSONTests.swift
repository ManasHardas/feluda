import XCTest
@testable import NeighborhoodWatchdog

/// The JSON the app sends and reads must match backend/watchdog/schemas.py and enums.py exactly.
/// The expected names below are copied from those files.
final class ContractJSONTests: XCTestCase {
    private let diagnosticsKeys: Set<String> = [
        "wifi_associated", "wifi_validated", "wifi_radio_enabled", "airplane_mode", "home_network_match",
        "local_gateway_reachable", "internet_reachable", "cellular_available", "default_transport",
        "network_changed_recently", "vpn_active", "last_wifi_rssi_dbm", "band_evidence",
        "external_power_lost_recently", "battery_saver", "user_initiated", "gap_started_at", "gap_ended_at", "gap_reason",
    ]

    private func sampleRequest() -> SubmitEventsRequest {
        var diagnostics = Diagnostics()
        diagnostics.wifiAssociated = false
        diagnostics.homeNetworkMatch = true
        diagnostics.internetReachable = true
        diagnostics.cellularAvailable = true
        diagnostics.defaultTransport = .cellular
        diagnostics.networkChangedRecently = true
        diagnostics.batterySaver = false
        diagnostics.gapStartedAt = Date(timeIntervalSince1970: 1_800_000_000)
        diagnostics.gapEndedAt = Date(timeIntervalSince1970: 1_800_000_600)
        diagnostics.gapReason = .appSuspended
        let event = ConnectivityEvent(
            eventID: UUID(uuidString: "6F9619FF-8B86-D011-B42D-00C04FC964FF")!, sequence: 42, eventType: .monitoringGapDetected,
            connectivityState: .wifiDisconnected, occurredAt: Date(timeIntervalSince1970: 1_800_000_600.25),
            osVersion: "17.4.1", appVersion: "1.0.0 (1)", confidence: .reconciled, diagnostics: diagnostics)
        return SubmitEventsRequest(deviceID: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!, reportedVia: .cellular,
                                   sentAt: Date(timeIntervalSince1970: 1_800_000_700), events: [event])
    }

    func testSubmitEventsRequestFieldNamesAndValues() throws {
        let object = try jsonObject(try WatchdogJSON.encoder().encode(sampleRequest()))
        XCTAssertEqual(Set(object.keys), ["device_id", "reported_via", "sent_at", "events"])
        XCTAssertEqual(object["reported_via"] as? String, "cellular")
        XCTAssertEqual(object["sent_at"] as? String, "2027-01-15T08:11:40.000Z")
        XCTAssertEqual((object["device_id"] as? String)?.lowercased(), "11111111-2222-3333-4444-555555555555")

        let event = try XCTUnwrap((object["events"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(event.keys), ["event_id", "sequence", "event_type", "connectivity_state", "occurred_at",
                                         "os_version", "app_version", "confidence", "diagnostics"])
        XCTAssertEqual(event["event_type"] as? String, "MONITORING_GAP_DETECTED")
        XCTAssertEqual(event["connectivity_state"] as? String, "WIFI_DISCONNECTED")
        XCTAssertEqual(event["confidence"] as? String, "RECONCILED")
        XCTAssertEqual(event["sequence"] as? Int, 42)
        XCTAssertEqual(event["occurred_at"] as? String, "2027-01-15T08:10:00.250Z")
        XCTAssertEqual(event["os_version"] as? String, "17.4.1")
        XCTAssertEqual(event["app_version"] as? String, "1.0.0 (1)")

        let diagnostics = try XCTUnwrap(event["diagnostics"] as? [String: Any])
        XCTAssertEqual(Set(diagnostics.keys), diagnosticsKeys, "exactly the schema's fields, no extras (the server forbids unknown fields)")
        XCTAssertEqual(diagnostics["default_transport"] as? String, "cellular")
        XCTAssertEqual(diagnostics["band_evidence"] as? String, "BAND_UNKNOWN")
        XCTAssertEqual(diagnostics["gap_reason"] as? String, "app_suspended")
        XCTAssertEqual(diagnostics["gap_started_at"] as? String, "2027-01-15T08:00:00.000Z")
        XCTAssertEqual(diagnostics["home_network_match"] as? Bool, true)
        // Not measurable on iOS: explicit null, never a made-up value.
        for key in ["last_wifi_rssi_dbm", "wifi_radio_enabled", "airplane_mode", "local_gateway_reachable", "vpn_active",
                    "wifi_validated", "external_power_lost_recently", "user_initiated"] {
            XCTAssertTrue(diagnostics[key] is NSNull, "\(key) should be null")
        }
    }

    func testEventRoundTripsThroughStorage() throws {
        let request = sampleRequest()
        let decoded = try WatchdogJSON.decoder().decode(SubmitEventsRequest.self, from: try WatchdogJSON.encoder().encode(request))
        XCTAssertEqual(decoded, request)
    }

    func testEnumRawValuesMatchTheContract() {
        XCTAssertEqual(ConnectivityState.allCases.map(\.rawValue), [
            "HEALTHY", "WIFI_DISCONNECTED", "WIFI_CONNECTED_NO_INTERNET", "INTERNET_UNAVAILABLE", "CELLULAR_AVAILABLE",
            "CELLULAR_UNAVAILABLE", "NETWORK_TRANSITION", "MONITORING_PAUSED", "MONITORING_PERMISSION_REQUIRED", "UNKNOWN"])
        XCTAssertEqual(EventType.allCases.map(\.rawValue), [
            "WIFI_LOST", "WIFI_RESTORED", "DEFAULT_NETWORK_CHANGED", "CELLULAR_AVAILABLE", "CELLULAR_UNAVAILABLE",
            "INTERNET_REACHABILITY_CHANGED", "SIGNAL_STRENGTH_CHANGED", "WIFI_ASSOCIATION_CHANGED", "MONITORING_STARTED",
            "MONITORING_RESUMED", "MONITORING_STOPPED", "MONITORING_DISABLED_BY_USER", "MONITORING_GAP_DETECTED",
            "PERMISSION_CHANGED", "HEARTBEAT"])
        XCTAssertEqual([Transport.wifi, .cellular, .ethernet, .vpn, .other, .none, .unknown].map(\.rawValue),
                       ["wifi", "cellular", "ethernet", "vpn", "other", "none", "unknown"])
        XCTAssertEqual([BandEvidence.band24Observed, .band5Observed, .band6Observed, .bandUnknown, .multiBandFailureNotVerified].map(\.rawValue),
                       ["BAND_2_4_OBSERVED", "BAND_5_OBSERVED", "BAND_6_OBSERVED", "BAND_UNKNOWN", "MULTI_BAND_FAILURE_NOT_VERIFIED"])
        XCTAssertEqual([ObservationConfidence.observed, .reconciled].map(\.rawValue), ["OBSERVED", "RECONCILED"])
        XCTAssertEqual([GapReason.appSuspended, .processTerminated, .deviceReboot, .permissionRevoked, .backgroundRestricted, .unknown].map(\.rawValue),
                       ["app_suspended", "process_terminated", "device_reboot", "permission_revoked", "background_restricted", "unknown"])
        XCTAssertEqual([BackgroundMonitoring.callback, .opportunistic, .foregroundOnly].map(\.rawValue),
                       ["callback", "opportunistic", "foreground_only"])
        XCTAssertEqual(Isp.allCases.map(\.rawValue),
                       ["att", "xfinity", "spectrum", "verizon", "tmobile_home", "google_fiber", "other", "unknown"])
        XCTAssertEqual([Classification.normal, .householdConnectivityIssue, .possibleIspOutage, .possibleRouterOrPowerFailure,
                        .possibleWirelessInterference, .correlatedNeighborhoodDisruption, .highPrioritySuspiciousDisruption].map(\.rawValue),
                       ["NORMAL", "HOUSEHOLD_CONNECTIVITY_ISSUE", "POSSIBLE_ISP_OUTAGE", "POSSIBLE_ROUTER_OR_POWER_FAILURE",
                        "POSSIBLE_WIRELESS_INTERFERENCE", "CORRELATED_NEIGHBORHOOD_DISRUPTION", "HIGH_PRIORITY_SUSPICIOUS_DISRUPTION"])
        XCTAssertEqual([StatusColor.green, .yellow, .orange, .red, .grey].map(\.rawValue), ["GREEN", "YELLOW", "ORANGE", "RED", "GREY"])
        XCTAssertEqual([MonitoringStatus.confirmedActive, .knownGap, .paused, .noRecentTelemetry, .failureReported, .neverReported].map(\.rawValue),
                       ["CONFIRMED_ACTIVE", "KNOWN_GAP", "PAUSED", "NO_RECENT_TELEMETRY", "FAILURE_REPORTED", "NEVER_REPORTED"])
        XCTAssertEqual([IncidentStatus.open, .resolved, .expired].map(\.rawValue), ["open", "resolved", "expired"])
        XCTAssertEqual([FailureMode.wifiLost, .noInternet, .allConnectivityLost].map(\.rawValue),
                       ["wifi_lost", "no_internet", "all_connectivity_lost"])
        XCTAssertEqual([EventResult.accepted, .duplicate, .rejected].map(\.rawValue), ["accepted", "duplicate", "rejected"])
        XCTAssertEqual([AlertLevel.normal, .householdWarning, .neighborhoodAnomaly, .highPriority].map(\.rawValue), [0, 1, 2, 3])
        XCTAssertEqual(Platform.ios.rawValue, "ios")
        XCTAssertEqual(PushProvider.apns.rawValue, "apns")
        XCTAssertEqual([ApnsEnvironment.sandbox, .production].map(\.rawValue), ["sandbox", "production"])
    }

    func testRegisterDeviceRequest() throws {
        let request = RegisterDeviceRequest(deviceID: UUID(), platform: .ios, osVersion: "17.4", appVersion: "1.0.0 (1)", capabilities: .iOS)
        let object = try jsonObject(try WatchdogJSON.encoder().encode(request))
        XCTAssertEqual(Set(object.keys), ["device_id", "platform", "os_version", "app_version", "capabilities"])
        XCTAssertEqual(object["platform"] as? String, "ios")
        let capabilities = try XCTUnwrap(object["capabilities"] as? [String: Any])
        XCTAssertEqual(Set(capabilities.keys), ["can_measure_rssi", "can_detect_band", "can_probe_gateway", "can_route_over_cellular",
                                                "can_detect_power_source", "can_detect_wifi_radio_state", "can_identify_home_network",
                                                "background_monitoring"])
        // The app must not claim abilities iOS does not give it.
        XCTAssertEqual(capabilities["can_measure_rssi"] as? Bool, false)
        XCTAssertEqual(capabilities["can_detect_band"] as? Bool, false)
        XCTAssertEqual(capabilities["can_route_over_cellular"] as? Bool, false)
        XCTAssertEqual(capabilities["can_detect_wifi_radio_state"] as? Bool, false)
        XCTAssertEqual(capabilities["background_monitoring"] as? String, "opportunistic")
    }

    func testOtherRequestBodies() throws {
        let push = RegisterPushTokenRequest(deviceID: UUID(), provider: .apns, token: String(repeating: "ab", count: 32), apnsEnvironment: .sandbox)
        let pushObject = try jsonObject(try WatchdogJSON.encoder().encode(push))
        XCTAssertEqual(Set(pushObject.keys), ["device_id", "provider", "token", "apns_environment"])
        XCTAssertEqual(pushObject["provider"] as? String, "apns")
        XCTAssertEqual(pushObject["apns_environment"] as? String, "sandbox")

        // Enrolment: the server rejects unknown fields, and an empty house number is left out.
        let deviceID = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        func enroll(houseNumber: String?) throws -> [String: Any] {
            try jsonObject(try WatchdogJSON.encoder().encode(EnrollRequest(
                invitationToken: "abcdefghijklmnopqrstuvwxyz", streetName: "Elm Street", houseNumber: houseNumber,
                privacyAcknowledged: true, deviceID: deviceID, platform: .ios, osVersion: "17.4", appVersion: "1.0.0 (1)",
                capabilities: .iOS)))
        }
        let required: Set<String> = ["invitation_token", "street_name", "privacy_acknowledged", "device_id", "platform",
                                     "os_version", "app_version", "capabilities"]
        let withoutNumber = try enroll(houseNumber: nil)
        XCTAssertEqual(Set(withoutNumber.keys), required, "no house number: the field is omitted")
        XCTAssertEqual(withoutNumber["privacy_acknowledged"] as? Bool, true)
        XCTAssertEqual(withoutNumber["platform"] as? String, "ios")
        XCTAssertEqual((withoutNumber["device_id"] as? String)?.lowercased(), "11111111-2222-3333-4444-555555555555")
        let withNumber = try enroll(houseNumber: "12")
        XCTAssertEqual(Set(withNumber.keys), required.union(["house_number"]))
        XCTAssertEqual(withNumber["house_number"] as? String, "12")
        XCTAssertEqual(Set(EnrollRequest.CodingKeys.allCases.map(\.rawValue)), required.union(["house_number"]),
                       "nothing but the street and the optional house number is asked of the resident")

        var preferences = NotificationPreferences()
        preferences.quietHoursEnabled = true
        preferences.timezone = "America/Chicago"
        let prefObject = try jsonObject(try WatchdogJSON.encoder().encode(preferences))
        XCTAssertEqual(Set(prefObject.keys), ["household_alerts", "neighborhood_alerts", "quiet_hours_enabled", "quiet_hours_start",
                                              "quiet_hours_end", "timezone"])
        XCTAssertEqual(prefObject["quiet_hours_start"] as? String, "22:00:00")
        XCTAssertEqual(prefObject["quiet_hours_end"] as? String, "07:00:00")
    }

    func testDecodeNotificationPreferences() throws {
        let json = #"{"household_alerts":true,"neighborhood_alerts":false,"quiet_hours_enabled":true,"quiet_hours_start":"21:30:00","quiet_hours_end":"06:15","timezone":"America/Chicago"}"#
        let preferences = try WatchdogJSON.decoder().decode(NotificationPreferences.self, from: Data(json.utf8))
        XCTAssertEqual(preferences.quietHoursStart, TimeOfDay(hour: 21, minute: 30))
        XCTAssertEqual(preferences.quietHoursEnd, TimeOfDay(hour: 6, minute: 15))
        XCTAssertFalse(preferences.neighborhoodAlerts)
        XCTAssertNil(TimeOfDay(string: "25:00:00"))
    }

    func testDecodeIncidentDetail() throws {
        let json = """
        {
          "incident_id": "0b0e7a2c-5a0b-4f0e-9f0a-7d1d6f6d3c11",
          "scope": "neighborhood",
          "classification": "CORRELATED_NEIGHBORHOOD_DISRUPTION",
          "alert_level": 2,
          "status": "open",
          "status_color": "ORANGE",
          "started_at": "2026-11-21T03:14:07.123456+00:00",
          "last_event_at": "2026-11-21T03:16:00Z",
          "resolved_at": null,
          "affected_household_count": 4,
          "active_household_count": 11,
          "approximate_scope": "within about 300 m, north-east part of the neighborhood",
          "correlation_window_s": 180,
          "time_spread_s": 74.5,
          "isp_diversity": {"att": 2, "tmobile_home": 1, "google_fiber": 1},
          "my_household_affected": true,
          "summary": "4 of 11 active households lost Wi-Fi within 75 seconds.",
          "score": 62.5,
          "score_breakdown": [
            {"indicator": "simultaneous_households", "points": 30, "max_points": 40, "detail": "4 households within 75 s"},
            {"indicator": "isp_diversity", "points": 20.0, "max_points": 20.0, "detail": "3 different providers"}
          ],
          "explanation": "Several nearby households on different providers lost Wi-Fi at about the same time.",
          "supporting_evidence": ["4 households affected", "3 internet providers involved"],
          "missing_evidence": ["No direct radio measurement is possible from phones"],
          "recommended_actions": ["Check on your wired devices", "Be attentive"],
          "delayed_report_count": 1,
          "failure_modes": {"wifi_lost": 3, "no_internet": 1}
        }
        """
        let detail = try WatchdogJSON.decoder().decode(IncidentDetailOut.self, from: Data(json.utf8))
        XCTAssertEqual(detail.incident.incidentID, UUID(uuidString: "0B0E7A2C-5A0B-4F0E-9F0A-7D1D6F6D3C11"))
        XCTAssertEqual(detail.incident.scope, .neighborhood)
        XCTAssertEqual(detail.incident.classification, .correlatedNeighborhoodDisruption)
        XCTAssertEqual(detail.incident.alertLevel, .neighborhoodAnomaly)
        XCTAssertEqual(detail.incident.status, .open)
        XCTAssertEqual(detail.incident.statusColor, .orange)
        XCTAssertEqual(detail.incident.startedAt.timeIntervalSince1970, 1_795_230_847.123, accuracy: 0.001)
        XCTAssertEqual(detail.incident.lastEventAt.timeIntervalSince1970, 1_795_230_960, accuracy: 0.001)
        XCTAssertNil(detail.incident.resolvedAt)
        XCTAssertEqual(detail.incident.affectedHouseholdCount, 4)
        XCTAssertEqual(detail.incident.activeHouseholdCount, 11)
        XCTAssertEqual(detail.incident.correlationWindowS, 180)
        XCTAssertEqual(detail.incident.timeSpreadS, 74.5)
        XCTAssertEqual(detail.incident.ispDiversity, ["att": 2, "tmobile_home": 1, "google_fiber": 1],
                       "dictionary keys keep the provider names exactly as sent")
        XCTAssertTrue(detail.incident.myHouseholdAffected)
        XCTAssertEqual(detail.score, 62.5)
        XCTAssertEqual(detail.scoreBreakdown.count, 2)
        XCTAssertEqual(detail.scoreBreakdown[0], ScoreComponent(indicator: "simultaneous_households", points: 30, maxPoints: 40, detail: "4 households within 75 s"))
        XCTAssertEqual(detail.supportingEvidence.count, 2)
        XCTAssertEqual(detail.missingEvidence, ["No direct radio measurement is possible from phones"])
        XCTAssertEqual(detail.recommendedActions.count, 2)
        XCTAssertEqual(detail.delayedReportCount, 1)
        XCTAssertEqual(detail.failureModes, ["wifi_lost": 3, "no_internet": 1])

        // score may be null.
        let unscored = json.replacingOccurrences(of: "\"score\": 62.5", with: "\"score\": null")
        XCTAssertNil(try WatchdogJSON.decoder().decode(IncidentDetailOut.self, from: Data(unscored.utf8)).score)

        // The list endpoint returns the same objects without the detail fields.
        let list = try WatchdogJSON.decoder().decode(IncidentListOut.self, from: Data("{\"incidents\": [\(json)]}".utf8))
        XCTAssertEqual(list.incidents.first?.incidentID, detail.incident.incidentID)
    }

    func testDecodeConnectivityStatus() throws {
        let json = """
        {
          "household_monitoring_status": "KNOWN_GAP",
          "status_color": "GREY",
          "status_explanation": "The phone in this household has not reported for 40 minutes.",
          "highest_alert_level": 0,
          "open_incident_ids": ["0b0e7a2c-5a0b-4f0e-9f0a-7d1d6f6d3c11"],
          "devices": [
            {"device_id": "11111111-2222-3333-4444-555555555555", "platform": "ios", "monitoring_status": "KNOWN_GAP",
             "connectivity_state": "WIFI_CONNECTED_NO_INTERNET", "last_event_at": "2026-11-21T03:14:07.5Z", "last_received_at": null},
            {"device_id": "21111111-2222-3333-4444-555555555555", "platform": "android", "monitoring_status": "CONFIRMED_ACTIVE",
             "connectivity_state": "HEALTHY", "last_event_at": "2026-11-21T03:14:07", "last_received_at": "2026-11-21T03:14:09.000001Z"}
          ],
          "server_time": "2026-11-21T03:54:07.999999Z"
        }
        """
        let status = try WatchdogJSON.decoder().decode(ConnectivityStatusOut.self, from: Data(json.utf8))
        XCTAssertEqual(status.householdMonitoringStatus, .knownGap)
        XCTAssertEqual(status.statusColor, .grey)
        XCTAssertEqual(status.highestAlertLevel, .normal)
        XCTAssertEqual(status.openIncidentIDs.count, 1)
        XCTAssertEqual(status.devices.map(\.platform), [.ios, .android])
        XCTAssertEqual(status.devices[0].connectivityState, .wifiConnectedNoInternet)
        XCTAssertNil(status.devices[0].lastReceivedAt)
        XCTAssertEqual(try XCTUnwrap(status.devices[0].lastEventAt).timeIntervalSince1970, 1_795_230_847.5, accuracy: 0.001)
        XCTAssertEqual(try XCTUnwrap(status.devices[1].lastEventAt).timeIntervalSince1970, 1_795_230_847, accuracy: 0.001)
    }

    func testDecodeOtherResponses() throws {
        let household = #"{"household_id":"11111111-2222-3333-4444-555555555555","neighborhood_id":"21111111-2222-3333-4444-555555555555","anonymous_reference":"Household 7","display_label":null,"house_number":null,"street_name":"Elm Street","isp":"att","membership_status":"active","device_count":3}"#
        let decodedHousehold = try WatchdogJSON.decoder().decode(HouseholdOut.self, from: Data(household.utf8))
        XCTAssertEqual(decodedHousehold.isp, .att)
        XCTAssertNil(decodedHousehold.displayLabel)
        XCTAssertNil(decodedHousehold.houseNumber, "the house number is optional")
        XCTAssertEqual(decodedHousehold.deviceCount, 3)

        let enrolled = #"{"device_token":"s3cret-device-token","household":\#(household.replacingOccurrences(of: #""house_number":null"#, with: #""house_number":"12""#)),"device":{"device_id":"11111111-2222-3333-4444-555555555555","platform":"ios","os_version":"17.4","app_version":"1.0.0 (1)","status":"active","monitoring_enabled":true,"last_seen_at":null,"created_at":"2026-11-01T10:00:00.000000Z"}}"#
        let decodedEnrolment = try WatchdogJSON.decoder().decode(EnrollResponse.self, from: Data(enrolled.utf8))
        XCTAssertEqual(decodedEnrolment.deviceToken, "s3cret-device-token")
        XCTAssertEqual(decodedEnrolment.household.houseNumber, "12")
        XCTAssertEqual(decodedEnrolment.device.platform, .ios)

        let neighborhood = #"{"neighborhood_id":"21111111-2222-3333-4444-555555555555","name":"Maple Grove","participating_households":20,"confirmed_active_households":11,"open_incident_count":1,"highest_alert_level":3,"status_color":"RED","status_explanation":"x","correlation_radius_m":500,"correlation_window_s":180}"#
        let decodedNeighborhood = try WatchdogJSON.decoder().decode(NeighborhoodOut.self, from: Data(neighborhood.utf8))
        XCTAssertEqual(decodedNeighborhood.highestAlertLevel, .highPriority)
        XCTAssertEqual(decodedNeighborhood.correlationRadiusM, 500)

        let submit = #"{"server_time":"2026-11-21T03:54:07Z","outcomes":[{"event_id":"11111111-2222-3333-4444-555555555555","result":"rejected","reason":"rate_limited"},{"event_id":"21111111-2222-3333-4444-555555555555","result":"accepted"}],"retry_after_seconds":30}"#
        let decodedSubmit = try WatchdogJSON.decoder().decode(SubmitEventsResponse.self, from: Data(submit.utf8))
        XCTAssertEqual(decodedSubmit.outcomes.map(\.result), [.rejected, .accepted])
        XCTAssertEqual(decodedSubmit.retryAfterSeconds, 30)
        XCTAssertNil(decodedSubmit.outcomes[1].reason)

        let device = #"[{"device_id":"11111111-2222-3333-4444-555555555555","platform":"ios","os_version":"17.4","app_version":"1.0.0 (1)","status":"active","monitoring_enabled":true,"last_seen_at":null,"created_at":"2026-11-01T10:00:00.000000Z"}]"#
        XCTAssertEqual(try WatchdogJSON.decoder().decode([DeviceOut].self, from: Data(device.utf8)).first?.status, .active)

        let health = #"{"status":"ok","api":"ok","database":"ok","version":"0.1.0","server_time":"2026-11-21T03:54:07.1Z"}"#
        XCTAssertEqual(try WatchdogJSON.decoder().decode(HealthOut.self, from: Data(health.utf8)).status, "ok")

        let invitation = #"{"valid":true,"neighborhood_name":"Maple Grove","expires_at":"2026-11-24T03:54:07+00:00"}"#
        XCTAssertEqual(try WatchdogJSON.decoder().decode(VerifyInvitationResponse.self, from: Data(invitation.utf8)).neighborhoodName, "Maple Grove")
    }

    func testDateFormats() {
        let expected = 1_795_230_847.0
        XCTAssertEqual(try XCTUnwrap(WatchdogDate.date(from: "2026-11-21T03:14:07Z")).timeIntervalSince1970, expected, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(WatchdogDate.date(from: "2026-11-21T03:14:07.250Z")).timeIntervalSince1970, expected + 0.25, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(WatchdogDate.date(from: "2026-11-21T03:14:07.250000+00:00")).timeIntervalSince1970, expected + 0.25, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(WatchdogDate.date(from: "2026-11-20T21:14:07-06:00")).timeIntervalSince1970, expected, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(WatchdogDate.date(from: "2026-11-21T03:14:07.5")).timeIntervalSince1970, expected + 0.5, accuracy: 0.0001)
        XCTAssertNil(WatchdogDate.date(from: "yesterday"))
        XCTAssertEqual(WatchdogDate.string(from: Date(timeIntervalSince1970: expected + 0.25)), "2026-11-21T03:14:07.250Z")
    }

    func testAPIErrorEnvelope() {
        let response = HTTPURLResponse(url: URL(string: "https://api.example.org/api/v1/households/me")!, statusCode: 403,
                                       httpVersion: nil, headerFields: nil)!
        let body = Data(#"{"error":{"code":"household_suspended","message":"This household's membership is suspended.","request_id":"req-1"}}"#.utf8)
        let error = APIError.fromResponse(data: body, response: response)
        XCTAssertEqual(error, .server(status: 403, code: "household_suspended", message: "This household's membership is suspended.", requestID: "req-1", retryAfter: nil))

        let conflict = HTTPURLResponse(url: URL(string: "https://api.example.org/api/v1/enroll")!, statusCode: 409, httpVersion: nil, headerFields: nil)!
        let taken = APIError.fromResponse(
            data: Data(#"{"error":{"code":"household_already_registered","message":"This home already takes part. Ask the organizer for its link."}}"#.utf8),
            response: conflict)
        XCTAssertEqual(taken.userMessage, "This home already takes part. Ask the organizer for its link.", "the server's sentence is shown")

        let limited = HTTPURLResponse(url: URL(string: "https://api.example.org/x")!, statusCode: 429, httpVersion: nil,
                                      headerFields: ["Retry-After": "42"])!
        XCTAssertEqual(APIError.fromResponse(data: Data(), response: limited),
                       .server(status: 429, code: "rate_limited", message: "", requestID: nil, retryAfter: 42))
    }
}
