package org.neighborhoodwatchdog.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.AuthApi
import org.neighborhoodwatchdog.android.data.api.DeviceCapabilities
import org.neighborhoodwatchdog.android.data.api.JoinHouseholdRequest
import org.neighborhoodwatchdog.android.data.api.RegisterDeviceRequest
import org.neighborhoodwatchdog.android.data.api.RegisterPushTokenRequest
import org.neighborhoodwatchdog.android.data.api.RevokeDeviceRequest
import org.neighborhoodwatchdog.android.data.api.UpdateHouseholdRequest
import org.neighborhoodwatchdog.android.data.api.VerifyInvitationRequest
import org.neighborhoodwatchdog.android.data.api.WatchdogApi
import org.neighborhoodwatchdog.android.data.api.apiCall
import org.neighborhoodwatchdog.android.data.auth.ApiKeyInterceptor
import org.neighborhoodwatchdog.android.data.auth.AuthRepository
import org.neighborhoodwatchdog.android.data.auth.BearerInterceptor
import org.neighborhoodwatchdog.android.data.auth.RemoteTokenRefresher
import org.neighborhoodwatchdog.android.data.auth.TokenAuthenticator
import org.neighborhoodwatchdog.android.domain.FakeClock
import org.neighborhoodwatchdog.android.domain.InMemoryTokenStore
import org.neighborhoodwatchdog.android.domain.auth.TokenManager
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
 * The app's real OkHttp / Retrofit / token stack against a locally running development backend.
 *
 * Skipped unless WATCHDOG_LIVE_API is set. Run it with `make live-android` from the repository root
 * while `make live-backend` is running in another terminal. It is the only test that talks to a
 * real server, and only to localhost.
 */
class LiveBackendTest {
    private fun env(name: String): String {
        val value = System.getenv(name).orEmpty()
        assumeTrue("live backend test not configured ($name missing)", value.isNotEmpty())
        return value
    }

