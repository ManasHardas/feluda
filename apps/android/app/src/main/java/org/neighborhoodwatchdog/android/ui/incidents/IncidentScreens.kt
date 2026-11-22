package org.neighborhoodwatchdog.android.ui.incidents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.neighborhoodwatchdog.android.R
import org.neighborhoodwatchdog.android.data.api.ApiResult
import org.neighborhoodwatchdog.android.data.api.IncidentDetailOut
import org.neighborhoodwatchdog.android.data.api.IncidentOut
import org.neighborhoodwatchdog.android.data.repository.WatchdogRepository
import org.neighborhoodwatchdog.android.domain.model.IncidentScope
import org.neighborhoodwatchdog.android.ui.common.BulletList
import org.neighborhoodwatchdog.android.ui.common.ErrorText
import org.neighborhoodwatchdog.android.ui.common.InfoRow
import org.neighborhoodwatchdog.android.ui.common.ScreenScaffold
import org.neighborhoodwatchdog.android.ui.common.SectionCard
import org.neighborhoodwatchdog.android.ui.common.StatusCard
import org.neighborhoodwatchdog.android.ui.common.classificationLabel
import org.neighborhoodwatchdog.android.ui.common.errorMessage
import org.neighborhoodwatchdog.android.ui.common.formatTime
import org.neighborhoodwatchdog.android.ui.common.incidentStatusLabel
import org.neighborhoodwatchdog.android.ui.common.ispKeyLabel
import javax.inject.Inject

data class IncidentListState(
    val loading: Boolean = true,
    val openOnly: Boolean = true,
    val incidents: List<IncidentOut> = emptyList(),
    val error: Int? = null,
)

@HiltViewModel
class IncidentListViewModel @Inject constructor(private val repository: WatchdogRepository) : ViewModel() {
    private val _state = MutableStateFlow(IncidentListState())
    val state: StateFlow<IncidentListState> = _state

    init {
        load()
    }

    fun setOpenOnly(openOnly: Boolean) {
        _state.update { it.copy(openOnly = openOnly) }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.incidents(_state.value.openOnly)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, incidents = result.value.incidents) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = errorMessage(result.error)) }
            }
        }
    }
}

@Composable
fun IncidentListScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: IncidentListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = stringResource(R.string.incidents_title), onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.openOnly,
                onClick = { viewModel.setOpenOnly(true) },
                label = { Text(stringResource(R.string.incidents_filter_open)) },
            )
            FilterChip(
                selected = !state.openOnly,
                onClick = { viewModel.setOpenOnly(false) },
                label = { Text(stringResource(R.string.incidents_filter_all)) },
            )
        }
        if (state.loading) CircularProgressIndicator()
        state.error?.let {
            ErrorText(stringResource(it))
            Button(onClick = viewModel::load) { Text(stringResource(R.string.action_retry)) }
        }
        if (!state.loading && state.error == null && state.incidents.isEmpty()) {
            Text(stringResource(if (state.openOnly) R.string.incidents_none_open else R.string.incidents_none))
            Text(stringResource(R.string.incidents_none_note), style = MaterialTheme.typography.bodySmall)
        }
        state.incidents.forEach { incident ->
            Box(modifier = Modifier.fillMaxWidth().clickable { onOpen(incident.incidentId) }) {
                StatusCard(
                    color = incident.statusColor,
                    title = stringResource(classificationLabel(incident.classification)),
                    explanation = incident.summary,
                ) {
                    InfoRow(stringResource(R.string.incident_status), stringResource(incidentStatusLabel(incident.status)))
                    InfoRow(stringResource(R.string.incident_started), formatTime(incident.startedAt).orEmpty())
                    InfoRow(
                        stringResource(R.string.incident_affected),
                        pluralStringResource(R.plurals.household_count, incident.affectedHouseholdCount, incident.affectedHouseholdCount),
                    )
                    if (incident.myHouseholdAffected) Text(stringResource(R.string.incident_my_household))
                    Text(stringResource(R.string.incidents_open_details), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

data class IncidentDetailState(val loading: Boolean = true, val incident: IncidentDetailOut? = null, val error: Int? = null)

@HiltViewModel
class IncidentDetailViewModel @Inject constructor(
    private val repository: WatchdogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val incidentId: String = savedStateHandle.get<String>("incidentId").orEmpty()
    private val _state = MutableStateFlow(IncidentDetailState())
    val state: StateFlow<IncidentDetailState> = _state

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.incident(incidentId)) {
                is ApiResult.Success -> _state.update { IncidentDetailState(loading = false, incident = result.value) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = errorMessage(result.error)) }
            }
        }
    }
}

