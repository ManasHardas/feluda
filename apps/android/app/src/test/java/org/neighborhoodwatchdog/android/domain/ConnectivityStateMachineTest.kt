package org.neighborhoodwatchdog.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.EventType
import org.neighborhoodwatchdog.android.domain.model.Transport
import org.neighborhoodwatchdog.android.domain.monitoring.ConnectivityStateMachine
import org.neighborhoodwatchdog.android.domain.monitoring.DetectedEvent
import org.neighborhoodwatchdog.android.domain.monitoring.MachineOutput
import org.neighborhoodwatchdog.android.domain.monitoring.NetworkSnapshot

class ConnectivityStateMachineTest {
    private val healthyWifi = NetworkSnapshot(
        wifiAssociated = true,
        wifiValidated = true,
        internetReachable = true,
        cellularAvailable = null,
        defaultTransport = Transport.WIFI,
        wifiRadioEnabled = true,
        airplaneMode = false,
        wifiRssiDbm = -52,
        wifiFrequencyMhz = 5180,
    )
    private val onCellular = NetworkSnapshot(
        wifiAssociated = false,
        cellularAvailable = true,
        defaultTransport = Transport.CELLULAR,
        wifiRadioEnabled = true,
        airplaneMode = false,
    )
    private val nothing = NetworkSnapshot(
        wifiAssociated = false,
        cellularAvailable = false,
        defaultTransport = Transport.NONE,
        wifiRadioEnabled = true,
        airplaneMode = false,
    )

    /** A machine whose baseline is [baseline], settled at t = 10 s. */
    private fun machineAt(baseline: NetworkSnapshot, hadWifiBefore: Boolean = false): ConnectivityStateMachine {
        val machine = ConnectivityStateMachine(hadWifiBefore = hadWifiBefore)
        machine.onSnapshot(baseline, 0)
        val out = machine.onTick(10_000)
        assertTrue(out.baselineEstablished)
        assertTrue(out.events.isEmpty())
        return machine
    }

    private fun types(out: MachineOutput): List<EventType> = out.events.map(DetectedEvent::type)

    @Test
    fun baselineProducesNoEventsAndStartsUnknown() {
        val machine = ConnectivityStateMachine()
        val first = machine.onSnapshot(healthyWifi, 0)
        assertEquals(ConnectivityState.UNKNOWN, first.state)
        assertEquals(5_000L, first.nextCheckAtMs)
        assertFalse(machine.onTick(4_999).baselineEstablished)
        val settled = machine.onTick(5_000)
        assertTrue(settled.baselineEstablished)
        assertTrue(settled.events.isEmpty())
        assertEquals(ConnectivityState.HEALTHY, settled.state)
    }

    @Test
    fun wifiLossIsReportedAfterDebounceWithTimeOfFirstCallback() {
        val machine = machineAt(healthyWifi)
        val pending = machine.onSnapshot(onCellular, 60_000)
        assertEquals(ConnectivityState.NETWORK_TRANSITION, pending.state)
        assertTrue(pending.events.isEmpty())
        assertEquals(65_000L, pending.nextCheckAtMs)
        assertTrue(machine.onTick(64_000).events.isEmpty())

        val settled = machine.onTick(65_000)
        assertEquals(ConnectivityState.WIFI_DISCONNECTED, settled.state)
        assertTrue(EventType.WIFI_LOST in types(settled))
        val lost = settled.events.first { it.type == EventType.WIFI_LOST }
        assertEquals("occurred_at is the first callback, not the end of the debounce", 60_000L, lost.occurredAtMs)
        assertEquals(ConnectivityState.WIFI_DISCONNECTED, lost.state)
        assertEquals("last RSSI seen before the loss", -52, lost.lastWifiRssiDbm)
        assertEquals(5180, lost.lastWifiFrequencyMhz)
        assertNull("the app does not know the user caused it", lost.userInitiated)
    }

    @Test
    fun cellularAvailableWhileWifiFails() {
        val machine = machineAt(healthyWifi)
        machine.onSnapshot(onCellular, 60_000)
        val settled = machine.onTick(65_000)
        assertEquals(
            listOf(EventType.WIFI_LOST, EventType.CELLULAR_AVAILABLE, EventType.DEFAULT_NETWORK_CHANGED),
            types(settled),
        )
        assertEquals(true, settled.events.first().snapshot.cellularAvailable)
    }

