package org.neighborhoodwatchdog.android.domain.monitoring

import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.EventType
import org.neighborhoodwatchdog.android.domain.model.Transport
import kotlin.math.abs

/** An event the state machine decided on. The caller turns it into a queued API event. */
data class DetectedEvent(
    val type: EventType,
    val state: ConnectivityState,
    /** Time of the first callback of the change, not the time the debounce finished. */
    val occurredAtMs: Long,
    val snapshot: NetworkSnapshot,
    /** Last RSSI / frequency seen while Wi-Fi was associated (for a loss: the values before it). */
    val lastWifiRssiDbm: Int?,
    val lastWifiFrequencyMhz: Int?,
    val networkChangedRecently: Boolean,
    val userInitiated: Boolean?,
)

data class MachineOutput(
    val state: ConnectivityState,
    val events: List<DetectedEvent>,
    /** When [ConnectivityStateMachine.onTick] must be called to settle a pending change. */
    val nextCheckAtMs: Long?,
    /** True exactly once: the first settled view of the network after start. No events come with it. */
    val baselineEstablished: Boolean = false,
)

/**
 * Turns raw network snapshots into exactly one [ConnectivityState] and a list of real change events.
 *
 * A change is not classified immediately. The machine waits until the picture has been quiet for
 * [Config.debounceMs] (a roam or a band switch drops and regains Wi-Fi within that time and produces
 * no event). If the network keeps flapping, the change is settled anyway after
 * [Config.maxTransitionMs]. Events carry the time of the first callback of the change.
 *
 * Not thread-safe: call it from one thread.
 */
