package org.neighborhoodwatchdog.android.ui.transparency

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.neighborhoodwatchdog.android.R
import org.neighborhoodwatchdog.android.config.AppConfig
import org.neighborhoodwatchdog.android.data.local.EventDao
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.data.local.QueuedEventEntity
import org.neighborhoodwatchdog.android.data.local.SentEventEntity
import org.neighborhoodwatchdog.android.monitoring.MonitorStatus
import org.neighborhoodwatchdog.android.monitoring.MonitoringController
import org.neighborhoodwatchdog.android.ui.common.BulletList
import org.neighborhoodwatchdog.android.ui.common.InfoRow
import org.neighborhoodwatchdog.android.ui.common.ScreenScaffold
import org.neighborhoodwatchdog.android.ui.common.SectionCard
import org.neighborhoodwatchdog.android.ui.common.formatTime
import org.neighborhoodwatchdog.android.ui.common.stateLabel
import javax.inject.Inject

/** A permission the installed app declares, whether it is granted now, and why it is there. */
data class DeclaredPermission(val name: String, val granted: Boolean, @StringRes val reason: Int)

@HiltViewModel
class TransparencyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    dao: EventDao,
    controller: MonitoringController,
    localState: LocalState,
    val config: AppConfig,
) : ViewModel() {
    val queued: StateFlow<List<QueuedEventEntity>> =
        dao.observeQueued(QUEUE_VIEW_LIMIT).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val queuedCount: StateFlow<Int> = dao.observeQueuedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val sent: StateFlow<List<SentEventEntity>> =
        dao.observeSent(50).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val monitor: StateFlow<MonitorStatus> = controller.status
    val lastReportAtMs: StateFlow<Long?> = localState.lastReportAtMs

    /** Read from the installed package, so the list is what the OS really granted, libraries included. */
    fun declaredPermissions(): List<DeclaredPermission> {
        val info: PackageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        val names = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags
        return names.mapIndexed { index, name ->
            val granted = flags != null && index < flags.size && flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            DeclaredPermission(name.removePrefix("android.permission."), granted, reasonFor(name))
        }
    }

    private fun reasonFor(permission: String): Int = when (permission) {
        "android.permission.INTERNET" -> R.string.permission_internet
        "android.permission.ACCESS_NETWORK_STATE" -> R.string.permission_network_state
        "android.permission.ACCESS_WIFI_STATE" -> R.string.permission_wifi_state
        "android.permission.CHANGE_NETWORK_STATE" -> R.string.permission_change_network
        "android.permission.POST_NOTIFICATIONS" -> R.string.permission_notifications
        "android.permission.RECEIVE_BOOT_COMPLETED" -> R.string.permission_boot
        "android.permission.WAKE_LOCK" -> R.string.permission_wake_lock
        "android.permission.FOREGROUND_SERVICE" -> R.string.permission_foreground_service
        "com.google.android.c2dm.permission.RECEIVE" -> R.string.permission_push_receive
        else -> R.string.permission_library
    }

    companion object {
        const val QUEUE_VIEW_LIMIT = 200
        private val pretty = Json { prettyPrint = true }

        fun prettyJson(raw: String): String = try {
            pretty.encodeToString(JsonElement.serializer(), pretty.parseToJsonElement(raw))
        } catch (_: kotlinx.serialization.SerializationException) {
            raw
        }
    }
}

