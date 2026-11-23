package org.neighborhoodwatchdog.android.domain.monitoring

import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.EventType
import org.neighborhoodwatchdog.android.domain.model.GapReason

/** A persisted "the monitor was alive at this moment" mark. */
data class AliveMark(
    /** Wall-clock time (epoch milliseconds). */
    val wallMs: Long,
    /** Milliseconds since boot, including sleep. Not affected by wall-clock changes. */
    val elapsedMs: Long,
    /** The OS boot counter when it could be read. */
    val bootCount: Int?,
)

data class MonitoringGap(val startedAtMs: Long, val endedAtMs: Long, val reason: GapReason)

object GapDetector {
    /** The monitor not running for longer than this is a gap that must be reported. */
    const val GAP_THRESHOLD_MS: Long = 2 * 60_000

    /**
     * @param last the mark persisted before the monitor stopped running, or null on first start.
     * @param rebootSeen the boot-completed receiver ran since [last] was written.
     * @param sameProcess the process that wrote [last] is still this process (it was suspended,
     *   not killed).
     */
    fun detect(
        last: AliveMark?,
        now: AliveMark,
        rebootSeen: Boolean,
        sameProcess: Boolean,
        permissionMissing: Boolean,
        backgroundRestricted: Boolean,
    ): MonitoringGap? {
        if (last == null) return null
        val rebooted = rebootSeen ||
            (last.bootCount != null && now.bootCount != null && last.bootCount != now.bootCount) ||
            now.elapsedMs < last.elapsedMs
        val duration = if (rebooted) now.wallMs - last.wallMs else now.elapsedMs - last.elapsedMs
        if (duration <= GAP_THRESHOLD_MS) return null
        val start = if (rebooted) minOf(last.wallMs, now.wallMs) else now.wallMs - duration
        val reason = when {
            rebooted -> GapReason.DEVICE_REBOOT
            permissionMissing -> GapReason.PERMISSION_REVOKED
            sameProcess -> GapReason.APP_SUSPENDED
            backgroundRestricted -> GapReason.BACKGROUND_RESTRICTED
            else -> GapReason.PROCESS_TERMINATED
        }
        return MonitoringGap(start, now.wallMs, reason)
    }
}

/** After a gap: which event describes the difference between the state before and the state now. */
object Reconciler {
    private val noWifi = setOf(
        ConnectivityState.WIFI_DISCONNECTED,
        ConnectivityState.INTERNET_UNAVAILABLE,
        ConnectivityState.CELLULAR_AVAILABLE,
        ConnectivityState.CELLULAR_UNAVAILABLE,
    )
    private val onWifi = setOf(ConnectivityState.HEALTHY, ConnectivityState.WIFI_CONNECTED_NO_INTERNET)

    /** Null when nothing changed or when either state says nothing about the network. */
    fun eventFor(before: ConnectivityState?, now: ConnectivityState): EventType? {
        if (before == null || before == now) return null
        if (before !in noWifi && before !in onWifi) return null
        return when {
            now == ConnectivityState.HEALTHY && before in noWifi -> EventType.WIFI_RESTORED
            now in onWifi && before in onWifi -> EventType.INTERNET_REACHABILITY_CHANGED
            now == ConnectivityState.WIFI_CONNECTED_NO_INTERNET && before in noWifi -> EventType.WIFI_ASSOCIATION_CHANGED
            now in noWifi && before in onWifi -> EventType.WIFI_LOST
            now == ConnectivityState.INTERNET_UNAVAILABLE && before in noWifi -> EventType.CELLULAR_UNAVAILABLE
            now in noWifi && before == ConnectivityState.INTERNET_UNAVAILABLE -> EventType.CELLULAR_AVAILABLE
            else -> null
        }
    }
}
