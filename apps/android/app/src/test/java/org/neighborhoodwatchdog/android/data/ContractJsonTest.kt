package org.neighborhoodwatchdog.android.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.neighborhoodwatchdog.android.data.api.ConnectivityStatusOut
import org.neighborhoodwatchdog.android.data.api.DeviceCapabilities
import org.neighborhoodwatchdog.android.data.api.HouseholdOut
import org.neighborhoodwatchdog.android.data.api.IncidentDetailOut
import org.neighborhoodwatchdog.android.data.api.JoinHouseholdRequest
import org.neighborhoodwatchdog.android.data.api.NeighborhoodOut
import org.neighborhoodwatchdog.android.data.api.NotificationPreferences
import org.neighborhoodwatchdog.android.data.api.RegisterDeviceRequest
import org.neighborhoodwatchdog.android.data.api.RegisterPushTokenRequest
import org.neighborhoodwatchdog.android.data.api.SessionResponse
import org.neighborhoodwatchdog.android.data.api.parseApiError
import org.neighborhoodwatchdog.android.domain.model.BackgroundMonitoring
import org.neighborhoodwatchdog.android.domain.model.BandEvidence
import org.neighborhoodwatchdog.android.domain.model.Classification
import org.neighborhoodwatchdog.android.domain.model.ConnectivityEvent
import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.Diagnostics
import org.neighborhoodwatchdog.android.domain.model.EventResult
import org.neighborhoodwatchdog.android.domain.model.EventType
import org.neighborhoodwatchdog.android.domain.model.GapReason
import org.neighborhoodwatchdog.android.domain.model.IncidentStatus
import org.neighborhoodwatchdog.android.domain.model.InstantSerializer
import org.neighborhoodwatchdog.android.domain.model.Isp
import org.neighborhoodwatchdog.android.domain.model.MonitoringStatus
import org.neighborhoodwatchdog.android.domain.model.ObservationConfidence
import org.neighborhoodwatchdog.android.domain.model.Platform
import org.neighborhoodwatchdog.android.domain.model.PushProvider
import org.neighborhoodwatchdog.android.domain.model.StatusColor
import org.neighborhoodwatchdog.android.domain.model.SubmitEventsRequest
import org.neighborhoodwatchdog.android.domain.model.SubmitEventsResponse
import org.neighborhoodwatchdog.android.domain.model.Transport
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import java.time.Instant

/** Field names and enum values must match backend/watchdog/schemas.py and enums.py exactly. */
class ContractJsonTest {
    private val json = WatchdogJson

    private val sampleRequest = SubmitEventsRequest(
        deviceId = "3f2b8a52-0c1d-4e7a-9b61-5d2f0a9c7e11",
        reportedVia = Transport.CELLULAR,
        sentAt = Instant.parse("2026-11-21T21:45:10.250Z"),
        events = listOf(
            ConnectivityEvent(
                eventId = "9a0e6c1e-7d55-4f0b-8c53-0b6c3a1f2d44",
                sequence = 42,
                eventType = EventType.WIFI_LOST,
                connectivityState = ConnectivityState.WIFI_DISCONNECTED,
                occurredAt = Instant.parse("2026-11-21T21:41:00Z"),
                osVersion = "Android 14 (API 34)",
                appVersion = "0.1.0 (1)",
                confidence = ObservationConfidence.OBSERVED,
                diagnostics = Diagnostics(
                    wifiAssociated = false,
                    wifiRadioEnabled = true,
                    airplaneMode = false,
                    homeNetworkMatch = true,
                    cellularAvailable = true,
                    defaultTransport = Transport.CELLULAR,
                    networkChangedRecently = false,
                    lastWifiRssiDbm = -58,
                    bandEvidence = BandEvidence.BAND_5_OBSERVED,
                ),
            ),
            ConnectivityEvent(
                eventId = "1c7f4b9d-2a63-4c1e-a5d8-6e0f9b3c2a10",
                sequence = 43,
                eventType = EventType.MONITORING_GAP_DETECTED,
                connectivityState = ConnectivityState.HEALTHY,
                occurredAt = Instant.parse("2026-11-21T21:44:00Z"),
                osVersion = "Android 14 (API 34)",
                appVersion = "0.1.0 (1)",
                confidence = ObservationConfidence.RECONCILED,
                diagnostics = Diagnostics(
                    gapStartedAt = Instant.parse("2026-11-21T21:20:00Z"),
                    gapEndedAt = Instant.parse("2026-11-21T21:44:00Z"),
                    gapReason = GapReason.PROCESS_TERMINATED,
                ),
            ),
        ),
    )