@Composable
fun TransparencyScreen(onBack: () -> Unit, viewModel: TransparencyViewModel = hiltViewModel()) {
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val queuedCount by viewModel.queuedCount.collectAsStateWithLifecycle()
    val sent by viewModel.sent.collectAsStateWithLifecycle()
    val monitor by viewModel.monitor.collectAsStateWithLifecycle()
    val lastReport by viewModel.lastReportAtMs.collectAsStateWithLifecycle()
    val permissions = remember { viewModel.declaredPermissions() }
    val config = viewModel.config
    val notConfigured = stringResource(R.string.value_not_configured)

    ScreenScaffold(title = stringResource(R.string.transparency_title), onBack = onBack) {
        Text(stringResource(R.string.transparency_intro))

        SectionCard(stringResource(R.string.transparency_source_title)) {
            LinkRow(stringResource(R.string.transparency_source_repo), config.sourceRepoUrl, notConfigured)
            InfoRow(stringResource(R.string.settings_app_version), config.versionName)
            InfoRow(stringResource(R.string.transparency_build_number), config.versionCode.toString())
            LinkRow(stringResource(R.string.transparency_privacy_policy), config.privacyUrl, notConfigured)
        }

        SectionCard(stringResource(R.string.transparency_data_title)) {
            Text(stringResource(R.string.transparency_data_sent_heading), style = MaterialTheme.typography.titleSmall)
            BulletList(
                listOf(
                    stringResource(R.string.transparency_data_1),
                    stringResource(R.string.transparency_data_2),
                    stringResource(R.string.transparency_data_3),
                    stringResource(R.string.transparency_data_4),
                    stringResource(R.string.transparency_data_5),
                    stringResource(R.string.transparency_data_6),
                ),
            )
            Text(stringResource(R.string.transparency_data_not_heading), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.edu_not_collected_body))
            InfoRow(stringResource(R.string.transparency_api_host), config.apiBaseUrl)
            InfoRow(stringResource(R.string.transparency_probe_host), config.internetProbeUrl.ifBlank { notConfigured })
        }

        SectionCard(stringResource(R.string.transparency_permissions_title)) {
            Text(stringResource(R.string.transparency_no_location))
            permissions.forEach { permission ->
                InfoRow(permission.name, stringResource(if (permission.granted) R.string.value_granted else R.string.value_not_granted))
                Text(stringResource(permission.reason), style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard(stringResource(R.string.transparency_monitoring_title)) {
            InfoRow(stringResource(R.string.transparency_monitoring_state), stringResource(stateLabel(monitor.state)))
            InfoRow(
                stringResource(R.string.main_monitoring_running),
                stringResource(if (monitor.running) R.string.value_running else R.string.value_not_running),
            )
            InfoRow(stringResource(R.string.main_last_report), formatTime(lastReport) ?: stringResource(R.string.value_none_yet))
            InfoRow(
                stringResource(R.string.transparency_push),
                stringResource(if (config.pushConfigured) R.string.value_configured else R.string.push_not_configured),
            )
            Text(stringResource(R.string.transparency_limits_heading), style = MaterialTheme.typography.titleSmall)
            BulletList(
                listOf(
                    stringResource(R.string.transparency_limit_1),
                    stringResource(R.string.transparency_limit_2),
                    stringResource(R.string.transparency_limit_3),
                    stringResource(R.string.transparency_limit_4),
                    stringResource(R.string.transparency_limit_5),
                ),
            )
        }

        SectionCard(stringResource(R.string.transparency_security_title)) {
            LinkRow(
                stringResource(R.string.transparency_security_review),
                config.securityReviewUrl,
                stringResource(R.string.transparency_security_review_none),
            )
            InfoRow(stringResource(R.string.transparency_security_contact), config.securityContact.ifBlank { notConfigured })
            Text(stringResource(R.string.transparency_security_note), style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(stringResource(R.string.transparency_queue_title)) {
            Text(pluralStringResource(R.plurals.transparency_queue_count, queuedCount, queuedCount))
            Text(stringResource(R.string.transparency_queue_note), style = MaterialTheme.typography.bodySmall)
            if (queuedCount > queued.size) {
                Text(stringResource(R.string.transparency_queue_truncated, queued.size), style = MaterialTheme.typography.bodySmall)
            }
            queued.forEach { event ->
                EventEntry(
                    title = event.eventType,
                    subtitle = stringResource(
                        R.string.transparency_queued_line,
                        formatTime(event.occurredAtMs).orEmpty(),
                        event.attempts,
                    ),
                    json = event.json,
                )
            }
        }

        SectionCard(stringResource(R.string.transparency_sent_title)) {
            if (sent.isEmpty()) Text(stringResource(R.string.transparency_sent_none))
            sent.forEach { event ->
                EventEntry(
                    title = event.eventType,
                    subtitle = stringResource(
                        R.string.transparency_sent_line,
                        formatTime(event.occurredAtMs).orEmpty(),
                        formatTime(event.sentAtMs).orEmpty(),
                        event.reportedVia ?: stringResource(R.string.value_not_measured),
                        event.result + (event.reason?.let { " ($it)" } ?: ""),
                    ),
                    json = event.json,
                )
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String, missing: String) {
    val uriHandler = LocalUriHandler.current
    if (url.isBlank()) {
        InfoRow(label, missing)
    } else {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        TextButton(
            onClick = {
                try {
                    uriHandler.openUri(url)
                } catch (_: Exception) {
                    // No app can open the link; the address stays visible on the button.
                }
            },
        ) { Text(url) }
    }
}

/** One event: tap to see the exact JSON the app sends for it. */
@Composable
private fun EventEntry(title: String, subtitle: String, json: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            Text(TransparencyViewModel.prettyJson(json), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        } else {
            Text(stringResource(R.string.transparency_show_json), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
