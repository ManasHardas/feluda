package org.neighborhoodwatchdog.android.data

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.DeviceCapabilities
import org.neighborhoodwatchdog.android.data.api.EnrollRequest
import org.neighborhoodwatchdog.android.data.api.VerifyInvitationRequest
import org.neighborhoodwatchdog.android.data.api.WatchdogApi
import org.neighborhoodwatchdog.android.data.api.apiCall
import org.neighborhoodwatchdog.android.data.auth.DeviceTokenInterceptor
import org.neighborhoodwatchdog.android.domain.InMemoryCredentialStore
import org.neighborhoodwatchdog.android.domain.auth.DeviceCredentials
import org.neighborhoodwatchdog.android.domain.model.Platform
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The real OkHttp / Retrofit stack against a local fake of the API: enrolment with an invitation
 * link, the device secret as bearer token, and what happens when the server stops accepting it.
 * There is no sign-in and no token refresh.
 */
class AuthNetworkingTest {
    private val server = MockWebServer()
    private val store = InMemoryCredentialStore()
    private val credentials = DeviceCredentials(store)
    private var rejections = 0
    private lateinit var api: WatchdogApi

    /** The secret the fake API currently accepts. */
    private var acceptedSecret = "secret-1"
    private var unauthorizedBody = """{"error":{"code":"unauthorized","message":"This phone is not enrolled.","request_id":"r-2"}}"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bearer = request.getHeader("Authorization")
                return when (request.path.orEmpty()) {
                    "/api/v1/auth/verify-invitation" ->
                        json(200, """{"valid":true,"neighborhood_name":"Elm Park","expires_at":"2026-12-01T00:00:00Z"}""")
                    "/api/v1/enroll" -> {
                        val body = request.body.clone().readUtf8()
                        when {
                            "\"street_name\":\"Taken Street\"" in body ->
                                json(409, """{"error":{"code":"household_already_registered","message":"This address is already taking part."}}""")
                            else -> json(201, enrolled(acceptedSecret))
                        }
                    }
                    "/api/v1/households/me" -> if (bearer == "Bearer $acceptedSecret") json(200, HOUSEHOLD) else json(401, unauthorizedBody)
                    "/api/v1/households/leave" -> if (bearer == "Bearer $acceptedSecret") json(200, """{"ok":true}""") else json(401, unauthorizedBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        credentials.addRejectedListener { rejections++ }
        // Built the same way as the application's API client (see AppModule).
        val client = OkHttpClient().newBuilder().addInterceptor(DeviceTokenInterceptor(credentials)).build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .client(client)
            .addConverterFactory(WatchdogJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WatchdogApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun enrolled(secret: String) =
        """{"device_token":"$secret","household":$HOUSEHOLD,"device":{"device_id":"$DEVICE_ID","platform":"android",
            "os_version":"Android 14 (API 34)","app_version":"0.1.0 (1)","status":"active","monitoring_enabled":true,
            "last_seen_at":null,"created_at":"2026-11-23T10:00:00Z"}}"""

    private fun enrolRequest(street: String, houseNumber: String?) = EnrollRequest(
        invitationToken = "a".repeat(32),
        streetName = street,
        houseNumber = houseNumber,
        privacyAcknowledged = true,
        deviceId = DEVICE_ID,
        platform = Platform.ANDROID,
        osVersion = "Android 14 (API 34)",
        appVersion = "0.1.0 (1)",
        capabilities = DeviceCapabilities(),
    )

    @Test
    fun publicCallsBeforeEnrolmentCarryNoAuthorizationHeader() = runTest {
        assertTrue(apiCall { api.verifyInvitation(VerifyInvitationRequest("a".repeat(32))) } is ApiResult.Success)
        val verify = server.takeRequest()
        assertEquals("/api/v1/auth/verify-invitation", verify.path)
        assertNull(verify.getHeader("Authorization"))

        val enrolled = apiCall { api.enroll(enrolRequest("Elm Street", null)) } as ApiResult.Success
        val enrol = server.takeRequest()
        assertEquals("POST", enrol.method)
        assertEquals("/api/v1/enroll", enrol.path)
        assertNull(enrol.getHeader("Authorization"))
        val sent = enrol.body.readUtf8()
        assertTrue(sent, "\"privacy_acknowledged\":true" in sent)
        assertFalse("no house number was typed, so none is sent", "house_number" in sent)
        assertEquals("secret-1", enrolled.value.deviceToken)
        assertEquals(1, enrolled.value.household.deviceCount)
    }

    @Test
    fun afterEnrolmentEveryRequestCarriesTheDeviceSecret() = runTest {
        val enrolled = apiCall { api.enroll(enrolRequest("Elm Street", "12")) } as ApiResult.Success
        credentials.save(enrolled.value.deviceToken)
        server.takeRequest()

        assertTrue(apiCall { api.household() } is ApiResult.Success)
        assertEquals("Bearer secret-1", server.takeRequest().getHeader("Authorization"))
        assertEquals(0, rejections)
    }

    @Test
    fun enrolmentRefusedForAnAddressAlreadyTakingPartKeepsTheServerMessage() = runTest {
        val refused = apiCall { api.enroll(enrolRequest("Taken Street", "12")) } as ApiResult.Failure
        assertEquals(409, refused.error.httpCode)
        assertEquals("household_already_registered", refused.error.code)
        assertEquals("This address is already taking part.", refused.error.message)
        assertFalse(credentials.isEnrolled)
    }

    @Test
    fun a401UnauthorizedRemovesTheSecretAndIsNotRetried() = runTest {
        credentials.save("secret-1")
        acceptedSecret = "removed-on-server"

        val result = apiCall { api.household() } as ApiResult.Failure
        assertEquals(401, result.error.httpCode)
        assertEquals("the error body is still readable by the caller", "unauthorized", result.error.code)
        assertEquals("one request, no refresh and no retry", 1, server.requestCount)
        assertNull("the phone is no longer enrolled", store.deviceToken)
        assertEquals(1, rejections)

        // The next request goes out without a header and changes nothing further.
        apiCall { api.household() }
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals(1, rejections)
    }

    @Test
    fun a401ThatIsNotTheApiErrorKeepsTheSecret() = runTest {
        // For example a captive portal or a proxy in between: losing the secret would mean asking for a new link.
        credentials.save("secret-1")
        acceptedSecret = "something-else"
        unauthorizedBody = "<html>Login required</html>"

        val result = apiCall { api.household() } as ApiResult.Failure
        assertEquals(401, result.error.httpCode)
        assertEquals("secret-1", store.deviceToken)
        assertEquals(0, rejections)
    }

    @Test
    fun afterLeavingTheSecretIsRefusedAndRemoved() = runTest {
        credentials.save("secret-1")
        assertTrue(apiCall { api.leaveHousehold() } is ApiResult.Success)
        acceptedSecret = "deleted-with-the-phone"

        val after = apiCall { api.household() } as ApiResult.Failure
        assertEquals("unauthorized", after.error.code)
        assertFalse(credentials.isEnrolled)
    }

    private companion object {
        const val DEVICE_ID = "3f2b8a52-0c1d-4e7a-9b61-5d2f0a9c7e11"
        const val HOUSEHOLD =
            """{"household_id":"7e6d5c4b-3a29-4180-9f0e-1d2c3b4a5968","neighborhood_id":"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","anonymous_reference":"Household 7","display_label":null,"house_number":null,"street_name":"Elm Street","isp":"unknown","membership_status":"active","device_count":1}"""
    }
}
