package org.neighborhoodwatchdog.android.ui.main

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.neighborhoodwatchdog.android.R
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.ConnectivityStatusOut
import org.neighborhoodwatchdog.android.data.api.NeighborhoodOut
import org.neighborhoodwatchdog.android.data.local.LocalState
import org.neighborhoodwatchdog.android.data.repository.WatchdogRepository
import org.neighborhoodwatchdog.android.domain.model.StatusColor
import org.neighborhoodwatchdog.android.monitoring.MonitorStatus
import org.neighborhoodwatchdog.android.monitoring.MonitoringController
import org.neighborhoodwatchdog.android.monitoring.PermissionStatus
import org.neighborhoodwatchdog.android.ui.common.InfoRow
import org.neighborhoodwatchdog.android.ui.common.ScreenScaffold
import org.neighborhoodwatchdog.android.ui.common.SectionCard
import org.neighborhoodwatchdog.android.ui.common.StatusCard
import org.neighborhoodwatchdog.android.ui.common.errorMessage
import org.neighborhoodwatchdog.android.ui.common.formatTime
import org.neighborhoodwatchdog.android.ui.common.monitoringStatusLabel
import org.neighborhoodwatchdog.android.ui.common.stateExplanation
import org.neighborhoodwatchdog.android.ui.common.stateLabel
import javax.inject.Inject

data class BackendView(
    val loading: Boolean = true,
    /** Null when the backend could not be reached or refused the request. */
    val status: ConnectivityStatusOut? = null,
    val neighborhood: NeighborhoodOut? = null,
    val errorMessage: Int? = null,
)

data class PermissionView(
    val notifications: Boolean = false,
    val monitoring: Boolean = true,
    val backgroundRestricted: Boolean? = null,
    val batterySaver: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: WatchdogRepository,
    private val controller: MonitoringController,
    private val permissionStatus: PermissionStatus,
    localState: LocalState,
) : ViewModel() {
    val monitor: StateFlow<MonitorStatus> = controller.status
    val lastReportAtMs: StateFlow<Long?> = localState.lastReportAtMs

    private val _backend = MutableStateFlow(BackendView())
    val backend: StateFlow<BackendView> = _backend

    private val _permissions = MutableStateFlow(readPermissions())
    val permissions: StateFlow<PermissionView> = _permissions

    private fun readPermissions() = PermissionView(
        notifications = permissionStatus.notificationsGranted,
        monitoring = permissionStatus.monitoringPermissionsGranted,
        backgroundRestricted = permissionStatus.backgroundRestricted,
        batterySaver = permissionStatus.batterySaverOn,
    )

    /** Household status and neighborhood status come from the server; nothing is derived locally. */
    suspend fun refresh() {
        _permissions.value = readPermissions()
        val status = repository.status()
        val neighborhood = repository.neighborhood()
        val failure = (status as? ApiResult.Failure) ?: (neighborhood as? ApiResult.Failure)
        _backend.update {
            BackendView(
                loading = false,
                status = (status as? ApiResult.Success)?.value,
                neighborhood = (neighborhood as? ApiResult.Success)?.value,
                errorMessage = failure?.let { f -> errorMessage(f.error) },
            )
        }
    }

    fun refreshNow() {
        viewModelScope.launch { refresh() }
    }
}

