package org.neighborhoodwatchdog.android.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.neighborhoodwatchdog.android.R
import org.neighborhoodwatchdog.android.data.api.ApiError
import org.neighborhoodwatchdog.android.domain.model.Classification
import org.neighborhoodwatchdog.android.domain.model.ConnectivityState
import org.neighborhoodwatchdog.android.domain.model.IncidentStatus
import org.neighborhoodwatchdog.android.domain.model.Isp
import org.neighborhoodwatchdog.android.domain.model.MonitoringStatus
import org.neighborhoodwatchdog.android.domain.model.StatusColor
import org.neighborhoodwatchdog.android.ui.theme.statusColorValue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
                actions = actions,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** A status colour with its meaning and the explanation text. The explanation is always shown. */
@Composable
fun StatusCard(color: StatusColor, title: String, explanation: String, footer: @Composable ColumnScope.() -> Unit = {}) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(20.dp).background(statusColorValue(color), CircleShape))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(colorMeaning(color)), style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(explanation, style = MaterialTheme.typography.bodyMedium)
        footer()
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ErrorText(text: String?) {
    if (!text.isNullOrBlank()) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun BulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun VerticalGap(height: Int = 8) = Spacer(Modifier.height(height.dp))

private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)

fun formatTime(instant: Instant?): String? = instant?.let { dateTimeFormat.withZone(ZoneId.systemDefault()).format(it) }

fun formatTime(epochMs: Long?): String? = epochMs?.let { formatTime(Instant.ofEpochMilli(it)) }

@StringRes
fun colorMeaning(color: StatusColor): Int = when (color) {
    StatusColor.GREEN -> R.string.color_green
    StatusColor.YELLOW -> R.string.color_yellow
    StatusColor.ORANGE -> R.string.color_orange
    StatusColor.RED -> R.string.color_red
    StatusColor.GREY -> R.string.color_grey
}

@StringRes
fun stateLabel(state: ConnectivityState): Int = when (state) {
    ConnectivityState.HEALTHY -> R.string.state_healthy
    ConnectivityState.WIFI_DISCONNECTED -> R.string.state_wifi_disconnected
    ConnectivityState.WIFI_CONNECTED_NO_INTERNET -> R.string.state_wifi_no_internet
    ConnectivityState.INTERNET_UNAVAILABLE -> R.string.state_internet_unavailable
    ConnectivityState.CELLULAR_AVAILABLE -> R.string.state_cellular_available
    ConnectivityState.CELLULAR_UNAVAILABLE -> R.string.state_cellular_unavailable
    ConnectivityState.NETWORK_TRANSITION -> R.string.state_transition
    ConnectivityState.MONITORING_PAUSED -> R.string.state_paused
    ConnectivityState.MONITORING_PERMISSION_REQUIRED -> R.string.state_permission_required
    ConnectivityState.UNKNOWN -> R.string.state_unknown
}

@StringRes
fun stateExplanation(state: ConnectivityState): Int = when (state) {
    ConnectivityState.HEALTHY -> R.string.state_healthy_explanation
    ConnectivityState.WIFI_DISCONNECTED -> R.string.state_wifi_disconnected_explanation
    ConnectivityState.WIFI_CONNECTED_NO_INTERNET -> R.string.state_wifi_no_internet_explanation
    ConnectivityState.INTERNET_UNAVAILABLE -> R.string.state_internet_unavailable_explanation
    ConnectivityState.CELLULAR_AVAILABLE -> R.string.state_cellular_available_explanation
    ConnectivityState.CELLULAR_UNAVAILABLE -> R.string.state_cellular_unavailable_explanation
    ConnectivityState.NETWORK_TRANSITION -> R.string.state_transition_explanation
    ConnectivityState.MONITORING_PAUSED -> R.string.state_paused_explanation
    ConnectivityState.MONITORING_PERMISSION_REQUIRED -> R.string.state_permission_required_explanation
    ConnectivityState.UNKNOWN -> R.string.state_unknown_explanation
}

/** Wording follows the terminology rule: an anomaly is described, never declared a confirmed cause. */
@StringRes
fun classificationLabel(classification: Classification): Int = when (classification) {
    Classification.NORMAL -> R.string.classification_normal
    Classification.HOUSEHOLD_CONNECTIVITY_ISSUE -> R.string.classification_household
    Classification.POSSIBLE_ISP_OUTAGE -> R.string.classification_isp
    Classification.POSSIBLE_ROUTER_OR_POWER_FAILURE -> R.string.classification_router_power
    Classification.POSSIBLE_WIRELESS_INTERFERENCE -> R.string.classification_wireless
    Classification.CORRELATED_NEIGHBORHOOD_DISRUPTION -> R.string.classification_correlated
    Classification.HIGH_PRIORITY_SUSPICIOUS_DISRUPTION -> R.string.classification_high_priority
}

@StringRes
fun incidentStatusLabel(status: IncidentStatus): Int = when (status) {
    IncidentStatus.OPEN -> R.string.incident_status_open
    IncidentStatus.RESOLVED -> R.string.incident_status_resolved
    IncidentStatus.EXPIRED -> R.string.incident_status_expired
}

@StringRes
fun monitoringStatusLabel(status: MonitoringStatus): Int = when (status) {
    MonitoringStatus.CONFIRMED_ACTIVE -> R.string.monitoring_confirmed_active
    MonitoringStatus.KNOWN_GAP -> R.string.monitoring_known_gap
    MonitoringStatus.PAUSED -> R.string.monitoring_paused
    MonitoringStatus.NO_RECENT_TELEMETRY -> R.string.monitoring_no_recent
    MonitoringStatus.FAILURE_REPORTED -> R.string.monitoring_failure_reported
    MonitoringStatus.NEVER_REPORTED -> R.string.monitoring_never_reported
}

@StringRes
fun ispLabel(isp: Isp): Int = when (isp) {
    Isp.ATT -> R.string.isp_att
    Isp.XFINITY -> R.string.isp_xfinity
    Isp.SPECTRUM -> R.string.isp_spectrum
    Isp.VERIZON -> R.string.isp_verizon
    Isp.TMOBILE_HOME -> R.string.isp_tmobile_home
    Isp.GOOGLE_FIBER -> R.string.isp_google_fiber
    Isp.OTHER -> R.string.isp_other
    Isp.UNKNOWN -> R.string.isp_unknown
}

/** Provider keys in `isp_diversity` are the API's lower-case values. Unknown keys are shown as sent. */
@Composable
fun ispKeyLabel(key: String): String {
    val isp = Isp.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
    return if (isp != null) stringResource(ispLabel(isp)) else key
}

@StringRes
fun errorMessage(error: ApiError): Int = when {
    error.isNetwork -> R.string.error_network
    else -> when (error.code) {
        "not_a_member" -> R.string.error_not_a_member
        "invitation_invalid" -> R.string.error_invitation_invalid
        "address_not_recognized" -> R.string.error_address_not_recognized
        "household_already_registered" -> R.string.error_household_already_registered
        "household_suspended" -> R.string.error_household_suspended
        "device_revoked" -> R.string.error_device_revoked
        "rate_limited" -> R.string.error_rate_limited
        "validation_error" -> R.string.error_validation
        "unauthorized" -> R.string.error_unauthorized
        else -> if (error.httpCode == 429) R.string.error_rate_limited else R.string.error_generic
    }
}