    @Test
    fun cellularUnavailableMeansInternetUnavailable() {
        val machine = machineAt(healthyWifi)
        machine.onSnapshot(nothing, 60_000)
        val settled = machine.onTick(65_000)
        assertEquals(ConnectivityState.INTERNET_UNAVAILABLE, settled.state)
        assertEquals(listOf(EventType.WIFI_LOST), types(settled))
        assertEquals(false, settled.events.single().snapshot.cellularAvailable)
    }

    @Test
    fun cellularDisappearingLaterIsItsOwnEvent() {
        val machine = machineAt(healthyWifi)
        machine.onSnapshot(onCellular, 60_000)
        machine.onTick(65_000)
        machine.onSnapshot(nothing, 100_000)
        val settled = machine.onTick(105_000)
        assertEquals(ConnectivityState.INTERNET_UNAVAILABLE, settled.state)
        assertEquals(listOf(EventType.CELLULAR_UNAVAILABLE), types(settled))
        assertEquals(100_000L, settled.events.single().occurredAtMs)
    }

    @Test
    fun reconnectionWithInternetIsWifiRestored() {
        val machine = machineAt(healthyWifi)
        machine.onSnapshot(onCellular, 60_000)
        machine.onTick(65_000)
        // Wi-Fi associates first; validation and the probe result arrive a little later.
        machine.onSnapshot(healthyWifi.copy(wifiValidated = false, internetReachable = null, cellularAvailable = true), 120_000)
        machine.onSnapshot(healthyWifi.copy(cellularAvailable = true), 122_000)
        assertTrue(machine.onTick(126_000).events.isEmpty())
        val settled = machine.onTick(127_000)
        assertEquals(ConnectivityState.HEALTHY, settled.state)
        assertTrue(EventType.WIFI_RESTORED in types(settled))
        assertEquals(120_000L, settled.events.first { it.type == EventType.WIFI_RESTORED }.occurredAtMs)
        assertTrue("a change followed another within a minute", settled.events.first().networkChangedRecently)
    }

    @Test
    fun wifiBackWithoutInternetIsNotARestore() {
        val machine = machineAt(healthyWifi)
        machine.onSnapshot(onCellular, 60_000)
        machine.onTick(65_000)
        val noInternet = healthyWifi.copy(wifiValidated = false, internetReachable = false, cellularAvailable = true, defaultTransport = Transport.CELLULAR)
        machine.onSnapshot(noInternet, 120_000)
        val associated = machine.onTick(125_000)
        assertEquals(ConnectivityState.WIFI_CONNECTED_NO_INTERNET, associated.state)
        assertEquals(listOf(EventType.WIFI_ASSOCIATION_CHANGED), types(associated))

        machine.onSnapshot(healthyWifi.copy(cellularAvailable = true), 200_000)
        val restored = machine.onTick(205_000)
        assertEquals(ConnectivityState.HEALTHY, restored.state)
        assertTrue(EventType.WIFI_RESTORED in types(restored))
        assertFalse(EventType.INTERNET_REACHABILITY_CHANGED in types(restored))
    }

    @Test
    fun wifiWithoutInternetFlipsReachabilityBothWays() {
        val machine = machineAt(healthyWifi)
        machine.onSnapshot(healthyWifi.copy(wifiValidated = false, internetReachable = false), 60_000)
        val down = machine.onTick(65_000)
        assertEquals(ConnectivityState.WIFI_CONNECTED_NO_INTERNET, down.state)
        assertEquals(listOf(EventType.INTERNET_REACHABILITY_CHANGED), types(down))
        assertEquals(true, down.events.single().snapshot.wifiAssociated)
        assertEquals(false, down.events.single().snapshot.internetReachable)

        machine.onSnapshot(healthyWifi, 300_000)
        val up = machine.onTick(305_000)
        assertEquals(ConnectivityState.HEALTHY, up.state)
        assertEquals(listOf(EventType.INTERNET_REACHABILITY_CHANGED), types(up))
    }

