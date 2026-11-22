package org.neighborhoodwatchdog.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.Diagnostics
import org.neighborhoodwatchdog.android.domain.model.EventType
import org.neighborhoodwatchdog.android.domain.model.GapReason
import org.neighborhoodwatchdog.android.domain.monitoring.AliveMark
import org.neighborhoodwatchdog.android.domain.monitoring.DiagnosticsBuilder
import org.neighborhoodwatchdog.android.domain.monitoring.GapDetector
import org.neighborhoodwatchdog.android.domain.monitoring.Reconciler
import java.time.Instant

class GapDetectorTest {
    private val wall = 1_800_000_000_000L
    private val last = AliveMark(wallMs = wall, elapsedMs = 500_000, bootCount = 7)

    private fun detect(
        now: AliveMark,
        last: AliveMark? = this.last,
        rebootSeen: Boolean = false,
        sameProcess: Boolean = false,
        permissionMissing: Boolean = false,
        backgroundRestricted: Boolean = false,
    ) = GapDetector.detect(last, now, rebootSeen, sameProcess, permissionMissing, backgroundRestricted)

    @Test
    fun firstStartHasNoGap() {
        assertNull(detect(AliveMark(wall, 1, 7), last = null))
    }

    @Test
    fun twoMinutesOrLessIsNotAGap() {
        assertNull(detect(AliveMark(wall + 120_000, 620_000, 7)))
        assertNotNull(detect(AliveMark(wall + 120_001, 620_001, 7)))
    }

    @Test
    fun processTerminationIsReportedWithTheLastAliveTime() {
        val gap = detect(AliveMark(wall + 900_000, 1_400_000, 7))!!
        assertEquals(GapReason.PROCESS_TERMINATED, gap.reason)
        assertEquals(wall, gap.startedAtMs)
        assertEquals(wall + 900_000, gap.endedAtMs)
    }

    @Test
    fun rebootIsDetectedByTheBootReceiver() {
        val gap = detect(AliveMark(wall + 300_000, 40_000, 7), rebootSeen = true)!!
        assertEquals(GapReason.DEVICE_REBOOT, gap.reason)
        assertEquals(wall, gap.startedAtMs)
    }

    @Test
    fun rebootIsDetectedByBootCountOrClockResetEvenWithoutTheReceiver() {
        assertEquals(GapReason.DEVICE_REBOOT, detect(AliveMark(wall + 300_000, 900_000, 8))!!.reason)
        assertEquals(GapReason.DEVICE_REBOOT, detect(AliveMark(wall + 300_000, 40_000, null), last = last.copy(bootCount = null))!!.reason)
    }

    @Test
    fun quickRebootUnderTwoMinutesIsNotAGap() {
        assertNull(detect(AliveMark(wall + 60_000, 20_000, 8), rebootSeen = true))
    }

    @Test
    fun sameProcessMeansSuspendedNotTerminated() {
        val gap = detect(AliveMark(wall + 600_000, 1_100_000, 7), sameProcess = true)!!
        assertEquals(GapReason.APP_SUSPENDED, gap.reason)
    }

    @Test
    fun permissionAndBackgroundRestrictionAreNamedAsReasons() {
        val now = AliveMark(wall + 600_000, 1_100_000, 7)
        assertEquals(GapReason.PERMISSION_REVOKED, detect(now, permissionMissing = true, backgroundRestricted = true)!!.reason)
        assertEquals(GapReason.BACKGROUND_RESTRICTED, detect(now, backgroundRestricted = true)!!.reason)
    }

    @Test
    fun wallClockChangeDoesNotInventOrHideAGapWithinOneBoot() {
        // The user set the clock forward one day; only 30 real seconds passed.
        assertNull(detect(AliveMark(wall + 86_400_000, 530_000, 7)))
        // The clock went backwards, but 10 real minutes passed: the gap ends "now" and lasts 10 minutes.
        val gap = detect(AliveMark(wall - 3_600_000, 1_100_000, 7))!!
        assertEquals(600_000, gap.endedAtMs - gap.startedAtMs)
    }

    @Test
    fun gapFieldsAreCopiedIntoDiagnostics() {
        val gap = detect(AliveMark(wall + 900_000, 1_400_000, 7))!!
        val diagnostics = DiagnosticsBuilder.withGap(Diagnostics(), gap)
        assertEquals(Instant.ofEpochMilli(wall), diagnostics.gapStartedAt)
        assertEquals(Instant.ofEpochMilli(wall + 900_000), diagnostics.gapEndedAt)
        assertEquals(GapReason.PROCESS_TERMINATED, diagnostics.gapReason)
    }

    @Test
    fun reconciliationPicksTheEventThatMatchesTheStateChange() {
        assertEquals(EventType.WIFI_LOST, Reconciler.eventFor(ConnectivityState.HEALTHY, ConnectivityState.WIFI_DISCONNECTED))
        assertEquals(EventType.WIFI_LOST, Reconciler.eventFor(ConnectivityState.HEALTHY, ConnectivityState.INTERNET_UNAVAILABLE))
        assertEquals(EventType.WIFI_RESTORED, Reconciler.eventFor(ConnectivityState.WIFI_DISCONNECTED, ConnectivityState.HEALTHY))
        assertEquals(
            EventType.INTERNET_REACHABILITY_CHANGED,
            Reconciler.eventFor(ConnectivityState.HEALTHY, ConnectivityState.WIFI_CONNECTED_NO_INTERNET),
        )
        assertEquals(
            EventType.CELLULAR_UNAVAILABLE,
            Reconciler.eventFor(ConnectivityState.WIFI_DISCONNECTED, ConnectivityState.INTERNET_UNAVAILABLE),
        )
        assertEquals(
            EventType.CELLULAR_AVAILABLE,
            Reconciler.eventFor(ConnectivityState.INTERNET_UNAVAILABLE, ConnectivityState.WIFI_DISCONNECTED),
        )
    }

    @Test
    fun reconciliationEmitsNothingWhenNothingChangedOrNothingWasKnown() {
        assertNull(Reconciler.eventFor(ConnectivityState.HEALTHY, ConnectivityState.HEALTHY))
        assertNull(Reconciler.eventFor(null, ConnectivityState.HEALTHY))
        assertNull(Reconciler.eventFor(ConnectivityState.MONITORING_PAUSED, ConnectivityState.HEALTHY))
        assertNull(Reconciler.eventFor(ConnectivityState.UNKNOWN, ConnectivityState.WIFI_DISCONNECTED))
        assertNull(Reconciler.eventFor(ConnectivityState.HEALTHY, ConnectivityState.UNKNOWN))
    }
}