@Composable
fun MainScreen(
    onOpenIncidents: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTransparency: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val monitor by viewModel.monitor.collectAsStateWithLifecycle()
    val backend by viewModel.backend.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val lastReport by viewModel.lastReportAtMs.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refresh()
                delay(60_000)
            }
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.app_name),
        actions = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_title)) } },
    ) {
        // 1. Household monitoring status, as the server sees it.
        val status = backend.status
        when {
            status != null -> StatusCard(
                color = status.statusColor,
                title = stringResource(R.string.main_household_title),
                explanation = status.statusExplanation,
            ) {
                InfoRow(
                    stringResource(R.string.main_household_monitoring),
                    stringResource(monitoringStatusLabel(status.householdMonitoringStatus)),
                )
            }
            backend.loading -> SectionCard(stringResource(R.string.main_household_title)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.main_loading))
            }
            else -> StatusCard(
                color = StatusColor.GREY,
                title = stringResource(R.string.main_household_title),
                explanation = stringResource(R.string.main_backend_unreachable),
            ) {
                backend.errorMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            }
        }

        // 2. This phone's own state. Local facts only; unknown values are shown as not measured.
        SectionCard(stringResource(R.string.main_device_title)) {
            Text(stringResource(stateLabel(monitor.state)), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(stateExplanation(monitor.state)))
            val snapshot = monitor.snapshot
            InfoRow(stringResource(R.string.main_row_wifi), stringResource(triState(snapshot?.wifiAssociated, R.string.value_connected, R.string.value_not_connected)))
            InfoRow(
                stringResource(R.string.main_row_internet),
                stringResource(triState(snapshot?.internetReachable, R.string.value_reachable, R.string.value_not_reachable)),
            )
            InfoRow(
                stringResource(R.string.main_row_cellular),
                stringResource(triState(snapshot?.cellularAvailable, R.string.value_available, R.string.value_not_available)),
            )
            InfoRow(
                stringResource(R.string.main_row_home_network),
                stringResource(triState(snapshot?.homeNetworkMatch, R.string.value_home_match, R.string.value_home_other)),
            )
            if (snapshot?.cellularAvailable == null && snapshot?.wifiAssociated == true) {
                Text(stringResource(R.string.main_cellular_note), style = MaterialTheme.typography.bodySmall)
            }
            monitor.uploadBlockedCode?.let {
                Text(stringResource(R.string.main_upload_blocked, it), color = MaterialTheme.colorScheme.error)
            }
        }

        // 3. Reporting and permissions.
        SectionCard(stringResource(R.string.main_reporting_title)) {
            InfoRow(
                stringResource(R.string.main_last_report),
                formatTime(lastReport) ?: stringResource(R.string.value_none_yet),
            )
            InfoRow(
                stringResource(R.string.main_monitoring_running),
                stringResource(if (monitor.running) R.string.value_running else R.string.value_not_running),
            )
            InfoRow(
                stringResource(R.string.main_permission_monitoring),
                stringResource(if (permissions.monitoring) R.string.value_granted else R.string.value_missing),
            )
            InfoRow(
                stringResource(R.string.main_permission_notifications),
                stringResource(if (permissions.notifications) R.string.value_granted else R.string.value_not_granted),
            )
            if (permissions.backgroundRestricted == true) {
                Text(stringResource(R.string.main_background_restricted), color = MaterialTheme.colorScheme.error)
            }
            if (permissions.batterySaver) Text(stringResource(R.string.main_battery_saver))
            Text(stringResource(R.string.main_background_note), style = MaterialTheme.typography.bodySmall)
        }

        // 4. Neighborhood incident status.
        val neighborhood = backend.neighborhood
        if (neighborhood != null) {
            StatusCard(
                color = neighborhood.statusColor,
                title = stringResource(R.string.main_neighborhood_title, neighborhood.name),
                explanation = neighborhood.statusExplanation,
            ) {
                Text(
                    pluralStringResource(
                        R.plurals.main_open_incidents,
                        neighborhood.openIncidentCount,
                        neighborhood.openIncidentCount,
                    ),
                )
                Text(
                    stringResource(
                        R.string.main_active_households,
                        neighborhood.confirmedActiveHouseholds,
                        neighborhood.participatingHouseholds,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (!backend.loading) {
            StatusCard(
                color = StatusColor.GREY,
                title = stringResource(R.string.main_neighborhood_title_unknown),
                explanation = stringResource(R.string.main_neighborhood_unknown),
            )
        }
        Button(onClick = onOpenIncidents, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.main_view_incidents)) }
        OutlinedButton(onClick = viewModel::refreshNow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_refresh)) }
        OutlinedButton(onClick = onOpenTransparency, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.transparency_title))
        }
    }
}

/** True, false or "not measured". An unknown value is never shown as either answer. */
private fun triState(value: Boolean?, yes: Int, no: Int): Int = when (value) {
    true -> yes
    false -> no
    null -> R.string.value_not_measured
}
