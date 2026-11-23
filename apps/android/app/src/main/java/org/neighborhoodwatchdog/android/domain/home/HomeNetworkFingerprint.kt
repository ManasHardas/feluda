package org.neighborhoodwatchdog.android.domain.home

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * What identifies a Wi-Fi network without location permission: its default gateways and the IPv4
 * prefix of the interface. SSID and BSSID are not read.
 */
data class NetworkIdentity(
    /** IPv4 default gateway addresses, e.g. "192.168.1.1". */
    val ipv4Gateways: Set<String> = emptySet(),
    /** IPv6 link-local router addresses without a scope, e.g. "fe80::1a2b:3cff:fe4d:5e6f". */
    val ipv6Routers: Set<String> = emptySet(),
    /** IPv4 network prefix of the interface, e.g. "192.168.1.0/24". */
    val ipv4Prefix: String? = null,
) {
    val isEmpty: Boolean get() = ipv4Gateways.isEmpty() && ipv6Routers.isEmpty()
}

/** Salted SHA-256 hashes. Stored only on the device; never transmitted. */
@Serializable
data class StoredFingerprint(
    val saltHex: String,
    val ipv4Hashes: Set<String>,
    val ipv6Hashes: Set<String>,
)

object HomeNetworkFingerprint {
    fun create(identity: NetworkIdentity, salt: ByteArray): StoredFingerprint? {
        if (identity.isEmpty) return null
        return StoredFingerprint(
            saltHex = salt.toHex(),
            ipv4Hashes = ipv4Hashes(identity, salt),
            ipv6Hashes = ipv6Hashes(identity, salt),
        )
    }

    /**
     * True / false, or null when no fingerprint was stored or the gateways were not available.
     *
     * The IPv6 link-local router address is close to unique per router, so when both sides have
     * one it decides. Otherwise the IPv4 gateway and prefix decide; that is weaker, because many
     * home routers use the same private addresses.
     */
    fun matches(stored: StoredFingerprint?, current: NetworkIdentity?): Boolean? {
        if (stored == null || current == null || current.isEmpty) return null
        val salt = stored.saltHex.hexToBytes()
        val v6 = ipv6Hashes(current, salt)
        if (stored.ipv6Hashes.isNotEmpty() && v6.isNotEmpty()) {
            return stored.ipv6Hashes.any { it in v6 }
        }
        val v4 = ipv4Hashes(current, salt)
        if (stored.ipv4Hashes.isNotEmpty() && v4.isNotEmpty()) {
            return stored.ipv4Hashes.any { it in v4 }
        }
        // Nothing comparable (for example stored IPv4 only, current IPv6 only).
        return null
    }

    private fun ipv4Hashes(identity: NetworkIdentity, salt: ByteArray): Set<String> =
        identity.ipv4Gateways.map { hash(salt, "v4|${normalize(it)}|${identity.ipv4Prefix.orEmpty()}") }.toSet()

    private fun ipv6Hashes(identity: NetworkIdentity, salt: ByteArray): Set<String> =
        identity.ipv6Routers.map { hash(salt, "v6|${normalize(it)}") }.toSet()

    /** Lower case, no IPv6 scope ("%wlan0"), no surrounding space. */
    fun normalize(address: String): String = address.trim().substringBefore('%').lowercase()

    private fun hash(salt: ByteArray, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(value.toByteArray(Charsets.UTF_8))
        return digest.digest().toHex()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

object InvitationToken {
    private val pattern = Regex("^[A-Za-z0-9_-]{20,128}$")

    /** Accepts the full link (`https://host/join#t=<token>`) or the bare token. Null when invalid. */
    fun parse(input: String): String? {
        val text = input.trim()
        val candidate = when {
            "#" in text -> text.substringAfter('#').split('&').firstOrNull { it.startsWith("t=") }?.removePrefix("t=")
            "t=" in text -> text.substringAfter("t=").substringBefore('&')
            else -> text
        } ?: return null
        return candidate.takeIf { pattern.matches(it) }
    }

    /**
     * The token from the link the web join page opens: `feluda://join#t=<token>`. Null for any
     * other address, so an unrelated VIEW intent is ignored.
     */
    fun fromAppLink(link: String?): String? {
        val text = link?.trim().orEmpty()
        val rest = APP_LINK_PREFIXES.firstOrNull { text.startsWith(it, ignoreCase = true) }?.let { text.substring(it.length) } ?: return null
        if (!rest.startsWith("#") && !rest.startsWith("?")) return null
        return parse(rest)
    }

    private val APP_LINK_PREFIXES = listOf("feluda://join/", "feluda://join")
}
