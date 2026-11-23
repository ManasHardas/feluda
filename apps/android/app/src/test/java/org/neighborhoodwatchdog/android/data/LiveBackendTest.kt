package org.neighborhoodwatchdog.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.neighborhoodwatchdog.android.data.api.ApiError
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.DeviceCapabilities
import org.neighborhoodwatchdog.android.data.api.EnrollRequest
import org.neighborhoodwatchdog.android.data.api.RegisterDeviceRequest
import org.neighborhoodwatchdog.android.data.api.RegisterPushTokenRequest
import org.neighborhoodwatchdog.android.data.api.UpdateHouseholdRequest
import org.neighborhoodwatchdog.android.data.api.VerifyInvitationRequest
import org.neighborhoodwatchdog.android.data.api.WatchdogApi
import org.neighborhoodwatchdog.android.data.api.apiCall
import org.neighborhoodwatchdog.android.data.auth.DeviceTokenInterceptor
import org.neighborhoodwatchdog.android.domain.InMemoryCredentialStore
import org.neighborhoodwatchdog.android.domain.auth.DeviceCredentials
import org.neighborhoodwatchdog.android.domain.home.InvitationToken
import org.neighborhoodwatchdog.android.domain.model.BackgroundMonitoring
import org.neighborhoodwatchdog.android.domain.model.BandEvidence
import org.neighborhoodwatchdog.android.domain.model.ConnectivityEvent
import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.Diagnostics
import org.neighborhoodwatchdog.android.domain.model.EventResult
import org.neighborhoodwatchdog.android.domain.model.EventType
import org.neighborhoodwatchdog.android.domain.model.GapReason
import org.neighborhoodwatchdog.android.domain.model.IncidentScope
import org.neighborhoodwatchdog.android.domain.model.Isp
import org.neighborhoodwatchdog.android.domain.model.ObservationConfidence
import org.neighborhoodwatchdog.android.domain.model.Platform
import org.neighborhoodwatchdog.android.domain.model.PushProvider
import org.neighborhoodwatchdog.android.domain.model.SubmitEventsRequest
import org.neighborhoodwatchdog.android.domain.model.Transport
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID

/**
 * The app's real OkHttp / Retrofit / device-secret stack against a locally running development backend.
 *
 * Skipped unless WATCHDOG_LIVE_API is set. Run it with `make live-android` from the repository root
 * while `make live-backend` is running in another terminal. It is the only test that talks to a
 * real server, and only to localhost. There is no sign-in: the fixture only hands over an
 * invitation link (good for two phones), a street and a house number.
 */
class LiveBackendTest {
    private fun env(name: String): String {
        val value = System.getenv(name).orEmpty()
        assumeTrue("live backend test not configured ($name missing)", value.isNotEmpty())
        return value
    }

    /** One phone: its own secret store and an API client built the way AppModule builds it. */
    private class Phone(origin: String) {
        val deviceId: String = UUID.randomUUID().toString()
        val store = InMemoryCredentialStore()
        val credentials = DeviceCredentials(store)
        var rejections = 0
        val api: WatchdogApi = Retrofit.Builder().baseUrl("$origin/api/v1/")
            .client(OkHttpClient().newBuilder().addInterceptor(DeviceTokenInterceptor(credentials)).build())
            .addConverterFactory(WatchdogJson.asConverterFactory("application/json".toMediaType()))
            .build().create(WatchdogApi::class.java)

        init {
            credentials.addRejectedListener { rejections++ }
        }

        fun enrolRequest(token: String, street: String, houseNumber: String?) = EnrollRequest(
            invitationToken = token, streetName = street, houseNumber = houseNumber, privacyAcknowledged = true, deviceId = deviceId,
            platform = Platform.ANDROID, osVersion = "15", appVersion = "0.1.0", capabilities = CAPABILITIES,
        )
    }

    private fun <T> ok(result: ApiResult<T>): T {
        assertTrue("expected success, got $result", result is ApiResult.Success)
        return (result as ApiResult.Success).value
    }

    private fun <T> failure(result: ApiResult<T>): ApiError {
        assertTrue("expected a failure, got $result", result is ApiResult.Failure)
        return (result as ApiResult.Failure).error
    }