    @Test
    fun wholeResidentJourneyAgainstTheRealBackend() = runBlocking {
        val origin = env("WATCHDOG_LIVE_API").trimEnd('/')
        val invitation = env("WATCHDOG_LIVE_INVITATION")
        val email = env("WATCHDOG_LIVE_EMAIL")
        val code = env("WATCHDOG_LIVE_CODE")
        val houseNumber = env("WATCHDOG_LIVE_HOUSE_NUMBER")
        val street = env("WATCHDOG_LIVE_STREET")
        val token = invitation.substringAfter("#t=")

        val clock = FakeClock(System.currentTimeMillis())
        val store = InMemoryTokenStore()
        val base = OkHttpClient()
        val converter = WatchdogJson.asConverterFactory("application/json".toMediaType())
        val authApi = Retrofit.Builder().baseUrl("$origin/auth/v1/")
            .client(base.newBuilder().addInterceptor(ApiKeyInterceptor("local")).build())
            .addConverterFactory(converter).build().create(AuthApi::class.java)
        val tokens = TokenManager(store, RemoteTokenRefresher(authApi, clock), clock)
        val auth = AuthRepository(authApi, tokens, clock)
        val api = Retrofit.Builder().baseUrl("$origin/api/v1/")
            .client(base.newBuilder().addInterceptor(BearerInterceptor(tokens)).authenticator(TokenAuthenticator(tokens)).build())
            .addConverterFactory(converter).build().create(WatchdogApi::class.java)

        fun <T> ok(result: ApiResult<T>): T {
            assertTrue("expected success, got $result", result is ApiResult.Success)
            return (result as ApiResult.Success).value
        }

        // Public endpoints
        assertEquals("ok", ok(apiCall { api.health() }).database)
        assertTrue(ok(apiCall { api.verifyInvitation(VerifyInvitationRequest(token)) }).householdBound)

        // Sign in with the e-mail code
        assertTrue(auth.requestCode(email) is ApiResult.Success)
        assertTrue(auth.verifyCode(email, code) is ApiResult.Success)
        assertTrue(auth.isSignedIn)

        // Not a member yet
        val before = apiCall { api.household() }
        assertEquals("not_a_member", (before as ApiResult.Failure).error.code)

        // Join
        val joined = ok(apiCall {
            api.joinHousehold(
                JoinHouseholdRequest(
                    invitationToken = token, houseNumber = houseNumber, streetName = street, displayLabel = "Live test",
                    isp = Isp.XFINITY, privacyAcknowledged = true,
                ),
            )
        })
        assertEquals(houseNumber, joined.houseNumber)
        ok(apiCall { api.household() })
        ok(apiCall { api.updateHousehold(UpdateHouseholdRequest(displayLabel = "Renamed")) })

        // Device, push token, preferences
        val deviceId = UUID.randomUUID().toString()
        val capabilities = DeviceCapabilities(
            canMeasureRssi = true, canDetectBand = true, canProbeGateway = true, canRouteOverCellular = true,
            canDetectPowerSource = true, canDetectWifiRadioState = true, canIdentifyHomeNetwork = true,
            backgroundMonitoring = BackgroundMonitoring.CALLBACK,
        )
        val device = ok(apiCall { api.registerDevice(RegisterDeviceRequest(deviceId, Platform.ANDROID, "15", "0.1.0", capabilities)) })
        assertEquals(deviceId, device.deviceId.lowercase())
        ok(apiCall { api.registerPushToken(RegisterPushTokenRequest(deviceId, PushProvider.FCM, "f".repeat(64))) })
        val preferences = ok(apiCall { api.notificationPreferences() })
        assertEquals(preferences, ok(apiCall { api.updateNotificationPreferences(preferences) }))
        assertEquals(1, ok(apiCall { api.devices() }).size)

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
            ConnectivityEvent(UUID.randomUUID().toString(), sequence, type, state, now.plusSeconds(offset), "15", "0.1.0", confidence, diagnostics)
        val events = listOf(
            event(EventType.MONITORING_GAP_DETECTED, ConnectivityState.UNKNOWN, 1, -30, gap),
            event(EventType.MONITORING_RESUMED, ConnectivityState.HEALTHY, 2, -29, Diagnostics()),
            event(EventType.HEARTBEAT, ConnectivityState.HEALTHY, 3, -20, Diagnostics()),
            event(EventType.WIFI_LOST, ConnectivityState.WIFI_DISCONNECTED, 4, -10, full),
            event(EventType.WIFI_LOST, ConnectivityState.WIFI_DISCONNECTED, 5, -9, full, ObservationConfidence.RECONCILED),
        )
        val request = SubmitEventsRequest(deviceId, Transport.CELLULAR, now, events)
        assertEquals(List(events.size) { EventResult.ACCEPTED }, ok(apiCall { api.submitEvents(request) }).outcomes.map { it.result })
        assertEquals(List(events.size) { EventResult.DUPLICATE }, ok(apiCall { api.submitEvents(request) }).outcomes.map { it.result })

        // Status, neighborhood, incidents
        val status = ok(apiCall { api.connectivityStatus() })
        assertEquals(1, status.devices.size)
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

        // Token refresh with rotation against the auth endpoints
        val oldTokens = store.tokens!!
        assertNotNull(tokens.refreshAfterUnauthorized(oldTokens.accessToken))
        assertNotEquals(oldTokens.refreshToken, store.tokens!!.refreshToken)
        ok(apiCall { api.household() })

        // Revoke and leave
        ok(apiCall { api.revokeDevice(RevokeDeviceRequest(deviceId)) })
        ok(apiCall { api.leaveHousehold() })
        val after = apiCall { api.household() }
        assertEquals("not_a_member", (after as ApiResult.Failure).error.code)
        assertFalse(after is ApiResult.Success<*>)
    }
}
