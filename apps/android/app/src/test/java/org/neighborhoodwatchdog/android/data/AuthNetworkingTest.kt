package org.neighborhoodwatchdog.android.data

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.AuthApi
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
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** The real OkHttp / Retrofit stack against a local fake of the auth service and the API. */
class AuthNetworkingTest {
    private val server = MockWebServer()
    private val clock = FakeClock()
    private val store = InMemoryTokenStore()
    private lateinit var tokens: TokenManager
    private lateinit var auth: AuthRepository
    private lateinit var api: WatchdogApi
    private lateinit var apiClient: OkHttpClient

    /** The access token the fake API currently accepts, and the refresh token the fake auth accepts. */
    private var acceptedAccess = "access-1"
    private var acceptedRefresh = "refresh-1"
    private var refreshCalls = 0

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/auth/v1/otp" -> json(200, "{}")
                    path == "/auth/v1/verify" -> {
                        val ok = request.body.clone().readUtf8().contains("\"token\":\"123456\"")
                        if (ok) json(200, session("access-1", "refresh-1")) else json(403, """{"code":403,"error_code":"otp_expired","msg":"Token has expired or is invalid"}""")
                    }
                    path == "/auth/v1/token?grant_type=refresh_token" -> {
                        refreshCalls++
                        if (request.body.clone().readUtf8().contains("\"refresh_token\":\"$acceptedRefresh\"")) {
                            acceptedAccess = "access-${refreshCalls + 1}"
                            acceptedRefresh = "refresh-${refreshCalls + 1}"
                            json(200, session(acceptedAccess, acceptedRefresh))
                        } else {
                            json(400, """{"code":400,"error_code":"refresh_token_already_used","msg":"Already used"}""")
                        }
                    }
                    path == "/auth/v1/logout" -> MockResponse().setResponseCode(204)
                    path == "/api/v1/households/me" ->
                        if (request.getHeader("Authorization") == "Bearer $acceptedAccess") {
                            json(403, """{"error":{"code":"not_a_member","message":"No household.","request_id":"r-1"}}""")
                        } else {
                            json(401, """{"error":{"code":"unauthorized","message":"Invalid token.","request_id":"r-2"}}""")
                        }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val base = OkHttpClient()
        val converter = WatchdogJson.asConverterFactory("application/json".toMediaType())
        val authApi = Retrofit.Builder()
            .baseUrl(server.url("/auth/v1/"))
            .client(base.newBuilder().addInterceptor(ApiKeyInterceptor("public-anon-key")).build())
            .addConverterFactory(converter)
            .build()
            .create(AuthApi::class.java)
        tokens = TokenManager(store, RemoteTokenRefresher(authApi, clock), clock)
        auth = AuthRepository(authApi, tokens, clock)
        apiClient = base.newBuilder().addInterceptor(BearerInterceptor(tokens)).authenticator(TokenAuthenticator(tokens)).build()
        api = Retrofit.Builder().baseUrl(server.url("/api/v1/")).client(apiClient).addConverterFactory(converter).build()
            .create(WatchdogApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun session(access: String, refresh: String) =
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"token_type":"bearer","user":{"id":"u-1","email":"resident@example.org"}}"""

    @Test
    fun emailCodeSignInStoresTokensAndSendsTheApiKey() = runTest {
        assertTrue(auth.requestCode(" resident@example.org ") is ApiResult.Success)
        val otp = server.takeRequest()
        assertEquals("/auth/v1/otp", otp.path)
        assertEquals("public-anon-key", otp.getHeader("apikey"))
        assertEquals("""{"email":"resident@example.org","create_user":true}""", otp.body.readUtf8())

        assertTrue("a wrong code is refused", auth.verifyCode("resident@example.org", "000000") is ApiResult.Failure)
        assertNull(store.tokens)
        server.takeRequest()

        assertTrue(auth.verifyCode("resident@example.org", "123456") is ApiResult.Success)
        assertEquals("""{"type":"email","email":"resident@example.org","token":"123456"}""", server.takeRequest().body.readUtf8())
        assertEquals("access-1", store.tokens?.accessToken)
        assertEquals("refresh-1", store.tokens?.refreshToken)
        assertEquals(clock.now + 3_600_000, store.tokens?.expiresAtMs)
        assertTrue(auth.isSignedIn)
    }

    @Test
    fun apiRequestsCarryTheBearerTokenAndErrorCodesAreRead() = runTest {
        auth.verifyCode("resident@example.org", "123456")
        server.takeRequest()
        val result = apiCall { api.household() }
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
        val failure = result as ApiResult.Failure
        assertEquals("not_a_member", failure.error.code)
        assertEquals(403, failure.error.httpCode)
    }

    @Test
    fun a401RefreshesOnceRotatesTheRefreshTokenAndRetriesOnce() = runTest {
        auth.verifyCode("resident@example.org", "123456")
        server.takeRequest()
        acceptedAccess = "revoked-on-server" // the API no longer accepts access-1

        val result = apiCall { api.household() } as ApiResult.Failure
        assertEquals("the retry with the new token reached the API", "not_a_member", result.error.code)
        assertEquals(1, refreshCalls)
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", refresh.path)
        assertEquals("""{"refresh_token":"refresh-1"}""", refresh.body.readUtf8())
        assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"))
        assertEquals("refresh-2", store.tokens?.refreshToken)
    }

    @Test
    fun a401AfterTheRetryIsNotRetriedAgain() = runTest {
        auth.verifyCode("resident@example.org", "123456")
        server.takeRequest()
        val before = server.requestCount
        // The API rejects every token: one refresh, one retry, then the 401 is returned.
        server.dispatcher = object : Dispatcher() {
            val original = server.dispatcher
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api/v1/households/me") {
                    json(401, """{"error":{"code":"unauthorized","message":"Invalid token."}}""")
                } else {
                    original.dispatch(request)
                }
        }
        val result = apiCall { api.household() } as ApiResult.Failure
        assertEquals(401, result.error.httpCode)
        assertEquals("unauthorized", result.error.code)
        assertEquals("request, refresh, one retry", 3, server.requestCount - before)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun refusedRefreshTokenSignsOutAndTheRequestFailsWith401() = runTest {
        auth.verifyCode("resident@example.org", "123456")
        acceptedAccess = "revoked-on-server"
        acceptedRefresh = "something-else" // refresh-1 was already used elsewhere
        val result = apiCall { api.household() } as ApiResult.Failure
        assertEquals(401, result.error.httpCode)
        assertNull("the session is gone", store.tokens)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun parallelRequestsWithAnExpiredTokenRefreshOnlyOnce() {
        store.save(org.neighborhoodwatchdog.android.domain.auth.AuthTokens("access-1", "refresh-1", clock.now - 1))
        val threads = List(6) {
            Thread {
                apiClient.newCall(Request.Builder().url(server.url("/api/v1/households/me")).build()).execute().close()
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals("six requests, one refresh", 1, refreshCalls)
        assertEquals("refresh-2", store.tokens?.refreshToken)
    }

    @Test
    fun signOutCallsLogoutAndClearsTokens() = runTest {
        auth.verifyCode("resident@example.org", "123456")
        server.takeRequest()
        auth.signOut()
        val logout = server.takeRequest()
        assertEquals("/auth/v1/logout", logout.path)
        assertEquals("Bearer access-1", logout.getHeader("Authorization"))
        assertNull(store.tokens)
    }
}