    @Test
    fun wholeResidentJourneyAgainstTheRealBackend() = runBlocking {
        val origin = env("WATCHDOG_LIVE_API").trimEnd('/')
        val invitation = env("WATCHDOG_LIVE_INVITATION")
        val houseNumber = env("WATCHDOG_LIVE_HOUSE_NUMBER")
        val street = env("WATCHDOG_LIVE_STREET")
        // The same parser the join screen uses for a pasted link.
        val token = InvitationToken.parse(invitation)
        assertNotNull("the fixture's invitation link is parsed by the app", token)
        token!!

        val phone = Phone(origin)
        val api = phone.api

        // Public endpoints
        assertEquals("ok", ok(apiCall { api.health() }).database)
        val verified = ok(apiCall { api.verifyInvitation(VerifyInvitationRequest(token)) })
        assertTrue(verified.valid)
        assertTrue(verified.neighborhoodName.isNotEmpty())
        assertTrue(verified.expiresAt.isAfter(Instant.now()))
        assertEquals("invitation_invalid", failure(apiCall { api.verifyInvitation(VerifyInvitationRequest("x".repeat(40))) }).code)

        // Not enrolled yet: no secret, so the API answers 401
        val before = failure(apiCall { api.household() })
        assertEquals(401, before.httpCode)
        assertEquals("unauthorized", before.code)

        // Enrolment is refused for a link that does not exist and for a missing street
        val unknownLink = failure(apiCall { api.enroll(phone.enrolRequest("x".repeat(40), street, houseNumber)) })
        assertEquals(404, unknownLink.httpCode)
        assertEquals("invitation_invalid", unknownLink.code)
        val noStreet = failure(apiCall { api.enroll(phone.enrolRequest(token, "", houseNumber)) })
        assertEquals(422, noStreet.httpCode)
        assertEquals("validation_error", noStreet.code)
        assertFalse(phone.credentials.isEnrolled)

        // Enrol: street and house number, nothing else
        val enrolled = ok(apiCall { api.enroll(phone.enrolRequest(token, street, houseNumber)) })
        assertTrue(enrolled.deviceToken.length >= 32)
        assertEquals(houseNumber, enrolled.household.houseNumber)
        assertEquals(phone.deviceId, enrolled.device.deviceId.lowercase())
        assertEquals(Platform.ANDROID, enrolled.device.platform)
        assertEquals(1, enrolled.household.deviceCount)
        phone.credentials.save(enrolled.deviceToken)

        // Enrolling again with the same device id and link (a lost answer) gives a fresh secret
        // and does not use the link a second time
        val again = ok(apiCall { api.enroll(phone.enrolRequest(token, street, houseNumber)) })
        assertNotEquals(enrolled.deviceToken, again.deviceToken)
        assertEquals(enrolled.household.householdId, again.household.householdId)
        assertEquals(1, again.household.deviceCount)
        phone.credentials.save(again.deviceToken)

        // Authenticated calls with the device secret
        val household = ok(apiCall { api.household() })
        assertEquals(enrolled.household.householdId, household.householdId)
        assertEquals(houseNumber, household.houseNumber)
        assertEquals("Renamed", ok(apiCall { api.updateHousehold(UpdateHouseholdRequest(displayLabel = "Renamed", isp = Isp.XFINITY)) }).displayLabel)

        // Device update (the device itself was created by enrolment), push token, preferences
        val device = ok(apiCall { api.registerDevice(RegisterDeviceRequest(phone.deviceId, Platform.ANDROID, "16", "0.1.1", CAPABILITIES)) })
        assertEquals(phone.deviceId, device.deviceId.lowercase())
        assertEquals("0.1.1", device.appVersion)
        val otherId = UUID.randomUUID().toString()
        val foreign = failure(apiCall { api.registerDevice(RegisterDeviceRequest(otherId, Platform.ANDROID, "16", "0.1.1", CAPABILITIES)) })
        assertTrue("a phone can only update its own record, got $foreign", foreign.httpCode == 409 || foreign.httpCode == 404)
        ok(apiCall { api.registerPushToken(RegisterPushTokenRequest(phone.deviceId, PushProvider.FCM, "f".repeat(64))) })
        val preferences = ok(apiCall { api.notificationPreferences() })
        assertEquals(preferences, ok(apiCall { api.updateNotificationPreferences(preferences) }))
        assertEquals(1, ok(apiCall { api.devices() }).size)

        // A second phone uses the second place on the same link, without a house number. One link is one
        // home: it joins the first phone's household whatever it types, and cannot start another one.
        val second = Phone(origin)
        val secondEnrolled = ok(apiCall { second.api.enroll(second.enrolRequest(token, street, null)) })
        assertEquals(enrolled.household.householdId, secondEnrolled.household.householdId)
        assertEquals("the household keeps what the first phone gave", houseNumber, secondEnrolled.household.houseNumber)
        assertEquals(2, secondEnrolled.household.deviceCount)
        second.credentials.save(secondEnrolled.deviceToken)
        assertEquals(houseNumber, ok(apiCall { second.api.household() }).houseNumber)

        // The link allowed two phones: a third is refused
        val third = Phone(origin)
        val refused = failure(apiCall { third.api.enroll(third.enrolRequest(token, street, null)) })
        assertEquals(404, refused.httpCode)
        assertEquals("invitation_invalid", refused.code)

        // Events: every diagnostics field Android can fill, plus a gap event with dates
        val now = Instant.now()
        val full = Diagnostics(
            wifiAssociated = false, wifiValidated = false, wifiRadioEnabled = true, airplaneMode = false, homeNetworkMatch = true,
            localGatewayReachable = false, internetReachable = true, cellularAvailable = true, defaultTransport = Transport.CELLULAR,
            networkChangedRecently = true, vpnActive = false, lastWifiRssiDbm = -52, bandEvidence = BandEvidence.BAND_5_OBSERVED,
            externalPowerLostRecently = false, batterySaver = false, userInitiated = false,
        )
        val gap = Diagnostics(gapStartedAt = now.minusSeconds(600), gapEndedAt = now.minusSeconds(30), gapReason = GapReason.APP_SUSPENDED)
        fun event(type: EventType, state: ConnectivityState, sequence: Long, offset: Long, diagnostics: Diagnostics,
                  confidence: ObservationConfidence = ObservationConfidence.OBSERVED) =
            ConnectivityEvent(UUID.randomUUID().toString(), sequence, type, state, now.plusSeconds(offset), "16", "0.1.1", confidence, diagnostics)
        val events = listOf(
            event(EventType.MONITORING_GAP_DETECTED, ConnectivityState.UNKNOWN, 1, -30, gap),
            event(EventType.MONITORING_RESUMED, ConnectivityState.HEALTHY, 2, -29, Diagnostics()),
            event(EventType.HEARTBEAT, ConnectivityState.HEALTHY, 3, -20, Diagnostics()),
            event(EventType.WIFI_LOST, ConnectivityState.WIFI_DISCONNECTED, 4, -10, full),
            event(EventType.WIFI_LOST, ConnectivityState.WIFI_DISCONNECTED, 5, -9, full, ObservationConfidence.RECONCILED),
        )
        val request = SubmitEventsRequest(phone.deviceId, Transport.CELLULAR, now, events)
        assertEquals(List(events.size) { EventResult.ACCEPTED }, ok(apiCall { api.submitEvents(request) }).outcomes.map { it.result })
        assertEquals(List(events.size) { EventResult.DUPLICATE }, ok(apiCall { api.submitEvents(request) }).outcomes.map { it.result })
        // Events are only accepted for the phone's own device id
        val foreignEvents = apiCall { api.submitEvents(request.copy(deviceId = second.deviceId)) }
        assertTrue("another phone's device id is refused, got $foreignEvents", foreignEvents is ApiResult.Failure)

        // Status, neighborhood, incidents
        val status = ok(apiCall { api.connectivityStatus() })
        assertEquals(1, status.devices.size)
        assertEquals(phone.deviceId, status.devices.single().deviceId.lowercase())
        assertTrue(status.statusExplanation.isNotEmpty())
        assertTrue(ok(apiCall { api.neighborhood() }).participatingHouseholds > 20)
        val incidents = ok(apiCall { api.incidents("all", 50) }).incidents
        val neighborhoodIncident = incidents.firstOrNull { it.scope == IncidentScope.NEIGHBORHOOD }
        assertNotNull("the fixture replays a correlated disruption", neighborhoodIncident)
        val detail = ok(apiCall { api.incident(neighborhoodIncident!!.incidentId) })
        assertTrue(detail.scoreBreakdown.isNotEmpty())
        assertTrue(detail.explanation.isNotEmpty())
        assertTrue(detail.recommendedActions.isNotEmpty())
        incidents.forEach { incident -> ok(apiCall { api.incident(incident.incidentId) }) }
        ok(apiCall { api.incidents("open", 50) })

        // The second phone leaves: its secret stops working and the app forgets it; the first is unaffected
        ok(apiCall { second.api.leaveHousehold() })
        assertEquals(0, second.rejections)
        val secondAfter = failure(apiCall { second.api.household() })
        assertEquals(401, secondAfter.httpCode)
        assertEquals("unauthorized", secondAfter.code)
        assertFalse("the interceptor removed the refused secret", second.credentials.isEnrolled)
        assertEquals(1, second.rejections)
        ok(apiCall { api.household() })
        assertTrue(phone.credentials.isEnrolled)

        // Leave: the phone and what it reported are deleted, and afterwards the secret is refused
        ok(apiCall { api.leaveHousehold() })
        val after = failure(apiCall { api.household() })
        assertEquals(401, after.httpCode)
        assertEquals("unauthorized", after.code)
        assertNull("the secret was removed from the phone", phone.store.deviceToken)
        assertEquals(1, phone.rejections)
    }

    private companion object {
        val CAPABILITIES = DeviceCapabilities(
            canMeasureRssi = true, canDetectBand = true, canProbeGateway = true, canRouteOverCellular = true,
            canDetectPowerSource = true, canDetectWifiRadioState = true, canIdentifyHomeNetwork = true,
            backgroundMonitoring = BackgroundMonitoring.CALLBACK,
        )
    }
}