    @Test
    fun unknownReachabilityInBetweenStillCountsAsAFlip() {
        val machine = machineAt(healthyWifi)
        machine.onSnapshot(healthyWifi.copy(wifiValidated = null, internetReachable = null), 60_000)
        val unknown = machine.onTick(65_000)
        assertEquals(ConnectivityState.UNKNOWN, unknown.state)
        assertTrue(unknown.events.isEmpty())
        machine.onSnapshot(healthyWifi.copy(wifiValidated = false, internetReachable = false), 70_000)
        assertEquals(listOf(EventType.INTERNET_REACHABILITY_CHANGED), types(machine.onTick(75_000)))
    }

    @Test
    fun airplaneModeIsUserInitiatedAndLeavesNoPath() {
        val machine = machineAt(healthyWifi.copy(cellularAvailable = true))
        machine.onSnapshot(nothing.copy(airplaneMode = true, wifiRadioEnabled = false), 60_000)
        val settled = machine.onTick(65_000)
        assertEquals(ConnectivityState.INTERNET_UNAVAILABLE, settled.state)
        assertEquals(listOf(EventType.WIFI_LOST, EventType.CELLULAR_UNAVAILABLE), types(settled))
        val lost = settled.events.first()
        assertEquals(true, lost.userInitiated)
        assertEquals(true, lost.snapshot.airplaneMode)
        assertNull("only the Wi-Fi loss carries the user flag", settled.events[1].userInitiated)
    }

    @Test
    fun wifiSwitchedOffByUserIsUserInitiated() {
        val machine = machineAt(healthyWifi)
        machine.onSnapshot(onCellular.copy(wifiRadioEnabled = false), 60_000)
        val lost = machine.onTick(65_000).events.first { it.type == EventType.WIFI_LOST }
        assertEquals(true, lost.userInitiated)
    }

    @Test
    fun roamWithinDebounceProducesNoEvent() {
        val machine = machineAt(healthyWifi)
        val dropped = machine.onSnapshot(onCellular, 60_000)
        assertEquals(ConnectivityState.NETWORK_TRANSITION, dropped.state)
        val back = machine.onSnapshot(healthyWifi, 62_000)
        assertEquals(ConnectivityState.HEALTHY, back.state)
        assertTrue(back.events.isEmpty())
        assertNull(back.nextCheckAtMs)
        assertTrue(machine.onTick(70_000).events.isEmpty())
    }

    @Test
    fun eachNewChangeRestartsTheQuietPeriod() {
        val machine = machineAt(healthyWifi)
        machine.onSnapshot(nothing, 60_000)
        val second = machine.onSnapshot(onCellular, 63_000)
        assertEquals(68_000L, second.nextCheckAtMs)
        assertTrue(machine.onTick(65_000).events.isEmpty())
        val settled = machine.onTick(68_000)
        assertEquals(ConnectivityState.WIFI_DISCONNECTED, settled.state)
        assertEquals(60_000L, settled.events.first().occurredAtMs)
    }

    @Test
    fun flappingNetworkIsSettledAfterTheMaximumTransitionTime() {
        val machine = machineAt(healthyWifi)
        var t = 60_000L
        var out = machine.onSnapshot(onCellular, t)
        // Alternate between two non-settled pictures every 3 seconds so the quiet period never ends.
        var flip = false
        while (out.events.isEmpty() && t < 100_000) {
            t += 3_000
            flip = !flip
            out = machine.onSnapshot(if (flip) nothing else onCellular, t)
        }
        assertTrue("settled by the 20 s cap, at $t", t <= 81_000)
        assertTrue(EventType.WIFI_LOST in types(out))
        assertEquals(60_000L, out.events.first().occurredAtMs)
    }

    @Test
    fun defaultNetworkMovingToAnotherTransportIsReported() {
        val machine = machineAt(healthyWifi.copy(cellularAvailable = true))
        machine.onSnapshot(healthyWifi.copy(cellularAvailable = true, defaultTransport = Transport.VPN, vpnActive = true), 60_000)
        val settled = machine.onTick(65_000)
        assertEquals(listOf(EventType.DEFAULT_NETWORK_CHANGED), types(settled))
        assertEquals(ConnectivityState.HEALTHY, settled.state)
    }

