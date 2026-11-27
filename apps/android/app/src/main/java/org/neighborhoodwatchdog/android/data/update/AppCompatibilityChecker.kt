package org.neighborhoodwatchdog.android.data.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.WatchdogApi
import org.neighborhoodwatchdog.android.data.api.apiCall
import org.neighborhoodwatchdog.android.domain.model.Clock
import org.neighborhoodwatchdog.android.domain.update.AppUpdateGate
import org.neighborhoodwatchdog.android.domain.update.AppVersionHeader

/**
 * Asks `GET /api/v1/app/compatibility` at start-up and when the app comes to the foreground, at
 * most once an hour (once a minute while an update is required, so a lowered minimum is noticed).
 * A failed check never changes anything and never blocks the app.
 */
class AppCompatibilityChecker(
    private val api: WatchdogApi,
    private val gate: AppUpdateGate,
    versionName: String,
    private val clock: Clock,
) {
    private val version = AppVersionHeader.semanticVersion(versionName)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastCheckMs: Long? = null

    /** Fire and forget, for lifecycle callbacks. */
    fun checkSoon(force: Boolean = false) {
        scope.launch { check(force) }
    }

    /** Returns true when the server answered and the gate was updated. */
    suspend fun check(force: Boolean = false): Boolean = mutex.withLock {
        val v = version ?: return@withLock false
        val now = clock.nowMs()
        val interval = if (gate.isRequired) REQUIRED_INTERVAL_MS else INTERVAL_MS
        val last = lastCheckMs
        if (!force && last != null && now - last in 0 until interval) return@withLock false
        lastCheckMs = now
        when (val result = apiCall { api.appCompatibility(PLATFORM, v) }) {
            is ApiResult.Success -> {
                val answer = result.value
                gate.applyCompatibility(answer.supported, answer.updateAvailable, answer.latestVersion, answer.updateUrl)
                true
            }
            is ApiResult.Failure -> false
        }
    }

    private companion object {
        const val PLATFORM = "android"
        const val INTERVAL_MS = 60 * 60_000L
        const val REQUIRED_INTERVAL_MS = 60_000L
    }
}