/** Everything shown here comes from the server. Addresses are never part of an incident. */
@Composable
fun IncidentDetailScreen(onBack: () -> Unit, viewModel: IncidentDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = stringResource(R.string.incident_title), onBack = onBack) {
        if (state.loading) CircularProgressIndicator()
        state.error?.let {
            ErrorText(stringResource(it))
            Button(onClick = viewModel::load) { Text(stringResource(R.string.action_retry)) }
        }
        val incident = state.incident ?: return@ScreenScaffold
        StatusCard(
            color = incident.statusColor,
            title = stringResource(classificationLabel(incident.classification)),
            explanation = incident.explanation.ifBlank { incident.summary },
        ) {
            Text(stringResource(R.string.incident_not_a_confirmation), style = MaterialTheme.typography.bodySmall)
        }
        SectionCard(stringResource(R.string.incident_facts_title)) {
            InfoRow(stringResource(R.string.incident_status), stringResource(incidentStatusLabel(incident.status)))
            InfoRow(stringResource(R.string.incident_started), formatTime(incident.startedAt).orEmpty())
            InfoRow(stringResource(R.string.incident_last_event), formatTime(incident.lastEventAt).orEmpty())
            incident.resolvedAt?.let { InfoRow(stringResource(R.string.incident_ended), formatTime(it).orEmpty()) }
            InfoRow(
                stringResource(R.string.incident_scope),
                stringResource(if (incident.scope == IncidentScope.NEIGHBORHOOD) R.string.incident_scope_neighborhood else R.string.incident_scope_household),
            )
            InfoRow(
                stringResource(R.string.incident_affected),
                pluralStringResource(R.plurals.household_count, incident.affectedHouseholdCount, incident.affectedHouseholdCount),
            )
            InfoRow(
                stringResource(R.string.incident_active_households),
                pluralStringResource(R.plurals.household_count, incident.activeHouseholdCount, incident.activeHouseholdCount),
            )
            InfoRow(stringResource(R.string.incident_approximate_scope), incident.approximateScope)
            InfoRow(
                stringResource(R.string.incident_correlation_window),
                stringResource(R.string.value_seconds, incident.correlationWindowS),
            )
            InfoRow(
                stringResource(R.string.incident_time_spread),
                stringResource(R.string.value_seconds, incident.timeSpreadS.toInt()),
            )
            if (incident.delayedReportCount > 0) {
                InfoRow(stringResource(R.string.incident_delayed_reports), incident.delayedReportCount.toString())
            }
            if (incident.myHouseholdAffected) Text(stringResource(R.string.incident_my_household))
        }
        SectionCard(stringResource(R.string.incident_isp_title)) {
            if (incident.ispDiversity.isEmpty()) {
                Text(stringResource(R.string.incident_isp_unknown))
            } else {
                incident.ispDiversity.forEach { (provider, count) ->
                    InfoRow(ispKeyLabel(provider), pluralStringResource(R.plurals.household_count, count, count))
                }
            }
        }
        SectionCard(stringResource(R.string.incident_supporting_title)) {
            if (incident.supportingEvidence.isEmpty()) Text(stringResource(R.string.incident_none_listed)) else BulletList(incident.supportingEvidence)
        }
        SectionCard(stringResource(R.string.incident_missing_title)) {
            if (incident.missingEvidence.isEmpty()) Text(stringResource(R.string.incident_none_listed)) else BulletList(incident.missingEvidence)
        }
        SectionCard(stringResource(R.string.incident_actions_title)) {
            if (incident.recommendedActions.isEmpty()) Text(stringResource(R.string.incident_none_listed)) else BulletList(incident.recommendedActions)
        }
        SectionCard(stringResource(R.string.incident_score_title)) {
            Text(
                incident.score?.let { stringResource(R.string.incident_score_value, it) } ?: stringResource(R.string.incident_score_none),
            )
            Text(stringResource(R.string.incident_score_note), style = MaterialTheme.typography.bodySmall)
            incident.scoreBreakdown.forEach { component ->
                InfoRow(component.indicator, stringResource(R.string.incident_score_points, component.points, component.maxPoints))
                Text(component.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
