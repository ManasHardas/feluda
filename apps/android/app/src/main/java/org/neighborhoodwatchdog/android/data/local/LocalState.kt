package org.neighborhoodwatchdog.android.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.neighborhoodwatchdog.android.data.auth.EncryptedFileStore
import org.neighborhoodwatchdog.android.domain.home.StoredFingerprint
import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import org.neighborhoodwatchdog.android.domain.monitoring.AliveMark
import org.neighborhoodwatchdog.android.domain.reporting.SequenceSource
import java.util.UUID

/**
 * Small local state in app-private SharedPreferences. Nothing here is a credential: the device
 * secret and the home-network fingerprint are kept in [EncryptedFileStore].
 */
class LocalState(context: Context) : SequenceSource {
    private val prefs: SharedPreferences = context.getSharedPreferences("watchdog_state", Context.MODE_PRIVATE)

    private val _lastReportAtMs = MutableStateFlow(prefs.getLong(K_LAST_REPORT, 0).takeIf { it > 0 })
    val lastReportAtMs: StateFlow<Long?> = _lastReportAtMs

    private val _monitoringEnabled = MutableStateFlow(prefs.getBoolean(K_MONITORING, true))
    val monitoringEnabledFlow: StateFlow<Boolean> = _monitoringEnabled

    var consentGiven: Boolean
        get() = prefs.getBoolean(K_CONSENT, false)
        set(value) = prefs.edit().putBoolean(K_CONSENT, value).apply()

    var monitoringEnabled: Boolean
        get() = _monitoringEnabled.value
        set(value) {
            prefs.edit().putBoolean(K_MONITORING, value).apply()
            _monitoringEnabled.value = value
        }

    /** The server stopped accepting this phone's secret; the join screen says so once. */
    var enrolmentEndedNotice: Boolean
        get() = prefs.getBoolean(K_ENROLMENT_ENDED, false)
        set(value) = prefs.edit().putBoolean(K_ENROLMENT_ENDED, value).apply()

    /** OS and app version last sent to the server, so the device record is updated only after a change. */
    var reportedDeviceVersion: String?
        get() = prefs.getString(K_REPORTED_VERSION, null)
        set(value) = prefs.edit().putString(K_REPORTED_VERSION, value).apply()

    /** The notification step of onboarding was shown (whatever the resident answered). */
    var notificationStepDone: Boolean
        get() = prefs.getBoolean(K_NOTIFICATION_STEP, false)
        set(value) = prefs.edit().putBoolean(K_NOTIFICATION_STEP, value).apply()

    /** The home-network step was confirmed or skipped. */
    var homeStepDone: Boolean
        get() = prefs.getBoolean(K_HOME_STEP, false)
        set(value) = prefs.edit().putBoolean(K_HOME_STEP, value).apply()

    /** MONITORING_STARTED was emitted once for this installation. */
    var monitoringEverStarted: Boolean
        get() = prefs.getBoolean(K_EVER_STARTED, false)
        set(value) = prefs.edit().putBoolean(K_EVER_STARTED, value).apply()

    var registeredPushToken: String?
        get() = prefs.getString(K_PUSH_TOKEN, null)
        set(value) = prefs.edit().putString(K_PUSH_TOKEN, value).apply()

    var lastNotificationPermission: Boolean?
        get() = if (prefs.contains(K_NOTIFICATION_PERMISSION)) prefs.getBoolean(K_NOTIFICATION_PERMISSION, false) else null
        set(value) = prefs.edit().apply {
            if (value == null) remove(K_NOTIFICATION_PERMISSION) else putBoolean(K_NOTIFICATION_PERMISSION, value)
        }.apply()

    var rebootSeen: Boolean
        get() = prefs.getBoolean(K_REBOOT, false)
        set(value) {
            prefs.edit().putBoolean(K_REBOOT, value).commit()
        }

    var lastState: ConnectivityState?
        get() = prefs.getString(K_LAST_STATE, null)?.let { name -> ConnectivityState.entries.firstOrNull { it.name == name } }
        set(value) = prefs.edit().putString(K_LAST_STATE, value?.name).apply()

    var hadWifi: Boolean
        get() = prefs.getBoolean(K_HAD_WIFI, false)
        set(value) = prefs.edit().putBoolean(K_HAD_WIFI, value).apply()

