package org.neighborhoodwatchdog.android.data.api

import org.neighborhoodwatchdog.android.domain.model.SubmitEventsRequest
import org.neighborhoodwatchdog.android.domain.model.SubmitEventsResponse
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException

/** Paths are relative to `{API_BASE_URL}/api/v1/`. */
interface WatchdogApi {
    @GET("monitoring/health")
    suspend fun health(): Response<HealthOut>

    /** Public. Whether this app version is still accepted and whether a newer one exists. */
    @GET("app/compatibility")
    suspend fun appCompatibility(@Query("platform") platform: String, @Query("version") version: String): Response<AppCompatibilityOut>

    @POST("auth/verify-invitation")
    suspend fun verifyInvitation(@Body body: VerifyInvitationRequest): Response<VerifyInvitationResponse>

    /** Public. Enrols this phone with an invitation link and returns its device secret. */
    @POST("enroll")
    suspend fun enroll(@Body body: EnrollRequest): Response<EnrollResponse>

    @GET("households/me")
    suspend fun household(): Response<HouseholdOut>

    @PATCH("households/me")
    suspend fun updateHousehold(@Body body: UpdateHouseholdRequest): Response<HouseholdOut>

    @POST("households/leave")
    suspend fun leaveHousehold(): Response<OkResponse>

    @GET("neighborhoods/me")
    suspend fun neighborhood(): Response<NeighborhoodOut>

    /** Updates this phone's own record (versions, capabilities). The device is created by [enroll]. */
    @POST("devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<DeviceOut>

    @POST("devices/revoke")
    suspend fun revokeDevice(@Body body: RevokeDeviceRequest): Response<OkResponse>

    @GET("devices")
    suspend fun devices(): Response<List<DeviceOut>>

    @POST("notifications/register")
    suspend fun registerPushToken(@Body body: RegisterPushTokenRequest): Response<OkResponse>

    @GET("notifications/preferences")
    suspend fun notificationPreferences(): Response<NotificationPreferences>

    @PUT("notifications/preferences")
    suspend fun updateNotificationPreferences(@Body body: NotificationPreferences): Response<NotificationPreferences>

    @POST("connectivity/events")
    suspend fun submitEvents(@Body body: SubmitEventsRequest): Response<SubmitEventsResponse>

    @GET("connectivity/status")
    suspend fun connectivityStatus(): Response<ConnectivityStatusOut>

    @GET("incidents")
    suspend fun incidents(@Query("status") status: String, @Query("limit") limit: Int): Response<IncidentListOut>

    @GET("incidents/{incident_id}")
    suspend fun incident(@Path("incident_id") incidentId: String): Response<IncidentDetailOut>
}

data class ApiError(
    /** Null when the request never got an HTTP answer. */
    val httpCode: Int?,
    /** The API error code (`invitation_invalid`, `rate_limited`, ...) when the body carried one. */
    val code: String?,
    val message: String?,
    val retryAfterSeconds: Int? = null,
) {
    val isNetwork: Boolean get() = httpCode == null && code == null
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

fun parseApiError(httpCode: Int, body: String?, retryAfter: String?): ApiError {
    val parsed = body?.takeIf { it.isNotBlank() }?.let {
        runCatching { WatchdogJson.decodeFromString(ErrorResponse.serializer(), it) }.getOrNull()
    }
    return ApiError(httpCode, parsed?.error?.code?.takeIf { it.isNotBlank() }, parsed?.error?.message, retryAfter?.trim()?.toIntOrNull())
}

suspend fun <T : Any> apiCall(block: suspend () -> Response<T>): ApiResult<T> = try {
    val response = block()
    val body = response.body()
    if (response.isSuccessful && body != null) {
        ApiResult.Success(body)
    } else {
        ApiResult.Failure(parseApiError(response.code(), response.errorBody()?.string(), response.headers()["Retry-After"]))
    }
} catch (e: IOException) {
    ApiResult.Failure(ApiError(null, null, e.message))
} catch (e: kotlinx.serialization.SerializationException) {
    ApiResult.Failure(ApiError(null, "unreadable_response", e.message))
}
