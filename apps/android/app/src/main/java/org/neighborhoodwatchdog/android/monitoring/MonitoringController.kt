package org.neighborhoodwatchdog.android.monitoring

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.neighborhoodwatchdog.android.data.local.FingerprintStore
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.data.repository.DeviceRepository
import org.neighborhoodwatchdog.android.domain.auth.DeviceCredentials
import org.neighborhoodwatchdog.android.domain.model.Clock
import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.Diagnostics
import org.neighborhoodwatchdog.android.domain.model.EventType
import org.neighborhoodwatchdog.android.domain.model.ObservationConfidence
import org.neighborhoodwatchdog.android.domain.monitoring.ConnectivityStateMachine
import org.neighborhoodwatchdog.android.domain.monitoring.DiagnosticsBuilder
import org.neighborhoodwatchdog.android.domain.monitoring.GapDetector
import org.neighborhoodwatchdog.android.domain.monitoring.MachineOutput
import org.neighborhoodwatchdog.android.domain.monitoring.MonitoringGap
import org.neighborhoodwatchdog.android.domain.monitoring.NetworkSnapshot
import org.neighborhoodwatchdog.android.domain.monitoring.PowerEvidence
import org.neighborhoodwatchdog.android.domain.monitoring.Reconciler
import org.neighborhoodwatchdog.android.domain.reporting.EventFactory
import org.neighborhoodwatchdog.android.domain.reporting.EventQueueStore
import org.neighborhoodwatchdog.android.domain.reporting.EventUploader
import org.neighborhoodwatchdog.android.domain.reporting.FlushResult
import org.neighborhoodwatchdog.android.domain.reporting.SentEventLog
import org.neighborhoodwatchdog.android.domain.update.AppUpdateGate

/** What the UI shows about this phone's own monitoring. Local facts only. */
data class MonitorStatus(
    /** The network callbacks are registered and the state machine is running. */
    val running: Boolean = false,
    val state: ConnectivityState = ConnectivityState.UNKNOWN,
    val snapshot: NetworkSnapshot? = null,
    val lastEventAtMs: Long? = null,
    /** Set when the server refuses this device's reports (for example `device_revoked`). */
    val uploadBlockedCode: String? = null,
)

enum class ClearMode {
    /** The server no longer accepts this phone: keep only that the introduction was read. */
    ENROLMENT_ENDED,

    /** Leave neighborhood: forget everything. */
    FORGET_EVERYTHING,
}

/**
 * Owns the monitor's lifecycle: start, gap detection, reconciliation, heartbeat, user pause and
 * shutdown. It lives in the application process, separate from any screen.
 *
 * Every state change goes through one queue of actions that are executed one after another on the
 * monitoring thread, so the state machine and the bookkeeping never run concurrently.
 */
