package org.neighborhoodwatchdog.android.ui.settings

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.neighborhoodwatchdog.android.R
import org.neighborhoodwatchdog.android.config.AppConfig
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.DeviceOut
import org.neighborhoodwatchdog.android.data.api.HouseholdOut
import org.neighborhoodwatchdog.android.data.api.NotificationPreferences
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.data.repository.DeviceRepository
import org.neighborhoodwatchdog.android.data.repository.WatchdogRepository
import org.neighborhoodwatchdog.android.domain.model.DeviceStatus
import org.neighborhoodwatchdog.android.domain.model.Isp
import org.neighborhoodwatchdog.android.monitoring.HomeNetworkManager
import org.neighborhoodwatchdog.android.monitoring.MonitoringController
import org.neighborhoodwatchdog.android.monitoring.PermissionStatus
import org.neighborhoodwatchdog.android.push.PushManager
import org.neighborhoodwatchdog.android.session.SessionCoordinator
import org.neighborhoodwatchdog.android.ui.common.ErrorText
import org.neighborhoodwatchdog.android.ui.common.InfoRow
import org.neighborhoodwatchdog.android.ui.common.ScreenScaffold
import org.neighborhoodwatchdog.android.ui.common.SectionCard
import org.neighborhoodwatchdog.android.ui.common.errorMessage
import org.neighborhoodwatchdog.android.ui.common.formatTime
import org.neighborhoodwatchdog.android.ui.common.ispLabel
import org.neighborhoodwatchdog.android.ui.onboarding.IspPicker
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