    @Test
    fun homeToNotHomeIsAnAssociationChange() {
        val machine = machineAt(healthyWifi.copy(homeNetworkMatch = true))
        machine.onSnapshot(healthyWifi.copy(homeNetworkMatch = false), 60_000)
        assertEquals(listOf(EventType.WIFI_ASSOCIATION_CHANGED), types(machine.onTick(65_000)))
    }

    @Test
    fun deviceThatNeverHadWifiReportsCellularOnlyStates() {
        val machine = machineAt(onCellular)
        assertEquals(ConnectivityState.CELLULAR_AVAILABLE, machine.currentState())
        machine.onSnapshot(nothing, 60_000)
        val settled = machine.onTick(65_000)
        assertEquals(ConnectivityState.INTERNET_UNAVAILABLE, settled.state)
        assertEquals(listOf(EventType.CELLULAR_UNAVAILABLE), types(settled))

        val ethernetOnly = ConnectivityStateMachine()
        ethernetOnly.onSnapshot(nothing.copy(otherPathAvailable = true, defaultTransport = Transport.ETHERNET), 0)
        assertEquals(ConnectivityState.CELLULAR_UNAVAILABLE, ethernetOnly.onTick(5_000).state)
    }

    @Test
    fun deviceThatHadWifiBeforeIsWifiDisconnectedNotCellularOnly() {
        val machine = machineAt(onCellular, hadWifiBefore = true)
        assertEquals(ConnectivityState.WIFI_DISCONNECTED, machine.currentState())
    }

    @Test
    fun signalStrengthNeedsFifteenDbAndTenMinutes() {
        val machine = machineAt(healthyWifi) // baseline -52 dBm
        assertTrue(machine.onSnapshot(healthyWifi.copy(wifiRssiDbm = -60), 20_000).events.isEmpty())
        val first = machine.onSnapshot(healthyWifi.copy(wifiRssiDbm = -70), 30_000)
        assertEquals(listOf(EventType.SIGNAL_STRENGTH_CHANGED), types(first))
        assertEquals(-70, first.events.single().lastWifiRssiDbm)
        // A second big change inside 10 minutes is not reported...
        assertTrue(machine.onSnapshot(healthyWifi.copy(wifiRssiDbm = -45), 60_000).events.isEmpty())
        // ...but is once 10 minutes have passed.
        val later = machine.onSnapshot(healthyWifi.copy(wifiRssiDbm = -45), 30_000 + 10 * 60_000)
        assertEquals(listOf(EventType.SIGNAL_STRENGTH_CHANGED), types(later))
    }

    @Test
    fun invalidRssiIsNeverTreatedAsAMeasurement() {
        val machine = machineAt(healthyWifi.copy(wifiRssiDbm = -127))
        assertNull(machine.lastSeenWifiRssiDbm)
        assertTrue(machine.onSnapshot(healthyWifi.copy(wifiRssiDbm = null), 20_000).events.isEmpty())
    }

    @Test
    fun monitoringDisabledIsNeverHealthy() {
        val machine = machineAt(healthyWifi)
        assertEquals(ConnectivityState.HEALTHY, machine.currentState())
        machine.setMonitoringEnabled(false)
        assertEquals(ConnectivityState.MONITORING_PAUSED, machine.currentState())
        // Even a perfect network picture cannot make a paused monitor report HEALTHY or emit events.
        val out = machine.onSnapshot(onCellular, 60_000)
        assertEquals(ConnectivityState.MONITORING_PAUSED, out.state)
        val settled = machine.onTick(70_000)
        assertEquals(ConnectivityState.MONITORING_PAUSED, settled.state)
        assertTrue(settled.events.isEmpty())
        assertEquals(ConnectivityState.MONITORING_PAUSED, machine.onSnapshot(healthyWifi, 80_000).state)
    }

    @Test
    fun missingPermissionIsItsOwnState() {
        val machine = ConnectivityStateMachine()
        machine.setPermissionMissing(true)
        machine.onSnapshot(NetworkSnapshot(), 0)
        assertEquals(ConnectivityState.MONITORING_PERMISSION_REQUIRED, machine.onTick(5_000).state)
        machine.setPermissionMissing(false)
        assertEquals("nothing is known about the network", ConnectivityState.UNKNOWN, machine.currentState())
    }
}