    @Test
    fun submitEventsRequestUsesTheContractFieldNames() {
        val tree = json.encodeToJsonElement(SubmitEventsRequest.serializer(), sampleRequest).jsonObject
        assertEquals(setOf("device_id", "reported_via", "sent_at", "events"), tree.keys)
        assertEquals("cellular", tree["reported_via"]!!.jsonPrimitive.content)
        assertEquals("2026-11-21T21:45:10.250Z", tree["sent_at"]!!.jsonPrimitive.content)

        val event = tree["events"]!!.jsonArray[0].jsonObject
        assertEquals(
            setOf(
                "event_id", "sequence", "event_type", "connectivity_state", "occurred_at",
                "os_version", "app_version", "confidence", "diagnostics",
            ),
            event.keys,
        )
        assertEquals("WIFI_LOST", event["event_type"]!!.jsonPrimitive.content)
        assertEquals("WIFI_DISCONNECTED", event["connectivity_state"]!!.jsonPrimitive.content)
        assertEquals("OBSERVED", event["confidence"]!!.jsonPrimitive.content)
        assertEquals("2026-11-21T21:41:00Z", event["occurred_at"]!!.jsonPrimitive.content)
        assertEquals(42, event["sequence"]!!.jsonPrimitive.int)

        val diagnostics = event["diagnostics"]!!.jsonObject
        assertEquals(
            setOf(
                "wifi_associated", "wifi_radio_enabled", "airplane_mode", "home_network_match", "cellular_available",
                "default_transport", "network_changed_recently", "last_wifi_rssi_dbm", "band_evidence",
            ),
            diagnostics.keys,
        )
        assertEquals("BAND_5_OBSERVED", diagnostics["band_evidence"]!!.jsonPrimitive.content)
        assertEquals("cellular", diagnostics["default_transport"]!!.jsonPrimitive.content)
        assertEquals(-58, diagnostics["last_wifi_rssi_dbm"]!!.jsonPrimitive.int)

        val gap = tree["events"]!!.jsonArray[1].jsonObject["diagnostics"]!!.jsonObject
        assertEquals(setOf("band_evidence", "gap_started_at", "gap_ended_at", "gap_reason"), gap.keys)
        assertEquals("process_terminated", gap["gap_reason"]!!.jsonPrimitive.content)
        assertEquals("BAND_UNKNOWN", gap["band_evidence"]!!.jsonPrimitive.content)
    }

    @Test
    fun unmeasuredValuesAreNeverSentAsDefaults() {
        val tree = json.encodeToJsonElement(Diagnostics.serializer(), Diagnostics()).jsonObject
        assertEquals("only band_evidence, which says unknown", setOf("band_evidence"), tree.keys)
        assertFalse(tree.values.any { it is JsonNull })
    }

    @Test
    fun submitEventsRequestRoundTrips() {
        val text = json.encodeToString(SubmitEventsRequest.serializer(), sampleRequest)
        assertEquals(sampleRequest, json.decodeFromString(SubmitEventsRequest.serializer(), text))
    }

