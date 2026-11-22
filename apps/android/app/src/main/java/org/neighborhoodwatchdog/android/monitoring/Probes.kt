package org.neighborhoodwatchdog.android.monitoring

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.neighborhoodwatchdog.android.config.AppConfig
import java.io.IOException
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Builds an HTTP client whose sockets and DNS lookups go through one specific [Network]. */
fun OkHttpClient.boundTo(network: Network): OkHttpClient = newBuilder()
    .socketFactory(network.socketFactory)
    .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
    })
    // A separate pool: connections opened over another network must never be reused here.
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    .build()

/**
 * Active checks. They are real requests, never assumptions:
 *  - internet: HTTPS GET of the backend health endpoint, then a second independent target;
 *  - gateway: TCP connect to the gateway address the OS reports (never a guessed address, not ICMP).
 */
class ProbeRunner(baseClient: OkHttpClient, private val config: AppConfig) {
    private val client = baseClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /** True if either target answers over [network] (the default network when null). */
    suspend fun internetReachable(network: Network?): Boolean = withContext(Dispatchers.IO) {
        val http = try {
            if (network != null) client.boundTo(network) else client
        } catch (_: RuntimeException) {
            return@withContext false
        }
        if (answers(http, config.healthUrl, requireSuccess = true)) return@withContext true
        config.internetProbeUrl.isNotBlank() && answers(http, config.internetProbeUrl, requireSuccess = false)
    }

    private fun answers(http: OkHttpClient, url: String, requireSuccess: Boolean): Boolean = try {
        http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { response ->
            if (requireSuccess) response.isSuccessful else response.code < 500
        }
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false // malformed probe URL in the build configuration
    }

    /**
     * True when any gateway accepts or refuses a TCP connection on port 53, 80 or 443 (a refusal
     * proves the gateway answered). False when every attempt timed out. Null when the OS reported
     * no gateway that can be probed.
     */
    suspend fun gatewayReachable(network: Network?, gateways: List<InetAddress>): Boolean? = withContext(Dispatchers.IO) {
        // An IPv6 link-local address without an interface scope cannot be connected to.
        val targets = gateways.filter { it !is Inet6Address || !it.isLinkLocalAddress || it.scopeId != 0 }
        if (targets.isEmpty()) return@withContext null
        for (gateway in targets) {
            for (port in GATEWAY_PORTS) {
                if (tcpAnswers(network, gateway, port)) return@withContext true
            }
        }
        false
    }

    private fun tcpAnswers(network: Network?, address: InetAddress, port: Int): Boolean {
        val socket: Socket = try {
            network?.socketFactory?.createSocket() ?: Socket()
        } catch (_: IOException) {
            return false
        }
        return try {
            socket.connect(InetSocketAddress(address, port), GATEWAY_TIMEOUT_MS)
            true
        } catch (e: ConnectException) {
            // "Connection refused" means the gateway replied with a reset: it is reachable.
            e.message?.contains("refused", ignoreCase = true) == true
        } catch (_: IOException) {
            false
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
                // nothing to do
            }
        }
    }

    private companion object {
        val GATEWAY_PORTS = intArrayOf(53, 80, 443)
        const val GATEWAY_TIMEOUT_MS = 1_500
    }
}
