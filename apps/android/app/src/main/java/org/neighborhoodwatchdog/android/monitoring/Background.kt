package org.neighborhoodwatchdog.android.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.neighborhoodwatchdog.android.R
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.domain.reporting.FlushResult
import org.neighborhoodwatchdog.android.push.NotificationChannels
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Schedules the two background jobs. There is no permanent foreground service (see README). */
class WorkScheduler(private val context: Context) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Every 15 minutes (the shortest period WorkManager allows). Survives reboots. */
    fun scheduleHeartbeat() {
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(HEARTBEAT, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Prompt upload once any network is connected. Expedited where the OS quota allows it. */
    fun enqueueUpload() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(UPLOAD, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelHeartbeat() {
        workManager.cancelUniqueWork(HEARTBEAT)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(HEARTBEAT)
        workManager.cancelUniqueWork(UPLOAD)
    }

    private companion object {
        const val HEARTBEAT = "watchdog_heartbeat"
        const val UPLOAD = "watchdog_upload"
    }
}

@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val controller: MonitoringController,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        controller.heartbeat()
        return Result.success()
    }
}

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val controller: MonitoringController,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (controller.flushNow()) {
        FlushResult.Idle -> Result.success()
        is FlushResult.RetryIn -> Result.retry()
        // The server refuses this device or session; the next heartbeat tries again.
        is FlushResult.Blocked -> Result.success()
        // This app version is refused: nothing is retried until a compatibility check accepts it.
        FlushResult.Paused -> Result.success()
    }

    /** Only used below Android 12, where expedited work runs as a short foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        UPLOAD_NOTIFICATION_ID,
        NotificationCompat.Builder(context, NotificationChannels.UPLOAD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.upload_notification_title))
            .setOngoing(true)
            .build(),
    )

    private companion object {
        const val UPLOAD_NOTIFICATION_ID = 4101
    }
}

/**
 * After a reboot: remember that it happened (so the gap is reported with reason `device_reboot`),
 * reschedule the heartbeat and start the monitor again.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: MonitoringController
    @Inject lateinit var localState: LocalState

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        localState.rebootSeen = true
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                controller.startAndAwaitBaseline(BOOT_WAIT_MS)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val BOOT_WAIT_MS = 8_000L
    }
}
