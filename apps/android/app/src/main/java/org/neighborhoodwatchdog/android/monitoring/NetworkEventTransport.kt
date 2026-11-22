package org.neighborhoodwatchdog.android.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.neighborhoodwatchdog.android.config.AppConfig
import org.neighborhoodwatchdog.android.data.api.parseApiError
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.domain.model.ConnectivityEvent
import org.neighborhoodwatchdog.android.domain.model.SubmitEventsRequest
import org.neighborhoodwatchdog.android.domain.model.SubmitEventsResponse
import org.neighborhoodwatchdog.android.domain.model.Transport
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import org.neighborhoodwatchdog.android.domain.reporting.EventTransport
import org.neighborhoodwatchdog.android.domain.reporting.SendResult
import java.io.IOException
import java.time.Instant

/**
 * Requests the cellular network from the OS for the duration of one piece of work. Having mobile
 * data does not mean a request uses it, so the HTTP client is bound to this [Network] explicitly.
 * The network request is always released afterwards.
 */
class CellularNetworkProvider(context: Context) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun <T> withCellular(timeoutMs: Int = 10_000, block: suspend (Network) -> T): T? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val available = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.complete(network)
            }

            override fun onUnavailable() {
                available.complete(null)
            }
        }
        try {
            connectivity.requestNetwork(request, callback, timeoutMs)
        } catch (_: SecurityException) {
            return null // CHANGE_NETWORK_STATE missing
        } catch (_: RuntimeException) {
            return null // the OS refused the request
        }
        try {
            val network = withTimeoutOrNull(timeoutMs + 1_000L) { available.await() } ?: return null
            return block(network)
        } finally {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // already released
            }
        }
    }

    /** True when the default network is validated Wi-Fi or Ethernet, so cellular is not needed. */
    fun defaultPathLooksHealthy(): Boolean {
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return false
        val fixed = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return fixed && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** The transport of the default network right now, as far as the OS says. */
    fun defaultTransport(): Transport {
        val network = connectivity.activeNetwork ?: return Transport.NONE
        val caps = connectivity.getNetworkCapabilities(network) ?: return Transport.UNKNOWN
        return ConnectivityMonitor.transportOf(caps)
    }
}

/**
 * One upload attempt. When Wi-Fi is lost or has no internet the request is first tried over an
 * explicitly bound cellular network; `reported_via` is `cellular` only for that bound request.
 * Otherwise, or when cellular fails, the default network is used and `reported_via` is whatever
 * the OS says the default network is.
 */
class NetworkEventTransport(
    private val apiClient: OkHttpClient,
    private val cellular: CellularNetworkProvider,
    private val config: AppConfig,
    private val localState: LocalState,
) : EventTransport {

    override suspend fun send(events: List<ConnectivityEvent>, sentAt: Instant): SendResult = withContext(Dispatchers.IO) {
        if (!cellular.defaultPathLooksHealthy()) {
            val overCellular = cellular.withCellular { network ->
                val bound = try {
                    apiClient.boundTo(network)
                } catch (_: RuntimeException) {
                    return@withCellular null
                }
                post(bound, events, sentAt, Transport.CELLULAR)
            }
            if (overCellular != null && overCellular !is SendResult.NetworkFailure) return@withContext overCellular
        }
        post(apiClient, events, sentAt, cellular.defaultTransport())
    }

    private fun post(client: OkHttpClient, events: List<ConnectivityEvent>, sentAt: Instant, via: Transport): SendResult {
        val payload = SubmitEventsRequest(deviceId = localState.installId(), reportedVia = via, sentAt = sentAt, events = events)
        val body = WatchdogJson.encodeToString(SubmitEventsRequest.serializer(), payload).toRequestBody(JSON)
        val request = Request.Builder().url(config.apiRoot + "connectivity/events").post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string()
                when {
                    response.isSuccessful -> try {
                        SendResult.Delivered(WatchdogJson.decodeFromString(SubmitEventsResponse.serializer(), text.orEmpty()), via)
                    } catch (e: kotlinx.serialization.SerializationException) {
                        SendResult.NetworkFailure("unreadable response: ${e.message}")
                    }
                    response.code == 401 -> SendResult.Unauthorized
                    response.code == 429 -> SendResult.RateLimited(response.header("Retry-After")?.trim()?.toIntOrNull())
                    response.code >= 500 -> SendResult.ServerError(response.code)
                    else -> SendResult.Rejected(response.code, parseApiError(response.code, text, null).code)
                }
            }
        } catch (e: IOException) {
            SendResult.NetworkFailure(e.message)
        } catch (e: IllegalArgumentException) {
            SendResult.NetworkFailure("invalid API_BASE_URL: ${e.message}")
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