    @Test
    fun everyEnumValueMatchesTheBackendSpelling() {
        fun <T> wire(serializer: kotlinx.serialization.KSerializer<T>, values: Iterable<T>) =
            values.map { json.encodeToJsonElement(serializer, it).jsonPrimitive.content }

        assertEquals(
            listOf("wifi", "cellular", "ethernet", "vpn", "other", "none", "unknown"),
            wire(Transport.serializer(), Transport.entries),
        )
        assertEquals(
            listOf("app_suspended", "process_terminated", "device_reboot", "permission_revoked", "background_restricted", "unknown"),
            wire(GapReason.serializer(), GapReason.entries),
        )
        assertEquals(
            listOf("att", "xfinity", "spectrum", "verizon", "tmobile_home", "google_fiber", "other", "unknown"),
            wire(Isp.serializer(), Isp.entries),
        )
        assertEquals(listOf("callback", "opportunistic", "foreground_only"), wire(BackgroundMonitoring.serializer(), BackgroundMonitoring.entries))
        assertEquals(listOf("android", "ios"), wire(Platform.serializer(), Platform.entries))
        assertEquals(listOf("fcm", "apns"), wire(PushProvider.serializer(), PushProvider.entries))
        assertEquals(listOf("accepted", "duplicate", "rejected"), wire(EventResult.serializer(), EventResult.entries))
        assertEquals(listOf("open", "resolved", "expired"), wire(IncidentStatus.serializer(), IncidentStatus.entries))
        assertEquals(
            listOf(
                "HEALTHY", "WIFI_DISCONNECTED", "WIFI_CONNECTED_NO_INTERNET", "INTERNET_UNAVAILABLE", "CELLULAR_AVAILABLE",
                "CELLULAR_UNAVAILABLE", "NETWORK_TRANSITION", "MONITORING_PAUSED", "MONITORING_PERMISSION_REQUIRED", "UNKNOWN",
            ),
            wire(ConnectivityState.serializer(), ConnectivityState.entries),
        )
        assertEquals(
            listOf(
                "WIFI_LOST", "WIFI_RESTORED", "DEFAULT_NETWORK_CHANGED", "CELLULAR_AVAILABLE", "CELLULAR_UNAVAILABLE",
                "INTERNET_REACHABILITY_CHANGED", "SIGNAL_STRENGTH_CHANGED", "WIFI_ASSOCIATION_CHANGED", "MONITORING_STARTED",
                "MONITORING_RESUMED", "MONITORING_STOPPED", "MONITORING_DISABLED_BY_USER", "MONITORING_GAP_DETECTED",
                "PERMISSION_CHANGED", "HEARTBEAT",
            ),
            wire(EventType.serializer(), EventType.entries),
        )
        assertEquals(
            listOf("BAND_2_4_OBSERVED", "BAND_5_OBSERVED", "BAND_6_OBSERVED", "BAND_UNKNOWN", "MULTI_BAND_FAILURE_NOT_VERIFIED"),
            wire(BandEvidence.serializer(), BandEvidence.entries),
        )
        assertEquals(listOf("GREEN", "YELLOW", "ORANGE", "RED", "GREY"), wire(StatusColor.serializer(), StatusColor.entries))
    }

    @Test
    fun submitEventsResponseIsParsed() {
        val response = json.decodeFromString(
            SubmitEventsResponse.serializer(),
            """
            {"server_time":"2026-11-21T21:45:11.123456Z",
             "outcomes":[
               {"event_id":"9a0e6c1e-7d55-4f0b-8c53-0b6c3a1f2d44","result":"accepted","reason":null},
               {"event_id":"1c7f4b9d-2a63-4c1e-a5d8-6e0f9b3c2a10","result":"rejected","reason":"rate_limited"}],
             "retry_after_seconds":30}
            """.trimIndent(),
        )
        assertEquals(EventResult.ACCEPTED, response.outcomes[0].result)
        assertNull(response.outcomes[0].reason)
        assertEquals("rate_limited", response.outcomes[1].reason)
        assertEquals(30, response.retryAfterSeconds)
        assertEquals(Instant.parse("2026-11-21T21:45:11.123456Z"), response.serverTime)
    }

    @Test
    fun timestampsAreReadWithOffsetOrWithoutAndWrittenWithZ() {
        assertEquals(Instant.parse("2026-11-21T21:41:00Z"), InstantSerializer.parse("2026-11-21T21:41:00+00:00"))
        assertEquals(Instant.parse("2026-11-21T21:41:00Z"), InstantSerializer.parse("2026-11-21T15:41:00-06:00"))
        assertEquals("a value without an offset is UTC", Instant.parse("2026-11-21T21:41:00Z"), InstantSerializer.parse("2026-11-21T21:41:00"))
        assertEquals(JsonPrimitive("2026-11-21T21:41:00Z"), json.encodeToJsonElement(InstantSerializer, Instant.parse("2026-11-21T21:41:00Z")))
    }

