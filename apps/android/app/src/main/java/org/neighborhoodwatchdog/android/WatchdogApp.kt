package org.neighborhoodwatchdog.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.neighborhoodwatchdog.android.data.update.AppCompatibilityChecker
import org.neighborhoodwatchdog.android.monitoring.MonitoringController
import org.neighborhoodwatchdog.android.push.NotificationChannels
import org.neighborhoodwatchdog.android.push.PushManager
import javax.inject.Inject

/**
 * The monitor lives in the application process, not in an activity: whenever Android starts the
 * process (app opened, WorkManager heartbeat, boot, push message) the callbacks are registered again.
 */
@HiltAndroidApp
class WatchdogApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var controller: MonitoringController
    @Inject lateinit var pushManager: PushManager
    @Inject lateinit var compatibility: AppCompatibilityChecker

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        // Skipped when the build has no Firebase settings; the UI then says push is not configured.
        pushManager.initialise()
        controller.ensureRunning()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // App opened or back in the foreground (at most once an hour). A failure changes nothing.
                compatibility.checkSoon()
                controller.onAppForeground()
            }
        })
    }
}
