package org.neighborhoodwatchdog.android.data.auth

import okhttp3.Interceptor
import okhttp3.Response
import org.neighborhoodwatchdog.android.data.api.parseApiError
import org.neighborhoodwatchdog.android.domain.auth.DeviceCredentials

/**
 * Adds `Authorization: Bearer <device secret>` to every API request once the phone is enrolled.
 *
 * A 401 with the API error code `unauthorized` means the server no longer knows this phone
 * (removed, revoked, or it left): the stored secret is removed and the app returns to the join
 * screen. A 401 in any other shape (for example from a captive portal or a proxy) changes nothing,
 * because losing the secret means asking the organizer for a new invitation link.
 */
class DeviceTokenInterceptor(private val credentials: DeviceCredentials) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(AUTHORIZATION) != null) return chain.proceed(original)
        val token = credentials.token() ?: return chain.proceed(original)
        val response = chain.proceed(original.newBuilder().header(AUTHORIZATION, "Bearer $token").build())
        if (response.code == 401) {
            val body = try {
                response.peekBody(MAX_ERROR_BYTES).string()
            } catch (_: java.io.IOException) {
                null
            }
            if (parseApiError(401, body, null).code == "unauthorized") credentials.rejected(token)
        }
        return response
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val MAX_ERROR_BYTES = 8_192L
    }
}