class MonitoringController(
    private val monitor: ConnectivityMonitor,
    monitorDispatcher: CoroutineDispatcher,
    private val probes: ProbeRunner,
    private val uploader: EventUploader,
    private val eventFactory: EventFactory,
    private val queueStore: EventQueueStore,
    private val sentLog: SentEventLog,
    private val fingerprintStore: FingerprintStore,
    private val localState: LocalState,
    private val credentials: DeviceCredentials,
    private val powerTracker: PowerTracker,
    private val aliveClock: AliveClock,
    private val permissions: PermissionStatus,
    private val scheduler: WorkScheduler,
    private val devices: DeviceRepository,
    private val clock: Clock,
    private val updateGate: AppUpdateGate,
) {
    private val scope = CoroutineScope(SupervisorJob() + monitorDispatcher)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val uploadKick = Channel<Unit>(Channel.CONFLATED)

    private val _status = MutableStateFlow(MonitorStatus())
    val status: StateFlow<MonitorStatus> = _status

    private class PendingStart(
        val gap: MonitoringGap?,
        val stateBefore: ConnectivityState?,
        val newProcess: Boolean,
        val userInitiated: Boolean,
    )

    // Everything below is touched only from actions (monitoring thread).
    private var machine: ConnectivityStateMachine? = null
    private var running = false
    private var pendingStart: PendingStart? = null
    private var baselineSignal: CompletableDeferred<Unit>? = null
    private var tickJob: Job? = null
    private var aliveJob: Job? = null
    private var periodicProbeJob: Job? = null
    private var probeRunning = false
    private var probeAgain = false
    private var probeDelayJob: Job? = null
    private var lastProbeStartedElapsedMs = 0L
    private var failedProbeStreak = 0
    private val probeWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private var aliveMarkedInThisProcess = false
    private var lastAliveWriteElapsedMs = 0L
    private var reconciledUntilMs = 0L
    private var lastEventAtMs: Long? = null
    private var uploadBlockedCode: String? = null

    init {
        scope.launch {
            for (action in actions) {
                try {
                    action()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One failed action (for example a storage error) must not stop the monitor.
                    android.util.Log.w(TAG, "Monitoring action failed", e)
                }
            }
        }
        ioScope.launch { uploadLoop() }
        ioScope.launch { followUpdateGate() }
    }

    /**
     * While the server refuses this app version nothing runs in the background: the monitor stops
     * and the WorkManager jobs are cancelled, so nothing retries. Queued events are kept. When a
     * compatibility check accepts the version again, monitoring starts and the queue is sent; the
     * time in between is reported as a monitoring gap, as for any other stop.
     */
    private suspend fun followUpdateGate() {
        var required = updateGate.isRequired
        updateGate.state.collect { state ->
            if (state.required == required) return@collect
            required = state.required
            if (required) {
                ask { stopMonitor() }
                scheduler.cancelAll()
                post { publish() }
            } else {
                ensureRunning()
                requestUpload()
            }
        }
    }

    private fun post(action: suspend () -> Unit) {
        actions.trySend(action)
    }

    private suspend fun <T> ask(action: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        post {
            try {
                result.complete(action())
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result.await()
    }

    /** Onboarding is complete: introduction read, phone enrolled, home-network step answered. */
    fun eligible(): Boolean = localState.consentGiven && credentials.isEnrolled && localState.homeStepDone

    /** Starts monitoring if it should run and does not run yet. Never starts before consent. */
    fun ensureRunning() = post { startIfNeeded(userInitiated = false) }

    /** Starts if needed and waits until the first settled view of the network (or the timeout). */
    suspend fun startAndAwaitBaseline(timeoutMs: Long) {
        val signal = ask {
            startIfNeeded(userInitiated = false)
            baselineSignal
        } ?: return
        withTimeoutOrNull(timeoutMs) { signal.await() }
    }

    /** The app came to the foreground: check permissions, make sure the monitor runs, retry uploads. */
    fun onAppForeground() {
        post {
            startIfNeeded(userInitiated = false)
            if (running) {
                checkAliveAndMark()
                checkPermissions()
                requestProbe()
            }
            publish()
        }
        if (updateGate.isRequired) return
        ioScope.launch { devices.updateRegistrationIfChanged() }
        requestUpload()
    }

    private suspend fun startIfNeeded(userInitiated: Boolean) {
        if (running) return
        if (!eligible() || !localState.monitoringEnabled || updateGate.isRequired) {
            publish()
            return
        }
        val permissionMissing = !permissions.monitoringPermissionsGranted
        val now = aliveClock.mark()
        val gap = GapDetector.detect(
            last = localState.lastAlive,
            now = now,
            rebootSeen = localState.rebootSeen,
            sameProcess = aliveMarkedInThisProcess,
            permissionMissing = permissionMissing,
            backgroundRestricted = permissions.backgroundRestricted == true,
        )
        pendingStart = PendingStart(gap, localState.lastState, !aliveMarkedInThisProcess, userInitiated)
        localState.rebootSeen = false
        machine = ConnectivityStateMachine(hadWifiBefore = localState.hadWifi).also { it.setPermissionMissing(permissionMissing) }
        running = true
        baselineSignal = CompletableDeferred()
        writeAlive()
        powerTracker.start()
        monitor.start { post { onRawChange() } }
        scheduler.scheduleHeartbeat()
        aliveJob?.cancel()
        aliveJob = scope.launch {
            while (isActive) {
                delay(ALIVE_INTERVAL_MS)
                post { if (running) checkAliveAndMark() }
            }
        }
        onRawChange()
    }

    private fun stopMonitor() {
        monitor.stop()
        powerTracker.stop()
        tickJob?.cancel()
        aliveJob?.cancel()
        periodicProbeJob?.cancel()
        probeDelayJob?.cancel()
        probeDelayJob = null
        failedProbeStreak = 0
        machine = null
        running = false
        pendingStart = null
        baselineSignal?.complete(Unit)
        baselineSignal = null
        probeWaiters.forEach { it.complete(Unit) }
        probeWaiters.clear()
    }

    private suspend fun onRawChange() {
        val m = machine ?: return
        checkAliveAndMark()
        val snapshot = monitor.snapshot()
        handle(m.onSnapshot(snapshot, clock.nowMs()))
        if (snapshot.internetReachable == null) requestProbe()
        // A connectivity change is the trigger to retry queued uploads.
        uploadKick.trySend(Unit)
    }

    private suspend fun handle(output: MachineOutput) {
        val m = machine ?: return
        for (event in output.events) {
            // Right after a suspension the callbacks describe changes that happened at an unknown
            // time inside the gap: they are reported as reconciled, with the time they were seen.
            val confidence = if (event.occurredAtMs <= reconciledUntilMs) ObservationConfidence.RECONCILED else ObservationConfidence.OBSERVED
            record(event.type, event.state, event.occurredAtMs, DiagnosticsBuilder.forEvent(event, powerTracker.observation()), confidence)
        }
        if (output.baselineEstablished) emitStartEvents(output.state)
        if (output.state != ConnectivityState.NETWORK_TRANSITION) localState.lastState = output.state
        if (m.settledSnapshot?.wifiAssociated == true) localState.hadWifi = true
        publish()
        tickJob?.cancel()
        val next = output.nextCheckAtMs ?: return
        tickJob = scope.launch {
            delay((next - clock.nowMs()).coerceAtLeast(0) + 20)
            post { machine?.let { handle(it.onTick(clock.nowMs())) } }
        }
    }

    /** Gap first, then the reconciled change, then the lifecycle event. Nothing is back-dated. */
    private suspend fun emitStartEvents(state: ConnectivityState) {
        val start = pendingStart
        pendingStart = null
        if (start != null) {
            val now = clock.nowMs()
            val base = currentDiagnostics(userInitiated = null)
            start.gap?.let { record(EventType.MONITORING_GAP_DETECTED, state, now, DiagnosticsBuilder.withGap(base, it)) }
            if (start.newProcess || start.gap != null) {
                Reconciler.eventFor(start.stateBefore, state)?.let {
                    record(it, state, now, base, ObservationConfidence.RECONCILED)
                }
            }
            val lifecycle = if (localState.monitoringEverStarted) EventType.MONITORING_RESUMED else EventType.MONITORING_STARTED
            record(lifecycle, state, now, base.copy(userInitiated = if (start.userInitiated) true else null))
            localState.monitoringEverStarted = true
        }
        baselineSignal?.complete(Unit)
    }

    /**
     * Compares "now" with the last persisted alive mark. More than two minutes without the process
     * running any code is a monitoring gap and is reported, whatever the cause.
     */
    private suspend fun checkAliveAndMark() {
        val m = machine ?: return
        val now = aliveClock.mark()
        val last = localState.lastAlive
        if (pendingStart == null && last != null) {
            val gap = GapDetector.detect(
                last = last,
                now = now,
                rebootSeen = false,
                sameProcess = true,
                permissionMissing = !permissions.monitoringPermissionsGranted,
                backgroundRestricted = permissions.backgroundRestricted == true,
            )
            if (gap != null) {
                reconciledUntilMs = now.wallMs + RECONCILE_WINDOW_MS
                record(
                    EventType.MONITORING_GAP_DETECTED,
                    m.currentState(),
                    now.wallMs,
                    DiagnosticsBuilder.withGap(currentDiagnostics(userInitiated = null), gap),
                )
                lastAliveWriteElapsedMs = 0
                requestProbe()
            }
        }
        if (now.elapsedMs - lastAliveWriteElapsedMs >= ALIVE_WRITE_MIN_INTERVAL_MS) writeAlive()
    }

    private fun writeAlive() {
        val now = aliveClock.mark()
        localState.lastAlive = now
        lastAliveWriteElapsedMs = now.elapsedMs
        aliveMarkedInThisProcess = true
    }

    private suspend fun checkPermissions() {
        val m = machine ?: return
        val monitoringGranted = permissions.monitoringPermissionsGranted
        val notifications = permissions.notificationsGranted
        val combined = monitoringGranted && notifications
        val before = localState.lastNotificationPermission
        m.setPermissionMissing(!monitoringGranted)
        if (monitoringGranted && !monitor.isRegistered) monitor.start { post { onRawChange() } }
        if (before != null && before != combined) {
            record(EventType.PERMISSION_CHANGED, m.currentState(), clock.nowMs(), currentDiagnostics(userInitiated = null))
        }
        localState.lastNotificationPermission = combined
    }

    private fun currentDiagnostics(userInitiated: Boolean?): Diagnostics {
        val m = machine
        val snapshot = m?.latestSnapshot
        val associated = snapshot?.wifiAssociated == true
        return DiagnosticsBuilder.build(
            snapshot = snapshot,
            lastWifiRssiDbm = if (associated) snapshot?.wifiRssiDbm else null,
            lastWifiFrequencyMhz = if (associated) snapshot?.wifiFrequencyMhz else null,
            networkChangedRecently = null,
            userInitiated = userInitiated,
            externalPowerLostRecently = PowerEvidence.externalPowerLostRecently(clock.nowMs(), powerTracker.observation()),
        )
    }

    private suspend fun record(
        type: EventType,
        state: ConnectivityState,
        occurredAtMs: Long,
        diagnostics: Diagnostics,
        confidence: ObservationConfidence = ObservationConfidence.OBSERVED,
    ) {
        val event = eventFactory.create(type, state, occurredAtMs, diagnostics, confidence)
        // Durable first: the event is in the database before any network attempt.
        withContext(Dispatchers.IO) { uploader.enqueue(event) }
        lastEventAtMs = occurredAtMs
        requestUpload()
    }

    // ---------------------------------------------------------------- probes

    /**
     * Probes are rate-limited: never more than one at a time and never more often than once every
     * [PROBE_MIN_INTERVAL_MS], however many callbacks arrive. A request inside that interval is
     * delayed, not dropped.
     */
    private fun requestProbe() {
        if (!running) return
        if (probeRunning) {
            probeAgain = true
            return
        }
        val sinceLast = aliveClock.mark().elapsedMs - lastProbeStartedElapsedMs
        if (lastProbeStartedElapsedMs != 0L && sinceLast in 0 until PROBE_MIN_INTERVAL_MS) {
            if (probeDelayJob?.isActive != true) {
                probeDelayJob = scope.launch {
                    delay(PROBE_MIN_INTERVAL_MS - sinceLast)
                    post { requestProbe() }
                }
            }
            return
        }
        probeRunning = true
        lastProbeStartedElapsedMs = aliveClock.mark().elapsedMs
        val target = monitor.probeTarget()
        val gateways = monitor.probeGateways()
        scope.launch {
            val internet = probes.internetReachable(target)
            val gateway = if (gateways.isEmpty()) null else probes.gatewayReachable(target, gateways)
            post {
                probeRunning = false
                failedProbeStreak = if (internet) 0 else failedProbeStreak + 1
                if (running) {
                    monitor.refreshWifiCapabilities()
                    monitor.setProbeResult(target, internet, gateway)
                    // The network changed while the probe ran and the result was discarded: measure again.
                    if (monitor.snapshot().internetReachable == null) probeAgain = true
                }
                probeWaiters.forEach { it.complete(Unit) }
                probeWaiters.clear()
                if (probeAgain) {
                    probeAgain = false
                    requestProbe()
                } else {
                    schedulePeriodicProbe()
                }
            }
        }
    }

    /**
     * While the process is alive: re-check every 5 minutes. While Wi-Fi has no internet the re-check
     * starts at 30 seconds and backs off (30 s, 60 s, 120 s, ... capped at 5 minutes).
     */
    private fun schedulePeriodicProbe() {
        periodicProbeJob?.cancel()
        if (!running) return
        val snapshot = machine?.latestSnapshot
        val waitMs = if (snapshot?.wifiAssociated == true && snapshot.wifiInternet == false) {
            (PROBE_FAST_MS shl (failedProbeStreak - 1).coerceIn(0, 4)).coerceAtMost(PROBE_SLOW_MS)
        } else {
            PROBE_SLOW_MS
        }
        periodicProbeJob = scope.launch {
            delay(waitMs)
            post { requestProbe() }
        }
    }

    // ---------------------------------------------------------------- heartbeat and uploads

    /**
     * Called by the 15-minute WorkManager job: make sure the monitor runs (this is where a killed
     * process or a reboot shows up as a gap), re-register callbacks if they were lost, measure
     * again, record a HEARTBEAT and flush the queue.
     */
    suspend fun heartbeat(): FlushResult {
        if (updateGate.isRequired) return FlushResult.Paused
        startAndAwaitBaseline(BASELINE_TIMEOUT_MS)
        val probeDone = ask {
            if (!running) return@ask null
            checkAliveAndMark()
            checkPermissions()
            CompletableDeferred<Unit>().also {
                probeWaiters += it
                requestProbe()
            }
        }
        if (probeDone != null) withTimeoutOrNull(PROBE_WAIT_MS) { probeDone.await() }
        ask {
            val m = machine
            if (running && m != null) {
                record(EventType.HEARTBEAT, m.currentState(), clock.nowMs(), currentDiagnostics(userInitiated = null))
            }
        }
        return flushNow()
    }

    /** Asks for an upload soon: in this process right away, and through WorkManager as a backup. */
    fun requestUpload() {
        if (updateGate.isRequired) return
        scheduler.enqueueUpload()
        uploadKick.trySend(Unit)
    }

    private suspend fun uploadLoop() {
        for (kick in uploadKick) {
            var result = flushNow()
            while (result !is FlushResult.Idle && result !is FlushResult.Paused) {
                // A callback may shorten the backoff, but failed attempts are never closer than this.
                delay(UPLOAD_MIN_RETRY_MS)
                val waitMs = when (result) {
                    is FlushResult.RetryIn -> result.delayMs
                    is FlushResult.Blocked -> result.delayMs
                    FlushResult.Idle, FlushResult.Paused -> 0L
                }.minus(UPLOAD_MIN_RETRY_MS).coerceAtLeast(0)
                if (result is FlushResult.Blocked || (result is FlushResult.RetryIn && result.cause == "rate_limited")) {
                    // The server asked for this pause or refuses this device: the wait is served in full.
                    delay(waitMs)
                } else {
                    // Wait for the backoff, or less if a connectivity callback or a new event arrives.
                    withTimeoutOrNull(waitMs) { uploadKick.receive() }
                }
                result = flushNow()
            }
        }
    }

    suspend fun flushNow(): FlushResult {
        if (!credentials.isEnrolled) return FlushResult.Idle
        val result = try {
            uploader.flush()
        } catch (e: java.io.IOException) {
            FlushResult.RetryIn(30_000, "storage")
        }
        val blocked = (result as? FlushResult.Blocked)?.errorCode
        post {
            if (uploadBlockedCode != blocked) {
                uploadBlockedCode = blocked
                publish()
            }
        }
        return result
    }

    // ---------------------------------------------------------------- user actions

    /** Off: emit MONITORING_DISABLED_BY_USER and stop the monitor. On: start again. */
    suspend fun setMonitoringEnabled(enabled: Boolean) = ask {
        if (!enabled) {
            if (localState.monitoringEnabled) {
                localState.monitoringEnabled = false
                if (eligible()) {
                    record(
                        EventType.MONITORING_DISABLED_BY_USER,
                        ConnectivityState.MONITORING_PAUSED,
                        clock.nowMs(),
                        currentDiagnostics(userInitiated = true),
                    )
                }
                stopMonitor()
                localState.lastState = ConnectivityState.MONITORING_PAUSED
                scheduler.cancelHeartbeat()
            }
        } else {
            localState.monitoringEnabled = true
            // The paused time is not a gap: the server was told monitoring was switched off.
            localState.lastAlive = null
            aliveMarkedInThisProcess = false
            startIfNeeded(userInitiated = true)
        }
        publish()
    }

    /**
     * The server answered 401 `unauthorized`: the secret is already removed. Stop monitoring and
     * clear what belonged to that enrolment, then tell the caller so the join screen is shown.
     */
    fun onEnrolmentRejected(done: () -> Unit) {
        ioScope.launch {
            try {
                stopAndClear(ClearMode.ENROLMENT_ENDED)
            } finally {
                done()
            }
        }
    }

    /** Leave, or removed by the server: stop monitoring and remove the queue, the log and the fingerprint. */
    suspend fun stopAndClear(mode: ClearMode) {
        ask { stopMonitor() }
        scheduler.cancelAll()
        withContext(Dispatchers.IO) {
            queueStore.clear()
            sentLog.clear()
        }
        fingerprintStore.clear()
        if (mode == ClearMode.ENROLMENT_ENDED) localState.clearAfterEnrolmentEnded() else localState.clearAll()
        ask {
            lastEventAtMs = null
            uploadBlockedCode = null
            publish()
        }
    }

    private fun publish() {
        val m = machine
        val state = when {
            !eligible() -> ConnectivityState.UNKNOWN
            !localState.monitoringEnabled -> ConnectivityState.MONITORING_PAUSED
            m != null -> m.currentState()
            else -> ConnectivityState.UNKNOWN
        }
        _status.value = MonitorStatus(
            running = running && monitor.isRegistered,
            state = state,
            snapshot = m?.latestSnapshot,
            lastEventAtMs = lastEventAtMs,
            uploadBlockedCode = uploadBlockedCode,
        )
    }

    private companion object {
        const val TAG = "WatchdogMonitor"
        const val ALIVE_INTERVAL_MS = 30_000L
        const val ALIVE_WRITE_MIN_INTERVAL_MS = 10_000L
        const val RECONCILE_WINDOW_MS = 10_000L
        const val PROBE_MIN_INTERVAL_MS = 10_000L
        const val UPLOAD_MIN_RETRY_MS = 5_000L
        const val PROBE_FAST_MS = 30_000L
        const val PROBE_SLOW_MS = 5 * 60_000L
        const val BASELINE_TIMEOUT_MS = 30_000L
        const val PROBE_WAIT_MS = 15_000L
    }
}