    @Test
    fun registrationRequestsUseTheContractFieldNames() {
        val device = json.encodeToJsonElement(
            RegisterDeviceRequest.serializer(),
            RegisterDeviceRequest(
                deviceId = "3f2b8a52-0c1d-4e7a-9b61-5d2f0a9c7e11",
                platform = Platform.ANDROID,
                osVersion = "Android 14 (API 34)",
                appVersion = "0.1.0 (1)",
                capabilities = DeviceCapabilities(canMeasureRssi = true, backgroundMonitoring = BackgroundMonitoring.CALLBACK),
            ),
        ).jsonObject
        assertEquals(setOf("device_id", "platform", "os_version", "app_version", "capabilities"), device.keys)
        assertEquals("android", device["platform"]!!.jsonPrimitive.content)
        assertEquals(
            setOf(
                "can_measure_rssi", "can_detect_band", "can_probe_gateway", "can_route_over_cellular", "can_detect_power_source",
                "can_detect_wifi_radio_state", "can_identify_home_network", "background_monitoring",
            ),
            device["capabilities"]!!.jsonObject.keys,
        )
        assertEquals("callback", device["capabilities"]!!.jsonObject["background_monitoring"]!!.jsonPrimitive.content)

        val join = json.encodeToJsonElement(
            JoinHouseholdRequest.serializer(),
            JoinHouseholdRequest("a".repeat(24), "12", "Elm Street", null, Isp.TMOBILE_HOME, true),
        ).jsonObject
        assertEquals(setOf("invitation_token", "house_number", "street_name", "isp", "privacy_acknowledged"), join.keys)
        assertEquals("tmobile_home", join["isp"]!!.jsonPrimitive.content)

        val push = json.encodeToJsonElement(
            RegisterPushTokenRequest.serializer(),
            RegisterPushTokenRequest("3f2b8a52-0c1d-4e7a-9b61-5d2f0a9c7e11", PushProvider.FCM, "t".repeat(32)),
        ).jsonObject
        assertEquals(setOf("device_id", "provider", "token"), push.keys)
        assertEquals("fcm", push["provider"]!!.jsonPrimitive.content)
    }

    @Test
    fun notificationPreferencesRoundTripWithTimeStrings() {
        val text = """{"household_alerts":true,"neighborhood_alerts":false,"quiet_hours_enabled":true,
            "quiet_hours_start":"22:30:00","quiet_hours_end":"07:00:00","timezone":"America/Chicago"}"""
        val parsed = json.decodeFromString(NotificationPreferences.serializer(), text)
        assertEquals("22:30:00", parsed.quietHoursStart)
        assertFalse(parsed.neighborhoodAlerts)
        val tree = json.encodeToJsonElement(NotificationPreferences.serializer(), parsed).jsonObject
        assertEquals(
            setOf("household_alerts", "neighborhood_alerts", "quiet_hours_enabled", "quiet_hours_start", "quiet_hours_end", "timezone"),
            tree.keys,
        )
        assertEquals(parsed, json.decodeFromJsonElement(NotificationPreferences.serializer(), tree))
    }

    @Test
    fun statusAndIncidentResponsesAreParsed() {
        val status = json.decodeFromString(
            ConnectivityStatusOut.serializer(),
            """{"household_monitoring_status":"KNOWN_GAP","status_color":"GREY","status_explanation":"Monitoring gap.",
                "highest_alert_level":0,"open_incident_ids":["5b1d3c2e-8f4a-4c6b-9d7e-1a2b3c4d5e6f"],
                "devices":[{"device_id":"3f2b8a52-0c1d-4e7a-9b61-5d2f0a9c7e11","platform":"android",
                  "monitoring_status":"KNOWN_GAP","connectivity_state":"WIFI_DISCONNECTED",
                  "last_event_at":"2026-11-21T21:41:00Z","last_received_at":null}],
                "server_time":"2026-11-21T21:45:11Z","a_field_added_later":123}""",
        )
        assertEquals(MonitoringStatus.KNOWN_GAP, status.householdMonitoringStatus)
        assertEquals(StatusColor.GREY, status.statusColor)
        assertEquals(ConnectivityState.WIFI_DISCONNECTED, status.devices.single().connectivityState)
        assertNull(status.devices.single().lastReceivedAt)

        val incident = json.decodeFromString(
            IncidentDetailOut.serializer(),
            """{"incident_id":"5b1d3c2e-8f4a-4c6b-9d7e-1a2b3c4d5e6f","scope":"neighborhood",
                "classification":"CORRELATED_NEIGHBORHOOD_DISRUPTION","alert_level":2,"status":"open","status_color":"ORANGE",
                "started_at":"2026-11-21T21:40:00Z","last_event_at":"2026-11-21T21:42:00Z","resolved_at":null,
                "affected_household_count":4,"active_household_count":9,"approximate_scope":"Within about 300 m",
                "correlation_window_s":120,"time_spread_s":47.5,"isp_diversity":{"att":2,"xfinity":2},
                "my_household_affected":true,"summary":"4 households lost Wi-Fi within 48 s.",
                "score":61.5,"score_breakdown":[{"indicator":"isp_diversity","points":20.0,"max_points":20.0,"detail":"Two providers."}],
                "explanation":"Several households on different providers.","supporting_evidence":["Two providers affected"],
                "missing_evidence":["No report from 5 households"],"recommended_actions":["Check your router"],
                "delayed_report_count":1,"failure_modes":{"wifi_lost":3,"no_internet":1}}""",
        )
        assertEquals(Classification.CORRELATED_NEIGHBORHOOD_DISRUPTION, incident.classification)
        assertEquals(IncidentStatus.OPEN, incident.status)
        assertEquals(mapOf("att" to 2, "xfinity" to 2), incident.ispDiversity)
        assertEquals(20.0, incident.scoreBreakdown.single().maxPoints, 0.0)
        assertEquals(mapOf("wifi_lost" to 3, "no_internet" to 1), incident.failureModes)
    }

