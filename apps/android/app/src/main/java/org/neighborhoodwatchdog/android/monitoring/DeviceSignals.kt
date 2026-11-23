package org.neighborhoodwatchdog.android.monitoring

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.neighborhoodwatchdog.android.domain.model.Clock
import org.neighborhoodwatchdog.android.domain.monitoring.AliveMark
import org.neighborhoodwatchdog.android.domain.monitoring.PowerObservation

/** What the OS currently allows this app to do. Read fresh each time; nothing is cached. */
class PermissionStatus(private val context: Context) {
    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val networkStateGranted: Boolean get() = granted(Manifest.permission.ACCESS_NETWORK_STATE)
    val wifiStateGranted: Boolean get() = granted(Manifest.permission.ACCESS_WIFI_STATE)
    val changeNetworkStateGranted: Boolean get() = granted(Manifest.permission.CHANGE_NETWORK_STATE)

    /** The permissions monitoring cannot work without. */
    val monitoringPermissionsGranted: Boolean get() = networkStateGranted && wifiStateGranted

    val notificationsGranted: Boolean
        get() {
            val runtime = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
            return runtime && NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** The resident (or the OS) restricted background work for this app. Null below Android 9. */
    val backgroundRestricted: Boolean?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
        } else {
            null
        }

    val batterySaverOn: Boolean
        get() = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
}

/** Remembers when the charger was unplugged, while the process is alive. */
class PowerTracker(private val context: Context, private val clock: Clock) {
    @Volatile private var lastUnpluggedAtMs: Long? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_DISCONNECTED) lastUnpluggedAtMs = clock.nowMs()
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        registered = false
    }

    fun observation(): PowerObservation {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return PowerObservation(chargingNow = battery?.isCharging, lastUnpluggedAtMs = lastUnpluggedAtMs)
    }
}

class AliveClock(private val context: Context, private val clock: Clock) {
    fun mark(): AliveMark = AliveMark(
        wallMs = clock.nowMs(),
        elapsedMs = SystemClock.elapsedRealtime(),
        bootCount = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (_: Settings.SettingNotFoundException) {
            null
        },
    )
}
