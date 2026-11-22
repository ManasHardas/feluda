package org.neighborhoodwatchdog.android.domain.monitoring

import org.neighborhoodwatchdog.android.domain.model.BandEvidence
import org.neighborhoodwatchdog.android.domain.model.Transport

/**
 * What the device knows about its network paths at one moment. Plain Kotlin so the state machine can
 * run in JVM unit tests. Every nullable field is null when the value was not measured.
 */
data class NetworkSnapshot(
    /** A Wi-Fi network is associated (the OS reports an available Wi-Fi network). */
    val wifiAssociated: Boolean? = null,
    /** The OS marks the Wi-Fi network as validated (its own internet check passed). */
    val wifiValidated: Boolean? = null,
    /** Result of the app's own HTTPS probe over the current best path. Null until a probe finished. */
    val internetReachable: Boolean? = null,
    /**
     * A cellular data network is up. Null when it cannot be told: many phones keep mobile data down
     * while Wi-Fi works, so "no cellular network right now" is not evidence that cellular is missing.
     */
    val cellularAvailable: Boolean? = null,
    /** Another usable path exists (for example Ethernet). */
    val otherPathAvailable: Boolean = false,
    val defaultTransport: Transport? = null,
    val homeNetworkMatch: Boolean? = null,
    val localGatewayReachable: Boolean? = null,
    val wifiRadioEnabled: Boolean? = null,
    val airplaneMode: Boolean? = null,
    val vpnActive: Boolean? = null,
    val wifiRssiDbm: Int? = null,
    val wifiFrequencyMhz: Int? = null,
    val batterySaver: Boolean? = null,
) {
    /** Internet over Wi-Fi: the app's probe when there is one, otherwise the OS validation flag. */
    val wifiInternet: Boolean?
        get() = if (wifiAssociated == true) internetReachable ?: wifiValidated else null
}

object WifiBand {
    /** Maps a channel centre frequency to the band that was observed. Unknown stays unknown. */
    fun fromFrequencyMhz(frequencyMhz: Int?): BandEvidence = when (frequencyMhz) {
        null -> BandEvidence.BAND_UNKNOWN
        in 2400..2500 -> BandEvidence.BAND_2_4_OBSERVED
        in 4900..5924 -> BandEvidence.BAND_5_OBSERVED
        in 5925..7125 -> BandEvidence.BAND_6_OBSERVED
        else -> BandEvidence.BAND_UNKNOWN
    }

    /** RSSI is only reported when it is a plausible dBm reading; the OS uses -127 for "invalid". */
    fun validRssi(rssiDbm: Int?): Int? = rssiDbm?.takeIf { it in -126..0 }
}
