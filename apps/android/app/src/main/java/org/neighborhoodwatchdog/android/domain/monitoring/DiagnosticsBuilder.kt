package org.neighborhoodwatchdog.android.domain.monitoring

import org.neighborhoodwatchdog.android.domain.model.Diagnostics
import java.time.Instant
import kotlin.math.abs

/** What is known about the charger around the time of an event. */
data class PowerObservation(
    /** The phone is on external power now. Null when the OS did not say. */
    val chargingNow: Boolean?,
    /** Wall-clock time the charger was last unplugged in this process, if seen. */
    val lastUnpluggedAtMs: Long?,
)

object PowerEvidence {
    const val WINDOW_MS: Long = 15_000

    /**
     * True only if the phone was charging and the charger was unplugged within 15 seconds of the
     * event. False if it is charging (and so stayed charging). Null if it was not charging or the
     * power state is not known.
     */
    fun externalPowerLostRecently(eventAtMs: Long, power: PowerObservation?): Boolean? {
        if (power == null) return null
        val unplugged = power.lastUnpluggedAtMs
        if (unplugged != null && abs(unplugged - eventAtMs) <= WINDOW_MS) return true
        return if (power.chargingNow == true) false else null
    }
}

object DiagnosticsBuilder {
    /** Copies measured values only. Anything not measured stays null. */
    fun build(
        snapshot: NetworkSnapshot?,
        lastWifiRssiDbm: Int?,
        lastWifiFrequencyMhz: Int?,
        networkChangedRecently: Boolean?,
        userInitiated: Boolean?,
        externalPowerLostRecently: Boolean?,
    ): Diagnostics = Diagnostics(
        wifiAssociated = snapshot?.wifiAssociated,
        wifiValidated = if (snapshot?.wifiAssociated == true) snapshot.wifiValidated else null,
        wifiRadioEnabled = snapshot?.wifiRadioEnabled,
        airplaneMode = snapshot?.airplaneMode,
        homeNetworkMatch = snapshot?.homeNetworkMatch,
        localGatewayReachable = snapshot?.localGatewayReachable,
        internetReachable = snapshot?.internetReachable,
        cellularAvailable = snapshot?.cellularAvailable,
        defaultTransport = snapshot?.defaultTransport,
        networkChangedRecently = networkChangedRecently,
        vpnActive = snapshot?.vpnActive,
        lastWifiRssiDbm = WifiBand.validRssi(lastWifiRssiDbm),
        bandEvidence = WifiBand.fromFrequencyMhz(lastWifiFrequencyMhz),
        externalPowerLostRecently = externalPowerLostRecently,
        batterySaver = snapshot?.batterySaver,
        userInitiated = userInitiated,
    )

    fun forEvent(event: DetectedEvent, power: PowerObservation?): Diagnostics = build(
        snapshot = event.snapshot,
        lastWifiRssiDbm = event.lastWifiRssiDbm,
        lastWifiFrequencyMhz = event.lastWifiFrequencyMhz,
        networkChangedRecently = event.networkChangedRecently,
        userInitiated = event.userInitiated,
        externalPowerLostRecently = PowerEvidence.externalPowerLostRecently(event.occurredAtMs, power),
    )

    fun withGap(base: Diagnostics, gap: MonitoringGap): Diagnostics = base.copy(
        gapStartedAt = Instant.ofEpochMilli(gap.startedAtMs),
        gapEndedAt = Instant.ofEpochMilli(gap.endedAtMs),
        gapReason = gap.reason,
    )
}
