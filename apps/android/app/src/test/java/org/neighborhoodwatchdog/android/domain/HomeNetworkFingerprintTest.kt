package org.neighborhoodwatchdog.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.neighborhoodwatchdog.android.domain.home.HomeNetworkFingerprint
import org.neighborhoodwatchdog.android.domain.home.InvitationToken
import org.neighborhoodwatchdog.android.domain.home.NetworkIdentity
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import org.neighborhoodwatchdog.android.domain.home.StoredFingerprint


/** A made-up invitation token used only as test input (not a credential). */
private const val SAMPLE_INVITATION = "Zx9_-abcdefghijklmnopqrstuv0123456789"

class HomeNetworkFingerprintTest {
    private val salt = ByteArray(32) { it.toByte() }
    private val home = NetworkIdentity(
        ipv4Gateways = setOf("192.168.1.1"),
        ipv6Routers = setOf("fe80::1a2b:3cff:fe4d:5e6f"),
        ipv4Prefix = "192.168.1.0/24",
    )
    private val stored = HomeNetworkFingerprint.create(home, salt)!!

    @Test
    fun sameNetworkMatches() {
        assertEquals(true, HomeNetworkFingerprint.matches(stored, home))
    }

    @Test
    fun scopeAndLetterCaseOfTheRouterAddressDoNotMatter() {
        val sameRouter = home.copy(ipv6Routers = setOf("FE80::1A2B:3CFF:FE4D:5E6F%wlan0"))
        assertEquals(true, HomeNetworkFingerprint.matches(stored, sameRouter))
    }

    @Test
    fun neighbourWithTheSamePrivateAddressesButAnotherRouterDoesNotMatch() {
        val neighbour = home.copy(ipv6Routers = setOf("fe80::aaaa:bbff:fecc:dddd"))
        assertEquals(false, HomeNetworkFingerprint.matches(stored, neighbour))
    }

    @Test
    fun differentGatewayOrPrefixDoesNotMatch() {
        val cafe = NetworkIdentity(ipv4Gateways = setOf("10.0.0.1"), ipv4Prefix = "10.0.0.0/24")
        assertEquals(false, HomeNetworkFingerprint.matches(stored, cafe))
        val samePrefixOtherGateway = NetworkIdentity(ipv4Gateways = setOf("192.168.1.254"), ipv4Prefix = "192.168.1.0/24")
        assertEquals(false, HomeNetworkFingerprint.matches(stored, samePrefixOtherGateway))
        val sameGatewayOtherPrefix = NetworkIdentity(ipv4Gateways = setOf("192.168.1.1"), ipv4Prefix = "192.168.0.0/16")
        assertEquals(false, HomeNetworkFingerprint.matches(stored, sameGatewayOtherPrefix))
    }

    @Test
    fun ipv4IsUsedWhenTheIpv6RouterIsNotKnownYet() {
        assertEquals(true, HomeNetworkFingerprint.matches(stored, home.copy(ipv6Routers = emptySet())))
        val storedV4Only = HomeNetworkFingerprint.create(home.copy(ipv6Routers = emptySet()), salt)!!
        assertEquals(true, HomeNetworkFingerprint.matches(storedV4Only, home))
    }

    @Test
    fun unknownStaysNull() {
        assertNull("no fingerprint stored", HomeNetworkFingerprint.matches(null, home))
        assertNull("gateways not available", HomeNetworkFingerprint.matches(stored, null))
        assertNull(HomeNetworkFingerprint.matches(stored, NetworkIdentity()))
        val storedV4Only = HomeNetworkFingerprint.create(home.copy(ipv6Routers = emptySet()), salt)!!
        assertNull("nothing comparable", HomeNetworkFingerprint.matches(storedV4Only, NetworkIdentity(ipv6Routers = setOf("fe80::1"))))
        assertNull(HomeNetworkFingerprint.create(NetworkIdentity(), salt))
    }

    @Test
    fun hashIsSaltedSha256AndContainsNoAddress() {
        val other = HomeNetworkFingerprint.create(home, ByteArray(32) { 7 })!!
        assertNotEquals("a different salt gives different hashes", stored.ipv4Hashes, other.ipv4Hashes)
        assertNotEquals(stored.ipv6Hashes, other.ipv6Hashes)
        assertEquals(false, HomeNetworkFingerprint.matches(other.copy(saltHex = stored.saltHex), home))
        stored.ipv4Hashes.plus(stored.ipv6Hashes).forEach { assertEquals(64, it.length) }
        val json = WatchdogJson.encodeToString(StoredFingerprint.serializer(), stored)
        assertFalse(json.contains("192.168"))
        assertFalse(json.contains("fe80"))
        assertEquals(stored, WatchdogJson.decodeFromString(StoredFingerprint.serializer(), json))
    }

    @Test
    fun invitationAcceptsFullLinkOrBareToken() {
        val token = SAMPLE_INVITATION
        assertEquals(token, InvitationToken.parse(token))
        assertEquals(token, InvitationToken.parse("  $token \n"))
        assertEquals(token, InvitationToken.parse("https://join.example.org/join#t=$token"))
        assertEquals(token, InvitationToken.parse("https://join.example.org/join#x=1&t=$token"))
        assertNotNull(InvitationToken.parse("a".repeat(20)))
    }

    @Test
    fun appLinkFromTheWebJoinPageCarriesTheToken() {
        val token = SAMPLE_INVITATION
        assertEquals(token, InvitationToken.fromAppLink("feluda://join#t=$token"))
        assertEquals(token, InvitationToken.fromAppLink("feluda://join/#t=$token"))
        assertEquals(token, InvitationToken.fromAppLink("FELUDA://join#x=1&t=$token"))
        assertEquals(token, InvitationToken.fromAppLink("feluda://join?t=$token"))
    }

    @Test
    fun otherAddressesAreNotInvitationAppLinks() {
        val token = SAMPLE_INVITATION
        assertNull(InvitationToken.fromAppLink(null))
        assertNull(InvitationToken.fromAppLink(""))
        assertNull(InvitationToken.fromAppLink("feluda://join"))
        assertNull(InvitationToken.fromAppLink("feluda://join#t=short"))
        assertNull(InvitationToken.fromAppLink("feluda://joiner#t=$token"))
        assertNull(InvitationToken.fromAppLink("feluda://other#t=$token"))
        assertNull("a web link is pasted, not opened as an app link", InvitationToken.fromAppLink("https://join.example.org/join#t=$token"))
        assertNull(InvitationToken.fromAppLink(token))
    }

    @Test
    fun invitationRejectsMalformedInput() {
        assertNull(InvitationToken.parse(""))
        assertNull(InvitationToken.parse("short"))
        assertNull(InvitationToken.parse("https://join.example.org/join"))
        assertNull(InvitationToken.parse("https://join.example.org/join#t=has spaces in it not valid"))
        assertNull(InvitationToken.parse("a".repeat(129)))
        assertNull(InvitationToken.parse("token-with-bad-chars!!!!!!!!!!"))
    }
}