    @Test
    fun householdAndNeighborhoodResponsesAreParsed() {
        val household = json.decodeFromString(
            HouseholdOut.serializer(),
            """{"household_id":"7e6d5c4b-3a29-4180-9f0e-1d2c3b4a5968","neighborhood_id":"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
                "anonymous_reference":"Household 7","display_label":null,"house_number":"12","street_name":"Elm Street",
                "isp":"google_fiber","membership_status":"active","role":"resident","member_count":2,"device_count":3}""",
        )
        assertEquals(Isp.GOOGLE_FIBER, household.isp)
        assertNull(household.displayLabel)

        val neighborhood = json.decodeFromString(
            NeighborhoodOut.serializer(),
            """{"neighborhood_id":"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","name":"Elm Park","participating_households":12,
                "confirmed_active_households":9,"open_incident_count":1,"highest_alert_level":2,"status_color":"ORANGE",
                "status_explanation":"Multiple nearby connectivity disruptions.","correlation_radius_m":500,"correlation_window_s":120}""",
        )
        assertEquals(2, neighborhood.highestAlertLevel)
        assertEquals(StatusColor.ORANGE, neighborhood.statusColor)
    }

    @Test
    fun enumValueFromANewerServerDoesNotBreakResponses() {
        val neighborhood = json.decodeFromString(
            NeighborhoodOut.serializer(),
            """{"neighborhood_id":"n","name":"Elm Park","participating_households":1,"confirmed_active_households":0,
                "open_incident_count":0,"highest_alert_level":0,"status_color":"PURPLE","status_explanation":"New.",
                "correlation_radius_m":500,"correlation_window_s":120}""",
        )
        assertEquals("an unknown colour is shown as grey (unknown), never as green", StatusColor.GREY, neighborhood.statusColor)
    }

    @Test
    fun errorBodyAndAuthSessionAreParsed() {
        val error = parseApiError(403, """{"error":{"code":"not_a_member","message":"No household.","request_id":"r-1"}}""", null)
        assertEquals("not_a_member", error.code)
        assertEquals(403, error.httpCode)
        assertFalse(error.isNetwork)
        val limited = parseApiError(429, """{"error":{"code":"rate_limited","message":"Slow down."}}""", "17")
        assertEquals(17, limited.retryAfterSeconds)
        assertNull("a body that is not the API error shape gives no code", parseApiError(502, "<html>Bad gateway</html>", null).code)

        val session = json.decodeFromString(
            SessionResponse.serializer(),
            """{"access_token":"a.b.c","refresh_token":"r1","expires_in":3600,"token_type":"bearer",
                "user":{"id":"u-1","email":"resident@example.org","aud":"authenticated"}}""",
        )
        assertEquals(3600, session.expiresIn)
        assertEquals("resident@example.org", session.user?.email)
        assertTrue(JsonObject(emptyMap()).isEmpty())
    }
}
