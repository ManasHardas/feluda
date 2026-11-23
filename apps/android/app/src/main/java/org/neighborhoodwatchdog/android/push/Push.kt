package org.neighborhoodwatchdog.android.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.neighborhoodwatchdog.android.MainActivity
import org.neighborhoodwatchdog.android.R
import org.neighborhoodwatchdog.android.config.AppConfig
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.data.repository.DeviceRepository
import org.neighborhoodwatchdog.android.domain.auth.DeviceCredentials
import org.neighborhoodwatchdog.android.monitoring.PermissionStatus
import javax.inject.Inject
import kotlin.coroutines.resume

object NotificationChannels {
    const val HOUSEHOLD = "household_warnings"
    const val NEIGHBORHOOD = "neighborhood_alerts"
    const val UPLOAD = "background_upload"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(HOUSEHOLD, context.getString(R.string.channel_household), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_household_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(NEIGHBORHOOD, context.getString(R.string.channel_neighborhood), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_neighborhood_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(UPLOAD, context.getString(R.string.channel_upload), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_upload_description) },
        )
    }

    /** Alert level 1 is a household warning; 2 and 3 are neighborhood alerts. */
    fun forAlertLevel(level: Int?): String = if (level != null && level >= 2) NEIGHBORHOOD else HOUSEHOLD
}

sealed interface PushSyncResult {
    /** The build has no Firebase settings. The app says so instead of pretending. */
    data object NotConfigured : PushSyncResult
    data object Registered : PushSyncResult
    data class Failed(val message: String?) : PushSyncResult
}

class PushManager(
    private val context: Context,
    private val config: AppConfig,
    private val devices: DeviceRepository,
    private val localState: LocalState,
    private val credentials: DeviceCredentials,
) {
    val configured: Boolean get() = config.pushConfigured

    /** Manual Firebase initialisation from build settings. There is no google-services.json. */
    fun initialise(): Boolean {
        if (!configured) return false
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        return try {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId(config.firebaseProjectId)
                    .setApplicationId(config.firebaseApplicationId)
                    .setApiKey(config.firebaseApiKey)
                    .setGcmSenderId(config.firebaseSenderId)
                    .build(),
            )
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Gets the current FCM token and registers it with the backend if it is new. */
    suspend fun syncToken(): PushSyncResult {
        if (!initialise()) return if (configured) PushSyncResult.Failed("Firebase could not be initialised") else PushSyncResult.NotConfigured
        val token = try {
            currentToken()
        } catch (e: Exception) {
            return PushSyncResult.Failed(e.message)
        } ?: return PushSyncResult.Failed("No push token was issued")
        return register(token)
    }

    suspend fun register(token: String): PushSyncResult {
        if (!credentials.isEnrolled) return PushSyncResult.Failed("This phone is not enrolled yet")
        if (token == localState.registeredPushToken) return PushSyncResult.Registered
        return when (val result = devices.registerPushToken(token)) {
            is ApiResult.Success -> PushSyncResult.Registered
            is ApiResult.Failure -> PushSyncResult.Failed(result.error.message)
        }
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }
}

@AndroidEntryPoint
class WatchdogMessagingService : FirebaseMessagingService() {
    @Inject lateinit var pushManager: PushManager
    @Inject lateinit var permissions: PermissionStatus

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { pushManager.register(token) }
    }

    /** Title and body come from the server. The app adds nothing and claims nothing of its own. */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = message.notification?.title ?: data["title"]
        val body = message.notification?.body ?: data["body"]
        if (title.isNullOrBlank() && body.isNullOrBlank()) return
        if (!permissions.notificationsGranted) return
        val incidentId = data[KEY_INCIDENT_ID]
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (incidentId != null) putExtra(KEY_INCIDENT_ID, incidentId)
        }
        val requestCode = (incidentId ?: message.messageId ?: "").hashCode()
        val pending = PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NotificationChannels.forAlertLevel(data[KEY_ALERT_LEVEL]?.toIntOrNull()))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(requestCode, notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the call.
        }
    }

    companion object {
        const val KEY_INCIDENT_ID = "incident_id"
        const val KEY_ALERT_LEVEL = "alert_level"
    }
}
