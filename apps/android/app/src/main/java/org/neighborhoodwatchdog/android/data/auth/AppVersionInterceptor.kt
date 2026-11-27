package org.neighborhoodwatchdog.android.data.auth

import okhttp3.Interceptor
import okhttp3.Response
import org.neighborhoodwatchdog.android.data.api.parseApiError
import org.neighborhoodwatchdog.android.domain.update.AppVersionHeader

/**
 * Adds `X-App-Version: android/<major.minor.patch> (<versionCode>)` to every request to the backend
 * ([apiRoot]), and only there: the third-party internet probe never learns the app version.
 *
 * A 426 with the API error code `update_required` means the server refuses this version:
 * [onUpdateRequired] switches the whole app to the "Update required" screen and stops uploads.
 * A 426 in any other shape changes nothing.
 */
class AppVersionInterceptor(
    private val apiRoot: String,
    private val headerValue: String?,
    private val onUpdateRequired: () -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!original.url.toString().startsWith(apiRoot)) return chain.proceed(original)
        val request = if (headerValue != null) original.newBuilder().header(AppVersionHeader.NAME, headerValue).build() else original
        val response = chain.proceed(request)
        if (response.code == UPGRADE_REQUIRED) {
            val body = try {
                response.peekBody(MAX_ERROR_BYTES).string()
            } catch (_: java.io.IOException) {
                null
            }
            if (parseApiError(UPGRADE_REQUIRED, body, null).code == UPDATE_REQUIRED) onUpdateRequired()
        }
        return response
    }

    companion object {
        const val UPGRADE_REQUIRED = 426
        const val UPDATE_REQUIRED = "update_required"
        private const val MAX_ERROR_BYTES = 8_192L
    }
}
