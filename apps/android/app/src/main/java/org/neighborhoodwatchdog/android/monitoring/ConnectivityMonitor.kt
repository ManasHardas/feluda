package org.neighborhoodwatchdog.android.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import org.neighborhoodwatchdog.android.data.local.FingerprintStore
import org.neighborhoodwatchdog.android.domain.home.HomeNetworkFingerprint
import org.neighborhoodwatchdog.android.domain.home.NetworkIdentity
import org.neighborhoodwatchdog.android.domain.model.Transport
import org.neighborhoodwatchdog.android.domain.monitoring.NetworkSnapshot
import org.neighborhoodwatchdog.android.domain.monitoring.WifiBand
import java.net.InetAddress

/**
 * Observes the OS network state through [ConnectivityManager.NetworkCallback]: the default network,
 * every Wi-Fi network and every cellular network. No location permission is used and
 * `FLAG_INCLUDE_LOCATION_INFO` is not set, so SSID and BSSID are never delivered to the app.
 *
 * All callbacks and all methods run on the monitoring thread behind [handler]; the class keeps no
 * locks. It only gathers facts; classification is done by the pure-Kotlin state machine.
 */
class ConnectivityMonitor(
    private val context: Context,
    private val handler: Handler,
    private val fingerprints: FingerprintStore,
) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private class NetState(var capabilities: NetworkCapabilities? = null, var link: LinkProperties? = null)

    private val wifiNetworks = LinkedHashMap<Network, NetState>()
    private val cellularNetworks = LinkedHashSet<Network>()
    private var defaultNetwork: Network? = null
    private var defaultCapabilities: NetworkCapabilities? = null

    /** Probe results belong to the network they were measured on. */
    private var probeDone = false
    private var probeNetwork: Network? = null
    private var probeInternet: Boolean? = null
    private var probeGateway: Boolean? = null

    private var registered = false
    private var onChange: (() -> Unit)? = null

    val isRegistered: Boolean get() = registered

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
            defaultCapabilities = null
            changed()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            defaultNetwork = network
            defaultCapabilities = networkCapabilities
            changed()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            changed()
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) {
                defaultNetwork = null
                defaultCapabilities = null
            }
            changed()
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetworks.getOrPut(network) { NetState() }
            changed()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val state = wifiNetworks.getOrPut(network) { NetState() }
            val before = state.capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val after = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            state.capabilities = networkCapabilities
            if (before != null && before != after && probeNetwork == network) {
                // The OS changed its mind about this network: the old probe result is stale.
                clearProbe()
            }
            changed()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            wifiNetworks.getOrPut(network) { NetState() }.link = linkProperties
            changed()
        }

        override fun onLost(network: Network) {
            wifiNetworks.remove(network)
            if (probeNetwork == network) clearProbe()
            changed()
        }
    }

    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cellularNetworks.add(network)
            changed()
        }

        override fun onLost(network: Network) {
            cellularNetworks.remove(network)
            changed()
        }
    }

    /** Registers the three callbacks. Safe to call again: it re-registers from a clean state. */
    fun start(onChange: () -> Unit) {
        stop()
        this.onChange = onChange
        try {
            connectivity.registerDefaultNetworkCallback(defaultCallback, handler)
            connectivity.registerNetworkCallback(transportRequest(NetworkCapabilities.TRANSPORT_WIFI), wifiCallback, handler)
            connectivity.registerNetworkCallback(
                transportRequest(NetworkCapabilities.TRANSPORT_CELLULAR),
                cellularCallback,
                handler,
            )
            registered = true
        } catch (_: SecurityException) {
            // ACCESS_NETWORK_STATE is missing: the caller reports MONITORING_PERMISSION_REQUIRED.
            stop()
        } catch (_: RuntimeException) {
            // The OS refused the registration (for example too many callbacks). Not registered.
            stop()
        }
    }

    fun stop() {
        for (callback in listOf(defaultCallback, wifiCallback, cellularCallback)) {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // was not registered
            }
        }
        registered = false
        onChange = null
        wifiNetworks.clear()
        cellularNetworks.clear()
        defaultNetwork = null
        defaultCapabilities = null
        clearProbe()
    }

    private fun transportRequest(transport: Int): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(transport)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private fun changed() {
        onChange?.invoke()
    }

    private fun clearProbe() {
        probeDone = false
        probeNetwork = null
        probeInternet = null
        probeGateway = null
    }

    /** The Wi-Fi network to describe: the default one when it is Wi-Fi, else the most recent. */
    private fun primaryWifi(): Network? =
        defaultNetwork?.takeIf { it in wifiNetworks } ?: wifiNetworks.keys.lastOrNull()

    /** The network an active probe should use: Wi-Fi when associated, else the default network. */
    fun probeTarget(): Network? = primaryWifi() ?: defaultNetwork

    /** Gateways the OS reports for the probe target. Never a guessed address. */
    fun probeGateways(): List<InetAddress> {
        val wifi = primaryWifi() ?: return emptyList()
        return gateways(linkOf(wifi))
    }

    fun setProbeResult(network: Network?, internetReachable: Boolean, gatewayReachable: Boolean?) {
        if (network != probeTarget()) return // the network changed while the probe ran: discard it
        probeDone = true
        probeNetwork = network
        probeInternet = internetReachable
        probeGateway = gatewayReachable
        changed()
    }

    /** Re-reads capabilities the OS does not push on its own (signal strength changes). */
    fun refreshWifiCapabilities() {
        val wifi = primaryWifi() ?: return
        val state = wifiNetworks[wifi] ?: return
        connectivity.getNetworkCapabilities(wifi)?.let { state.capabilities = it }
        connectivity.getLinkProperties(wifi)?.let { state.link = it }
    }

    private fun linkOf(network: Network): LinkProperties? =
        wifiNetworks[network]?.link ?: connectivity.getLinkProperties(network)

    fun snapshot(): NetworkSnapshot {
        if (!registered) return NetworkSnapshot(batterySaver = powerManager.isPowerSaveMode)
        val wifi = primaryWifi()
        val wifiState = wifi?.let { wifiNetworks[it] }
        val wifiAssociated = wifi != null
        val (rssi, frequency) = if (wifiAssociated) wifiSignal(wifiState?.capabilities) else null to null
        val probeMatches = probeDone && probeNetwork == probeTarget()
        return NetworkSnapshot(
            wifiAssociated = wifiAssociated,
            wifiValidated = wifiState?.capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            internetReachable = if (probeMatches) probeInternet else null,
            cellularAvailable = when {
                cellularNetworks.isNotEmpty() -> true
                // Mobile data is usually kept down while Wi-Fi works: not measured, so null.
                wifiAssociated -> null
                else -> false
            },
            otherPathAvailable = defaultCapabilities?.let {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            } ?: false,
            defaultTransport = defaultTransport(),
            homeNetworkMatch = if (wifi != null) HomeNetworkFingerprint.matches(fingerprints.load(), identityOf(wifi)) else null,
            localGatewayReachable = if (probeMatches && wifiAssociated) probeGateway else null,
            wifiRadioEnabled = try {
                wifiManager?.isWifiEnabled
            } catch (_: SecurityException) {
                null
            },
            airplaneMode = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0,
            vpnActive = defaultCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            wifiRssiDbm = rssi,
            wifiFrequencyMhz = frequency,
            batterySaver = powerManager.isPowerSaveMode,
        )
    }

    private fun defaultTransport(): Transport {
        if (defaultNetwork == null) return Transport.NONE
        val caps = defaultCapabilities ?: return Transport.UNKNOWN
        return transportOf(caps)
    }

    /**
     * RSSI and frequency without location permission. Android 12+: the (redacted) [WifiInfo] carried
     * in the capabilities still has both. Android 10-11: signal strength from the capabilities.
     * Older, or when those are empty: [WifiManager.getConnectionInfo]. Unknown stays null.
     */
    @Suppress("DEPRECATION")
    private fun wifiSignal(capabilities: NetworkCapabilities?): Pair<Int?, Int?> {
        var rssi: Int? = null
        var frequency: Int? = null
        if (capabilities != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = capabilities.transportInfo as? WifiInfo
            if (info != null) {
                rssi = WifiBand.validRssi(info.rssi)
                frequency = info.frequency.takeIf { it > 0 }
            }
            if (rssi == null) rssi = WifiBand.validRssi(capabilities.signalStrength)
        }
        if (rssi == null || frequency == null) {
            val info = try {
                wifiManager?.connectionInfo
            } catch (_: SecurityException) {
                null
            }
            // Below Android 12 this is the supported source. From 12 on it is a fallback only.
            if (info != null) {
                if (rssi == null) rssi = WifiBand.validRssi(info.rssi)
                if (frequency == null) frequency = info.frequency.takeIf { it > 0 }
            }
        }
        return rssi to frequency
    }

    /** What identifies the current Wi-Fi network for the home-network fingerprint, or null. */
    fun currentWifiIdentity(): NetworkIdentity? = primaryWifi()?.let { identityOf(it) }

    private fun identityOf(network: Network): NetworkIdentity? = HomeNetworkManager.identityFrom(linkOf(network))

    private fun gateways(link: LinkProperties?): List<InetAddress> {
        if (link == null) return emptyList()
        return link.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway }.filter { !it.isAnyLocalAddress }.distinct()
    }

    companion object {
        fun transportOf(caps: NetworkCapabilities): Transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.OTHER
        }

        /** "192.168.1.37" with length 24 becomes "192.168.1.0/24". */
        fun ipv4Prefix(address: InetAddress, prefixLength: Int): String {
            val bytes = address.address
            val length = prefixLength.coerceIn(0, 32)
            val masked = ByteArray(4) { i ->
                val bits = (length - i * 8).coerceIn(0, 8)
                val mask = if (bits == 0) 0 else (0xFF shl (8 - bits)) and 0xFF
                (bytes[i].toInt() and mask).toByte()
            }
            return masked.joinToString(".") { (it.toInt() and 0xFF).toString() } + "/" + length
        }
    }
}
