package org.neighborhoodwatchdog.android.data

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.WatchdogApi
import org.neighborhoodwatchdog.android.data.api.apiCall
import org.neighborhoodwatchdog.android.data.auth.AppVersionInterceptor
import org.neighborhoodwatchdog.android.data.auth.DeviceTokenInterceptor
import org.neighborhoodwatchdog.android.data.update.AppCompatibilityChecker
import org.neighborhoodwatchdog.android.domain.FakeClock
import org.neighborhoodwatchdog.android.domain.InMemoryCredentialStore
import org.neighborhoodwatchdog.android.domain.InMemoryUpdateStore
import org.neighborhoodwatchdog.android.domain.auth.DeviceCredentials
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import org.neighborhoodwatchdog.android.domain.update.AppUpdateGate
import org.neighborhoodwatchdog.android.domain.update.AppVersionHeader
import org.neighborhoodwatchdog.android.ui.common.errorMessage
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** The real OkHttp / Retrofit stack, built as in AppModule, against a local fake of the API. */
class AppVersionNetworkingTest {
    private val server = MockWebServer()
    private val clock = FakeClock()
    private val gate = AppUpdateGate(InMemoryUpdateStore(), "0.1.0 (1)")
    private lateinit var api: WatchdogApi
    private lateinit var base: OkHttpClient

    private var householdResponse: () -> MockResponse = { json(200, "{}") }
    private var compatibilityResponse: () -> MockResponse = { json(200, compatibility(supported = true, updateAvailable = false)) }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/v1/households/me" -> householdResponse()
                "/api/v1/app/compatibility" -> compatibilityResponse()
                else -> MockResponse().setResponseCode(204)
            }
        }
        server.start()
        val apiRoot = server.url("/api/v1/").toString()
        base = OkHttpClient.Builder()
            .addInterceptor(AppVersionInterceptor(apiRoot, AppVersionHeader.value("0.1.0", 1), gate::markRequired))
            .build()
        val client = base.newBuilder().addInterceptor(DeviceTokenInterceptor(DeviceCredentials(InMemoryCredentialStore("secret")))).build()
        api = Retrofit.Builder()
            .baseUrl(apiRoot)
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

    private fun compatibility(supported: Boolean, updateAvailable: Boolean, url: String? = "https://appdistribution.firebase.dev/i/abc") =
        """{"platform":"android","version":"0.1.0","supported":$supported,"update_available":$updateAvailable,
            "minimum_version":"0.1.0","latest_version":"0.2.0","update_url":${url?.let { "\"$it\"" } ?: "null"}}"""

    private val updateRequiredBody =
        """{"error":{"code":"update_required","message":"This version of the app is no longer supported.","request_id":"r-1"}}"""

    @Test
    fun everyApiRequestCarriesTheAppVersionHeader() = runTest {
        apiCall { api.household() }
        assertEquals("android/0.1.0 (1)", server.takeRequest().getHeader("X-App-Version"))
    }

    @Test
    fun requestsOutsideTheApiCarryNoVersion() {
        // The third-party internet probe uses the same base client.
        base.newCall(Request.Builder().url(server.url("/generate_204")).build()).execute().close()
        assertNull(server.takeRequest().getHeader("X-App-Version"))
    }

    @Test
    fun a426UpdateRequiredSetsTheGateAndIsADistinctError() = runTest {
        householdResponse = { json(426, updateRequiredBody) }
        val result = apiCall { api.household() } as ApiResult.Failure
        assertEquals(426, result.error.httpCode)
        assertEquals("update_required", result.error.code)
        assertEquals(org.neighborhoodwatchdog.android.R.string.error_update_required, errorMessage(result.error))
        assertTrue(gate.isRequired)
    }

    @Test
    fun a426InAnotherShapeChangesNothing() = runTest {
        householdResponse = { MockResponse().setResponseCode(426).setBody("<html>Upgrade</html>") }
        apiCall { api.household() }
        assertFalse(gate.isRequired)
    }

    @Test
    fun compatibilityIsDecodedAndAskedWithPlatformAndVersion() = runTest {
        compatibilityResponse = { json(200, compatibility(supported = true, updateAvailable = true)) }
        val checker = AppCompatibilityChecker(api, gate, "0.1.0", clock)
        assertTrue(checker.check())
        val request = server.takeRequest()
        assertEquals("android", request.requestUrl?.queryParameter("platform"))
        assertEquals("0.1.0", request.requestUrl?.queryParameter("version"))
        val state = gate.state.value
        assertFalse(state.required)
        assertTrue(state.showBanner)
        assertEquals("0.2.0", state.latestVersion)
        assertEquals("https://appdistribution.firebase.dev/i/abc", state.updateUrl)
    }

    @Test
    fun notSupportedRequiresTheUpdateAndALaterSupportedAnswerClearsIt() = runTest {
        compatibilityResponse = { json(200, compatibility(supported = false, updateAvailable = true)) }
        val checker = AppCompatibilityChecker(api, gate, "0.1.0", clock)
        checker.check()
        assertTrue(gate.isRequired)

        compatibilityResponse = { json(200, compatibility(supported = true, updateAvailable = false)) }
        clock.now += 61_000 // while required, checks may repeat every minute
        checker.check()
        assertFalse(gate.isRequired)
    }

    @Test
    fun failuresChangeNothing() = runTest {
        gate.markRequired()
        val checker = AppCompatibilityChecker(api, gate, "0.1.0", clock)

        compatibilityResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        assertFalse(checker.check(force = true))
        assertTrue(gate.isRequired)

        compatibilityResponse = { json(500, """{"error":{"code":"internal_error","message":"x"}}""") }
        assertFalse(checker.check(force = true))
        assertTrue(gate.isRequired)

        compatibilityResponse = { json(200, """{"platform":"android","version":"0.1.0"}""") }
        assertFalse("an answer without `supported` is not read as either", checker.check(force = true))
        assertTrue(gate.isRequired)
    }

    @Test
    fun checksAreAtMostHourlyWhileSupported() = runTest {
        val checker = AppCompatibilityChecker(api, gate, "0.1.0", clock)
        assertTrue(checker.check())
        assertFalse(checker.check())
        clock.now += 30 * 60_000
        assertFalse(checker.check())
        clock.now += 31 * 60_000
        assertTrue(checker.check())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aVersionNameThatIsNotSemanticIsNeverChecked() = runTest {
        val checker = AppCompatibilityChecker(api, gate, "0.1.0-dev", clock)
        assertFalse(checker.check())
        assertEquals(0, server.requestCount)
    }
}