    var lastAlive: AliveMark?
        get() {
            val wall = prefs.getLong(K_ALIVE_WALL, 0)
            if (wall <= 0) return null
            val boot = prefs.getInt(K_ALIVE_BOOT, -1).takeIf { it >= 0 }
            return AliveMark(wall, prefs.getLong(K_ALIVE_ELAPSED, 0), boot)
        }
        set(value) = prefs.edit().apply {
            if (value == null) {
                remove(K_ALIVE_WALL).remove(K_ALIVE_ELAPSED).remove(K_ALIVE_BOOT)
            } else {
                putLong(K_ALIVE_WALL, value.wallMs).putLong(K_ALIVE_ELAPSED, value.elapsedMs).putInt(K_ALIVE_BOOT, value.bootCount ?: -1)
            }
        }.apply()

    fun recordReport(sentAtMs: Long) {
        prefs.edit().putLong(K_LAST_REPORT, sentAtMs).apply()
        _lastReportAtMs.value = sentAtMs
    }

    /** Random UUID generated once per installation. Never a hardware identifier. */
    @Synchronized
    fun installId(): String {
        prefs.getString(K_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(K_INSTALL_ID, id).commit()
        return id
    }

    /** Per-device counter, increasing, persisted before it is used. */
    @Synchronized
    override fun next(): Long {
        val value = prefs.getLong(K_SEQUENCE, 0) + 1
        prefs.edit().putLong(K_SEQUENCE, value).commit()
        return value
    }

    /**
     * The server no longer accepts this phone: forget everything except that the introduction was
     * read. The install id goes too, because the server refuses to enrol a removed device id again.
     */
    fun clearAfterEnrolmentEnded() {
        val consent = consentGiven
        prefs.edit().clear().putBoolean(K_CONSENT, consent).putBoolean(K_ENROLMENT_ENDED, true).commit()
        _lastReportAtMs.value = null
        _monitoringEnabled.value = true
    }

    /** Leave: forget everything, including the install id. */
    fun clearAll() {
        prefs.edit().clear().commit()
        _lastReportAtMs.value = null
        _monitoringEnabled.value = true
    }

    private companion object {
        const val K_CONSENT = "consent_given"
        const val K_MONITORING = "monitoring_enabled"
        const val K_ENROLMENT_ENDED = "enrolment_ended_notice"
        const val K_REPORTED_VERSION = "reported_device_version"
        const val K_NOTIFICATION_STEP = "notification_step_done"
        const val K_HOME_STEP = "home_step_done"
        const val K_EVER_STARTED = "monitoring_ever_started"
        const val K_PUSH_TOKEN = "registered_push_token"
        const val K_NOTIFICATION_PERMISSION = "notification_permission"
        const val K_REBOOT = "reboot_seen"
        const val K_LAST_STATE = "last_state"
        const val K_HAD_WIFI = "had_wifi"
        const val K_ALIVE_WALL = "alive_wall_ms"
        const val K_ALIVE_ELAPSED = "alive_elapsed_ms"
        const val K_ALIVE_BOOT = "alive_boot_count"
        const val K_LAST_REPORT = "last_report_ms"
        const val K_INSTALL_ID = "install_id"
        const val K_SEQUENCE = "event_sequence"
    }
}

/** The home-network fingerprint and its salt, encrypted at rest. They never leave the device. */
class FingerprintStore(private val files: EncryptedFileStore) {
    @Volatile private var cached: StoredFingerprint? = null
    @Volatile private var loaded = false

    @Synchronized
    fun load(): StoredFingerprint? {
        if (!loaded) {
            cached = files.read(FILE)?.let { bytes ->
                runCatching { WatchdogJson.decodeFromString(StoredFingerprint.serializer(), bytes.decodeToString()) }.getOrNull()
            }
            loaded = true
        }
        return cached
    }

    @Synchronized
    fun save(fingerprint: StoredFingerprint) {
        files.write(FILE, WatchdogJson.encodeToString(StoredFingerprint.serializer(), fingerprint).encodeToByteArray())
        cached = fingerprint
        loaded = true
    }

    @Synchronized
    fun clear() {
        files.delete(FILE)
        cached = null
        loaded = true
    }

    private companion object {
        const val FILE = "home_network.bin"
    }
}
