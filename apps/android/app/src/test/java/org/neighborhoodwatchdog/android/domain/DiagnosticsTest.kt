package org.neighborhoodwatchdog.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.neighborhoodwatchdog.android.domain.model.BandEvidence
import org.neighborhoodwatchdog.android.domain.model.Diagnostics
import org.neighborhoodwatchdog.android.domain.model.Transport
import org.neighborhoodwatchdog.android.domain.monitoring.DiagnosticsBuilder
import org.neighborhoodwatchdog.android.domain.monitoring.NetworkSnapshot
import org.neighborhoodwatchdog.android.domain.monitoring.PowerEvidence
import org.neighborhoodwatchdog.android.domain.monitoring.PowerObservation
import org.neighborhoodwatchdog.android.domain.monitoring.WifiBand

class DiagnosticsTest {
    @Test
    fun nothingMeasuredMeansEverythingNull() {
        val built = DiagnosticsBuilder.build(null, null, null, null, null, null)
        assertEquals("only band_evidence has a value, and it says unknown", Diagnostics(), built)
        assertEquals(BandEvidence.BAND_UNKNOWN, built.bandEvidence)
    }

    @Test
    fun measuredValuesAreCopiedAndUnknownOnesStayNull() {
        val snapshot = NetworkSnapshot(
            wifiAssociated = false,
            wifiValidated = true, // stale flag from a network that is gone: must not be reported
            cellularAvailable = true,
            defaultTransport = Transport.CELLULAR,
            airplaneMode = false,
        )
        val built = DiagnosticsBuilder.build(snapshot, -61, 2437, true, null, null)
        assertEquals(false, built.wifiAssociated)
        assertNull(built.wifiValidated)
        assertNull(built.internetReachable)
        assertNull(built.localGatewayReachable)
        assertNull(built.homeNetworkMatch)
        assertNull(built.vpnActive)
        assertNull(built.batterySaver)
        assertNull(built.userInitiated)
        assertNull(built.externalPowerLostRecently)
        assertEquals(true, built.cellularAvailable)
        assertEquals(Transport.CELLULAR, built.defaultTransport)
        assertEquals(-61, built.lastWifiRssiDbm)
        assertEquals(BandEvidence.BAND_2_4_OBSERVED, built.bandEvidence)
        assertEquals(true, built.networkChangedRecently)
    }

    @Test
    fun bandComesFromTheFrequencyOnly() {
        assertEquals(BandEvidence.BAND_2_4_OBSERVED, WifiBand.fromFrequencyMhz(2412))
        assertEquals(BandEvidence.BAND_2_4_OBSERVED, WifiBand.fromFrequencyMhz(2484))
        assertEquals(BandEvidence.BAND_5_OBSERVED, WifiBand.fromFrequencyMhz(5180))
        assertEquals(BandEvidence.BAND_5_OBSERVED, WifiBand.fromFrequencyMhz(5885))
        assertEquals(BandEvidence.BAND_6_OBSERVED, WifiBand.fromFrequencyMhz(5955))
        assertEquals(BandEvidence.BAND_6_OBSERVED, WifiBand.fromFrequencyMhz(7115))
        assertEquals(BandEvidence.BAND_UNKNOWN, WifiBand.fromFrequencyMhz(null))
        assertEquals(BandEvidence.BAND_UNKNOWN, WifiBand.fromFrequencyMhz(-1))
        assertEquals(BandEvidence.BAND_UNKNOWN, WifiBand.fromFrequencyMhz(60480))
    }

    @Test
    fun implausibleRssiIsNotReported() {
        assertEquals(-40, WifiBand.validRssi(-40))
        assertNull(WifiBand.validRssi(-127))
        assertNull(WifiBand.validRssi(Int.MIN_VALUE))
        assertNull(WifiBand.validRssi(5))
        assertNull(WifiBand.validRssi(null))
    }

    @Test
    fun externalPowerLostRecentlyFollowsTheThreeCases() {
        val event = 1_000_000L
        // Unplugged 10 s before or after the event: true.
        assertEquals(true, PowerEvidence.externalPowerLostRecently(event, PowerObservation(false, event - 10_000)))
        assertEquals(true, PowerEvidence.externalPowerLostRecently(event, PowerObservation(false, event + 3_000)))
        // Charging and stayed charging: false.
        assertEquals(false, PowerEvidence.externalPowerLostRecently(event, PowerObservation(true, null)))
        assertEquals(false, PowerEvidence.externalPowerLostRecently(event, PowerObservation(true, event - 60_000)))
        // Not charging (unplugged long ago or never plugged): null, not false.
        assertNull(PowerEvidence.externalPowerLostRecently(event, PowerObservation(false, event - 60_000)))
        assertNull(PowerEvidence.externalPowerLostRecently(event, PowerObservation(false, null)))
        assertNull(PowerEvidence.externalPowerLostRecently(event, PowerObservation(null, null)))
        assertNull(PowerEvidence.externalPowerLostRecently(event, null))
    }
}
