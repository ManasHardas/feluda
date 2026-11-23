package org.neighborhoodwatchdog.android.data.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.neighborhoodwatchdog.android.data.api.ApiError
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.AuthApi
import org.neighborhoodwatchdog.android.data.api.OtpRequest
import org.neighborhoodwatchdog.android.data.api.RefreshRequest
import org.neighborhoodwatchdog.android.data.api.SessionResponse
import org.neighborhoodwatchdog.android.data.api.VerifyOtpRequest
import org.neighborhoodwatchdog.android.domain.auth.AuthTokens
import org.neighborhoodwatchdog.android.domain.auth.RefreshOutcome
import org.neighborhoodwatchdog.android.domain.auth.TokenManager
import org.neighborhoodwatchdog.android.domain.auth.TokenRefresher
import org.neighborhoodwatchdog.android.domain.model.Clock
import java.io.IOException

fun SessionResponse.toTokens(nowMs: Long): AuthTokens = AuthTokens(
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresAtMs = nowMs + expiresIn * 1000,
    userId = user?.id,
    email = user?.email,
)

/** Sends the publishable key to the auth service. It is public by design. */
class ApiKeyInterceptor(private val publicKey: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = if (publicKey.isBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder().header("apikey", publicKey).build()
        }
        return chain.proceed(request)
    }
}

class RemoteTokenRefresher(private val authApi: AuthApi, private val clock: Clock) : TokenRefresher {
    override suspend fun refresh(refreshToken: String): RefreshOutcome = try {
        val response = authApi.refresh("refresh_token", RefreshRequest(refreshToken))
        val body = response.body()
        when {
            response.isSuccessful && body != null -> RefreshOutcome.Success(body.toTokens(clock.nowMs()))
            // The auth service refuses the refresh token (expired, revoked or already rotated).
            response.code() in 400..403 -> RefreshOutcome.SessionInvalid
            else -> RefreshOutcome.TemporaryFailure
        }
    } catch (_: IOException) {
        RefreshOutcome.TemporaryFailure
    } catch (_: kotlinx.serialization.SerializationException) {
        RefreshOutcome.TemporaryFailure
    }
}

/** Adds `Authorization: Bearer <access token>` to every API request, refreshing first when it is about to expire. */
class BearerInterceptor(private val tokens: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(AUTHORIZATION) != null) return chain.proceed(original)
        val token = runBlocking { tokens.validAccessToken() } ?: return chain.proceed(original)
        return chain.proceed(original.newBuilder().header(AUTHORIZATION, "Bearer $token").build())
    }
}

/** On a 401: refresh once (serialised in [TokenManager]) and retry the request once. */
class TokenAuthenticator(private val tokens: TokenManager) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null // already retried once
        val rejected = response.request.header(AUTHORIZATION)?.removePrefix("Bearer ")
        val fresh = runBlocking { tokens.refreshAfterUnauthorized(rejected) } ?: return null
        if (fresh == rejected) return null
        return response.request.newBuilder().header(AUTHORIZATION, "Bearer $fresh").build()
    }
}

private const val AUTHORIZATION = "Authorization"

class AuthRepository(
    private val authApi: AuthApi,
    private val tokens: TokenManager,
    private val clock: Clock,
) {
    val isSignedIn: Boolean get() = tokens.isSignedIn()
    val email: String? get() = tokens.current()?.email

    suspend fun requestCode(email: String): ApiResult<Unit> = call {
        val response = authApi.requestCode(OtpRequest(email.trim()))
        if (response.isSuccessful) ApiResult.Success(Unit) else failure(response.code(), response.errorBody()?.string())
    }

    suspend fun verifyCode(email: String, code: String): ApiResult<Unit> = call {
        val response = authApi.verify(VerifyOtpRequest(email = email.trim(), token = code.trim()))
        val body = response.body()
        if (response.isSuccessful && body != null) {
            tokens.save(body.toTokens(clock.nowMs()).let { it.copy(email = it.email ?: email.trim()) })
            ApiResult.Success(Unit)
        } else {
            failure(response.code(), response.errorBody()?.string())
        }
    }

    /** Best effort: the local session is cleared whether or not the server could be told. */
    suspend fun signOut() {
        val access = tokens.current()?.accessToken
        if (access != null) {
            try {
                authApi.logout("Bearer $access")
            } catch (_: IOException) {
                // Offline: the tokens are still removed from this device below.
            }
        }
        tokens.clear()
    }

    fun clearLocalSession() = tokens.clear()

    private suspend fun call(block: suspend () -> ApiResult<Unit>): ApiResult<Unit> = try {
        block()
    } catch (e: IOException) {
        ApiResult.Failure(ApiError(null, null, e.message))
    } catch (e: kotlinx.serialization.SerializationException) {
        ApiResult.Failure(ApiError(null, "unreadable_response", e.message))
    }

    private fun failure(code: Int, body: String?): ApiResult.Failure {
        // GoTrue errors look like {"code":..,"error_code":..,"msg":..}; the local emulator may use the API shape.
        val parsed = org.neighborhoodwatchdog.android.data.api.parseApiError(code, body, null)
        return ApiResult.Failure(parsed)
    }
}