data class SettingsState(
    val loading: Boolean = true,
    val household: HouseholdOut? = null,
    val devices: List<DeviceOut> = emptyList(),
    val preferences: NotificationPreferences? = null,
    val loadError: Int? = null,
    val busy: Boolean = false,
    val actionError: Int? = null,
    val notificationsGranted: Boolean = false,
    val homeNetworkSet: Boolean = false,
    val homeNetworkMessage: Int? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: WatchdogRepository,
    private val devices: DeviceRepository,
    private val controller: MonitoringController,
    private val session: SessionCoordinator,
    private val permissions: PermissionStatus,
    private val home: HomeNetworkManager,
    private val localState: LocalState,
    pushManager: PushManager,
    val config: AppConfig,
) : ViewModel() {
    val monitoringEnabled: StateFlow<Boolean> = localState.monitoringEnabledFlow
    val lastReportAtMs: StateFlow<Long?> = localState.lastReportAtMs
    val installId: String = localState.installId()
    val pushConfigured: Boolean = pushManager.configured

    private val _state = MutableStateFlow(
        SettingsState(notificationsGranted = permissions.notificationsGranted, homeNetworkSet = home.hasFingerprint),
    )
    val state: StateFlow<SettingsState> = _state

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null, notificationsGranted = permissions.notificationsGranted) }
        viewModelScope.launch {
            val household = repository.household()
            val deviceList = devices.devices()
            val preferences = repository.preferences()
            // A 401 `unauthorized` (this phone was removed) is handled centrally: the app returns to the join screen.
            val failure = listOf(household, deviceList, preferences).filterIsInstance<ApiResult.Failure>().firstOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    household = (household as? ApiResult.Success)?.value ?: it.household,
                    devices = (deviceList as? ApiResult.Success)?.value ?: it.devices,
                    preferences = (preferences as? ApiResult.Success)?.value ?: it.preferences,
                    loadError = failure?.let { f -> errorMessage(f.error) },
                )
            }
        }
    }

    /** Off emits MONITORING_DISABLED_BY_USER and stops the monitor. */
    fun setMonitoring(enabled: Boolean) {
        viewModelScope.launch { controller.setMonitoringEnabled(enabled) }
    }

    fun updatePreferences(transform: (NotificationPreferences) -> NotificationPreferences) {
        val current = _state.value.preferences ?: return
        val updated = transform(current).copy(timezone = TimeZone.getDefault().id.take(64))
        _state.update { it.copy(preferences = updated, actionError = null) }
        viewModelScope.launch {
            when (val result = repository.savePreferences(updated)) {
                is ApiResult.Success -> _state.update { it.copy(preferences = result.value) }
                // Not saved: show what the server still has.
                is ApiResult.Failure -> _state.update { it.copy(preferences = current, actionError = errorMessage(result.error)) }
            }
        }
    }

    fun saveHousehold(displayLabel: String, isp: Isp?) = action {
        when (val result = repository.updateHousehold(displayLabel, isp)) {
            is ApiResult.Success -> {
                _state.update { it.copy(household = result.value) }
                null
            }
            is ApiResult.Failure -> errorMessage(result.error)
        }
    }

    fun revokeDevice(deviceId: String) = action {
        when (val result = devices.revoke(deviceId)) {
            is ApiResult.Success -> {
                load()
                null
            }
            is ApiResult.Failure -> errorMessage(result.error)
        }
    }

    fun setHomeNetwork() {
        val stored = home.confirmCurrentNetworkAsHome()
        _state.update {
            it.copy(
                homeNetworkSet = home.hasFingerprint,
                homeNetworkMessage = if (stored) R.string.settings_home_saved else R.string.home_error_no_wifi,
            )
        }
    }

    fun forgetHomeNetwork() {
        home.forget()
        _state.update { it.copy(homeNetworkSet = false, homeNetworkMessage = R.string.settings_home_forgotten) }
    }

    /** The server deletes this phone and what it reported; then the app forgets its own data. */
    fun leave() = action {
        (session.leaveNeighborhood() as? ApiResult.Failure)?.let { errorMessage(it.error) }
    }

    private fun action(block: suspend () -> Int?) {
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            val error = block()
            _state.update { it.copy(busy = false, actionError = error) }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenTransparency: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val monitoringEnabled by viewModel.monitoringEnabled.collectAsStateWithLifecycle()
    val lastReport by viewModel.lastReportAtMs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<String?>(null) }
    var editingTime by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end

    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        if (state.loading) CircularProgressIndicator()
        state.loadError?.let {
            ErrorText(stringResource(it))
            OutlinedButton(onClick = viewModel::load) { Text(stringResource(R.string.action_retry)) }
        }
        ErrorText(state.actionError?.let { stringResource(it) })

        SectionCard(stringResource(R.string.settings_monitoring_title)) {
            SwitchRow(stringResource(R.string.settings_monitoring_switch), monitoringEnabled, viewModel::setMonitoring)
            Text(
                stringResource(if (monitoringEnabled) R.string.settings_monitoring_on else R.string.settings_monitoring_off),
                style = MaterialTheme.typography.bodySmall,
            )
            InfoRow(stringResource(R.string.main_last_report), formatTime(lastReport) ?: stringResource(R.string.value_none_yet))
        }

        SectionCard(stringResource(R.string.settings_notifications_title)) {
            if (!viewModel.pushConfigured) Text(stringResource(R.string.push_not_configured), color = MaterialTheme.colorScheme.error)
            InfoRow(
                stringResource(R.string.main_permission_notifications),
                stringResource(if (state.notificationsGranted) R.string.value_granted else R.string.value_not_granted),
            )
            if (!state.notificationsGranted) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                ) { Text(stringResource(R.string.settings_open_notification_settings)) }
            }
            val preferences = state.preferences
            if (preferences == null) {
                Text(stringResource(R.string.settings_preferences_unavailable))
            } else {
                SwitchRow(stringResource(R.string.settings_household_alerts), preferences.householdAlerts) { value ->
                    viewModel.updatePreferences { it.copy(householdAlerts = value) }
                }
                SwitchRow(stringResource(R.string.settings_neighborhood_alerts), preferences.neighborhoodAlerts) { value ->
                    viewModel.updatePreferences { it.copy(neighborhoodAlerts = value) }
                }
                SwitchRow(stringResource(R.string.settings_quiet_hours), preferences.quietHoursEnabled) { value ->
                    viewModel.updatePreferences { it.copy(quietHoursEnabled = value) }
                }
                if (preferences.quietHoursEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_quiet_start, preferences.quietHoursStart.take(5)), modifier = Modifier.weight(1f))
                        TextButton(onClick = { editingTime = true }) { Text(stringResource(R.string.action_change)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_quiet_end, preferences.quietHoursEnd.take(5)), modifier = Modifier.weight(1f))
                        TextButton(onClick = { editingTime = false }) { Text(stringResource(R.string.action_change)) }
                    }
                    Text(stringResource(R.string.settings_quiet_timezone, preferences.timezone), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionCard(stringResource(R.string.settings_privacy_title)) {
            Text(stringResource(R.string.settings_privacy_body))
            OutlinedButton(onClick = onOpenTransparency) { Text(stringResource(R.string.transparency_title)) }
        }

        SectionCard(stringResource(R.string.settings_home_title)) {
            Text(stringResource(if (state.homeNetworkSet) R.string.settings_home_set else R.string.settings_home_not_set))
            Text(stringResource(R.string.home_privacy), style = MaterialTheme.typography.bodySmall)
            state.homeNetworkMessage?.let { Text(stringResource(it)) }
            OutlinedButton(onClick = viewModel::setHomeNetwork) { Text(stringResource(R.string.home_confirm)) }
            if (state.homeNetworkSet) TextButton(onClick = viewModel::forgetHomeNetwork) { Text(stringResource(R.string.settings_home_forget)) }
        }

        SectionCard(stringResource(R.string.settings_household_title)) {
            val household = state.household
            if (household == null) {
                Text(stringResource(R.string.settings_household_unavailable))
            } else {
                var label by rememberSaveable(household.displayLabel) { mutableStateOf(household.displayLabel.orEmpty()) }
                var isp by remember(household.isp) { mutableStateOf<Isp?>(household.isp) }
                InfoRow(stringResource(R.string.settings_household_reference), household.anonymousReference)
                InfoRow(
                    stringResource(R.string.settings_household_address),
                    listOfNotNull(household.houseNumber?.takeIf { it.isNotBlank() }, household.streetName).joinToString(" "),
                )
                Text(stringResource(R.string.settings_household_address_note), style = MaterialTheme.typography.bodySmall)
                InfoRow(stringResource(R.string.settings_household_phones), household.deviceCount.toString())
                InfoRow(stringResource(R.string.settings_household_provider), stringResource(ispLabel(household.isp)))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text(stringResource(R.string.join_label_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IspPicker(selected = isp, onSelected = { isp = it })
                OutlinedButton(onClick = { viewModel.saveHousehold(label, isp) }, enabled = !state.busy) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }

        SectionCard(stringResource(R.string.settings_devices_title)) {
            InfoRow(stringResource(R.string.settings_device_this_id), viewModel.installId)
            Text(stringResource(R.string.settings_device_id_note), style = MaterialTheme.typography.bodySmall)
            state.devices.forEach { device ->
                val mine = device.deviceId.equals(viewModel.installId, ignoreCase = true)
                Text(
                    stringResource(
                        if (mine) R.string.settings_device_line_this else R.string.settings_device_line_other,
                        device.osVersion,
                        device.appVersion,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                InfoRow(
                    stringResource(R.string.incident_status),
                    stringResource(if (device.status == DeviceStatus.ACTIVE) R.string.device_active else R.string.device_revoked),
                )
                InfoRow(
                    stringResource(R.string.settings_device_last_seen),
                    formatTime(device.lastSeenAt) ?: stringResource(R.string.value_none_yet),
                )
                if (!mine && device.status == DeviceStatus.ACTIVE) {
                    TextButton(onClick = { revoke = device.deviceId }, enabled = !state.busy) {
                        Text(stringResource(R.string.settings_device_remove))
                    }
                }
            }
        }

        SectionCard(stringResource(R.string.settings_about_title)) {
            InfoRow(stringResource(R.string.settings_app_version), viewModel.config.appVersion)
        }

        SectionCard(stringResource(R.string.settings_leave_title)) {
            Text(stringResource(R.string.settings_leave_body))
            Button(onClick = { confirmLeave = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_leave))
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            stringResource(R.string.settings_leave),
            stringResource(R.string.settings_leave_confirm),
            onDismiss = { confirmLeave = false },
        ) {
            confirmLeave = false
            viewModel.leave()
        }
    }
    revoke?.let { deviceId ->
        ConfirmDialog(
            stringResource(R.string.settings_device_remove),
            stringResource(R.string.settings_device_remove_confirm),
            onDismiss = { revoke = null },
        ) {
            revoke = null
            viewModel.revokeDevice(deviceId)
        }
    }
    editingTime?.let { start ->
        val preferences = state.preferences
        if (preferences != null) {
            TimeDialog(
                initial = if (start) preferences.quietHoursStart else preferences.quietHoursEnd,
                onDismiss = { editingTime = null },
                onConfirm = { time ->
                    editingTime = null
                    viewModel.updatePreferences { if (start) it.copy(quietHoursStart = time) else it.copy(quietHoursEnd = time) }
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Quiet-hour times travel as "HH:MM:SS". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val parts = initial.split(":")
    val picker = rememberTimePickerState(
        initialHour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 22,
        initialMinute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = picker) },
        confirmButton = {
            TextButton(onClick = { onConfirm(String.format(Locale.ROOT, "%02d:%02d:00", picker.hour, picker.minute)) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
