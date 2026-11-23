package org.neighborhoodwatchdog.android.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import org.neighborhoodwatchdog.android.data.local.FingerprintStore
import org.neighborhoodwatchdog.android.domain.home.HomeNetworkFingerprint
import org.neighborhoodwatchdog.android.domain.home.NetworkIdentity
import java.net.Inet4Address
import java.net.Inet6Address
import java.security.SecureRandom

/**
 * The home-network step: reads the gateways of the Wi-Fi network the phone is on right now and
 * stores a salted hash of them on the device. No location permission; SSID and BSSID are not read.
 */
class HomeNetworkManager(context: Context, private val store: FingerprintStore) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val hasFingerprint: Boolean get() = store.load() != null

    /** The identity of the current Wi-Fi network, or null when not on Wi-Fi or no gateway is known. */
    @Suppress("DEPRECATION")
    fun currentWifiIdentity(): NetworkIdentity? {
        val candidates = listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks
        val wifi = candidates.firstOrNull {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
        } ?: return null
        return identityFrom(connectivity.getLinkProperties(wifi))
    }

    /** Stores the fingerprint of the current Wi-Fi network. False when there is nothing to store. */
    fun confirmCurrentNetworkAsHome(): Boolean {
        val identity = currentWifiIdentity() ?: return false
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val fingerprint = HomeNetworkFingerprint.create(identity, salt) ?: return false
        store.save(fingerprint)
        return true
    }

    fun forget() = store.clear()

    companion object {
        fun identityFrom(link: LinkProperties?): NetworkIdentity? {
            if (link == null) return null
            val routers = link.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway }.filter { !it.isAnyLocalAddress }.distinct()
            val v4 = routers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.toSet()
            val v6 = routers.filterIsInstance<Inet6Address>().filter { it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }.map(HomeNetworkFingerprint::normalize).toSet()
            val prefix = link.linkAddresses.firstOrNull { it.address is Inet4Address }
                ?.let { ConnectivityMonitor.ipv4Prefix(it.address, it.prefixLength) }
            return NetworkIdentity(v4, v6, prefix).takeUnless { it.isEmpty }
        }
    }
}