class ConnectivityStateMachine(
    private val config: Config = Config(),
    hadWifiBefore: Boolean = false,
) {
    data class Config(
        val debounceMs: Long = 5_000,
        val maxTransitionMs: Long = 20_000,
        val recentChangeWindowMs: Long = 60_000,
        val rssiMinDeltaDb: Int = 15,
        val rssiMinIntervalMs: Long = 10 * 60_000,
    )

    private var monitoringEnabled = true
    private var permissionMissing = false

    private var stable: NetworkSnapshot? = null
    private var latest: NetworkSnapshot? = null
    private var pendingSinceMs: Long? = null
    private var lastChangeAtMs: Long = 0
    private var lastSettledAtMs: Long? = null

    private var hadWifi = hadWifiBefore
    private var awaitingRestore = false
    private var lastKnownWifiInternet: Boolean? = null
    private var lastKnownCellular: Boolean? = null
    private var lastRealDefault: Transport? = null

    private var lastWifiRssi: Int? = null
    private var lastWifiFrequency: Int? = null
    private var rssiBaseline: Int? = null
    private var lastRssiEventAtMs: Long? = null

    val settledSnapshot: NetworkSnapshot? get() = stable
    val latestSnapshot: NetworkSnapshot? get() = latest
    val lastSeenWifiRssiDbm: Int? get() = lastWifiRssi
    val lastSeenWifiFrequencyMhz: Int? get() = lastWifiFrequency

    fun setMonitoringEnabled(enabled: Boolean) {
        monitoringEnabled = enabled
    }

    fun setPermissionMissing(missing: Boolean) {
        permissionMissing = missing
    }

    fun onSnapshot(snapshot: NetworkSnapshot, nowMs: Long): MachineOutput {
        val previous = latest
        latest = snapshot
        if (snapshot.wifiAssociated == true) {
            WifiBand.validRssi(snapshot.wifiRssiDbm)?.let { lastWifiRssi = it }
            snapshot.wifiFrequencyMhz?.let { lastWifiFrequency = it }
        }

        val events = mutableListOf<DetectedEvent>()
        val settled = stable
        if (settled == null) {
            if (pendingSinceMs == null) {
                pendingSinceMs = nowMs
                lastChangeAtMs = nowMs
            } else if (previous == null || material(previous) != material(snapshot)) {
                lastChangeAtMs = nowMs
            }
        } else if (material(snapshot) != material(settled)) {
            if (pendingSinceMs == null) {
                pendingSinceMs = nowMs
                lastChangeAtMs = nowMs
            } else if (previous == null || material(previous) != material(snapshot)) {
                lastChangeAtMs = nowMs
            }
        } else {
            // Back to the settled picture (for example a roam): the blip produces no event.
            pendingSinceMs = null
            stable = snapshot
            signalStrengthEvent(snapshot, nowMs)?.let(events::add)
        }
        return settleIfDue(nowMs, events)
    }

    fun onTick(nowMs: Long): MachineOutput = settleIfDue(nowMs, mutableListOf())

    fun currentState(): ConnectivityState = when {
        !monitoringEnabled -> ConnectivityState.MONITORING_PAUSED
        permissionMissing -> ConnectivityState.MONITORING_PERMISSION_REQUIRED
        stable == null -> ConnectivityState.UNKNOWN
        pendingSinceMs != null -> ConnectivityState.NETWORK_TRANSITION
        else -> classify(stable)
    }

    private fun settleIfDue(nowMs: Long, events: MutableList<DetectedEvent>): MachineOutput {
        var baseline = false
        val since = pendingSinceMs
        val candidate = latest
        if (since != null && candidate != null &&
            (nowMs - lastChangeAtMs >= config.debounceMs || nowMs - since >= config.maxTransitionMs)
        ) {
            val old = stable
            if (old == null) {
                establishBaseline(candidate)
                baseline = true
            } else {
                events += settle(old, candidate, since)
                lastSettledAtMs = nowMs
            }
            stable = candidate
            pendingSinceMs = null
        }
        val pending = pendingSinceMs
        val next = pending?.let { minOf(lastChangeAtMs + config.debounceMs, it + config.maxTransitionMs) }
        // While monitoring is off or a permission is missing nothing is reported as a network event.
        val reportable = if (monitoringEnabled && !permissionMissing) events else emptyList()
        return MachineOutput(currentState(), reportable, next, baseline)
    }

    private fun establishBaseline(snapshot: NetworkSnapshot) {
        if (snapshot.wifiAssociated == true) hadWifi = true
        awaitingRestore = snapshot.wifiAssociated == false
        lastKnownWifiInternet = snapshot.wifiInternet
        lastKnownCellular = snapshot.cellularAvailable
        lastRealDefault = realTransport(snapshot.defaultTransport)
        rssiBaseline = if (snapshot.wifiAssociated == true) WifiBand.validRssi(snapshot.wifiRssiDbm) else null
    }

    private fun settle(old: NetworkSnapshot, new: NetworkSnapshot, changeStartedAtMs: Long): List<DetectedEvent> {
        val types = mutableListOf<EventType>()
        val oldWifi = old.wifiAssociated
        val newWifi = new.wifiAssociated

        if (oldWifi == true && newWifi == false) {
            types += EventType.WIFI_LOST
            awaitingRestore = true
            lastKnownWifiInternet = null
            rssiBaseline = null
        }
        if (newWifi == true) {
            hadWifi = true
            val internet = new.wifiInternet
            if (awaitingRestore) {
                if (internet == true) {
                    types += EventType.WIFI_RESTORED
                    awaitingRestore = false
                } else if (oldWifi != true) {
                    // Associated again but the internet is not (yet) reachable over it.
                    types += EventType.WIFI_ASSOCIATION_CHANGED
                }
            } else if (internet != null && lastKnownWifiInternet != null && internet != lastKnownWifiInternet) {
                types += EventType.INTERNET_REACHABILITY_CHANGED
            }
            if (internet != null) lastKnownWifiInternet = internet
            if (oldWifi == true && old.homeNetworkMatch != null && new.homeNetworkMatch != null &&
                old.homeNetworkMatch != new.homeNetworkMatch
            ) {
                types += EventType.WIFI_ASSOCIATION_CHANGED
            }
            if (oldWifi != true) rssiBaseline = WifiBand.validRssi(new.wifiRssiDbm)
        }

        val cellular = new.cellularAvailable
        if (cellular != null && cellular != lastKnownCellular) {
            if (cellular) {
                types += EventType.CELLULAR_AVAILABLE
            } else if (lastKnownCellular == true) {
                types += EventType.CELLULAR_UNAVAILABLE
            }
            lastKnownCellular = cellular
        }

        val default = realTransport(new.defaultTransport)
        if (default != null) {
            if (lastRealDefault != null && default != lastRealDefault) types += EventType.DEFAULT_NETWORK_CHANGED
            lastRealDefault = default
        }

        val state = classify(new)
        val recent = lastSettledAtMs?.let { changeStartedAtMs - it <= config.recentChangeWindowMs } ?: false
        val userCaused = if (new.wifiRadioEnabled == false || new.airplaneMode == true) true else null
        return types.distinct().map { type ->
            DetectedEvent(
                type = type,
                state = state,
                occurredAtMs = changeStartedAtMs,
                snapshot = new,
                lastWifiRssiDbm = lastWifiRssi.takeIf { newWifi == true || type == EventType.WIFI_LOST },
                lastWifiFrequencyMhz = lastWifiFrequency.takeIf { newWifi == true || type == EventType.WIFI_LOST },
                networkChangedRecently = recent,
                userInitiated = if (type == EventType.WIFI_LOST) userCaused else null,
            )
        }
    }

    /** At most one per [Config.rssiMinIntervalMs], and only for a change of [Config.rssiMinDeltaDb] or more. */
    private fun signalStrengthEvent(snapshot: NetworkSnapshot, nowMs: Long): DetectedEvent? {
        if (snapshot.wifiAssociated != true) return null
        val rssi = WifiBand.validRssi(snapshot.wifiRssiDbm) ?: return null
        val baseline = rssiBaseline
        if (baseline == null) {
            rssiBaseline = rssi
            return null
        }
        if (abs(rssi - baseline) < config.rssiMinDeltaDb) return null
        val last = lastRssiEventAtMs
        if (last != null && nowMs - last < config.rssiMinIntervalMs) return null
        rssiBaseline = rssi
        lastRssiEventAtMs = nowMs
        return DetectedEvent(
            type = EventType.SIGNAL_STRENGTH_CHANGED,
            state = classify(snapshot),
            occurredAtMs = nowMs,
            snapshot = snapshot,
            lastWifiRssiDbm = rssi,
            lastWifiFrequencyMhz = lastWifiFrequency,
            networkChangedRecently = lastSettledAtMs?.let { nowMs - it <= config.recentChangeWindowMs } ?: false,
            userInitiated = null,
        )
    }

    private fun classify(snapshot: NetworkSnapshot?): ConnectivityState {
        if (snapshot == null) return ConnectivityState.UNKNOWN
        return when (snapshot.wifiAssociated) {
            null -> ConnectivityState.UNKNOWN
            true -> when (snapshot.wifiInternet) {
                true -> ConnectivityState.HEALTHY
                false -> ConnectivityState.WIFI_CONNECTED_NO_INTERNET
                null -> ConnectivityState.UNKNOWN
            }
            false -> {
                val cellular = snapshot.cellularAvailable
                val other = snapshot.otherPathAvailable
                when {
                    // Cellular-only observations: this session never had Wi-Fi.
                    !hadWifi && cellular == true -> ConnectivityState.CELLULAR_AVAILABLE
                    !hadWifi && cellular == false && other -> ConnectivityState.CELLULAR_UNAVAILABLE
                    cellular == true || other -> ConnectivityState.WIFI_DISCONNECTED
                    cellular == false -> ConnectivityState.INTERNET_UNAVAILABLE
                    else -> ConnectivityState.UNKNOWN
                }
            }
        }
    }

    private data class Material(
        val wifi: Boolean?,
        val wifiInternet: Boolean?,
        val cellular: Boolean?,
        val other: Boolean,
        val default: Transport?,
        val home: Boolean?,
    )

    private fun material(s: NetworkSnapshot) = Material(
        wifi = s.wifiAssociated,
        wifiInternet = s.wifiInternet,
        cellular = s.cellularAvailable,
        other = s.otherPathAvailable,
        default = realTransport(s.defaultTransport),
        home = if (s.wifiAssociated == true) s.homeNetworkMatch else null,
    )

    private fun realTransport(t: Transport?): Transport? =
        t?.takeIf { it != Transport.NONE && it != Transport.UNKNOWN }
}
