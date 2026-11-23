package org.neighborhoodwatchdog.android.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.neighborhoodwatchdog.android.R
import org.neighborhoodwatchdog.android.domain.model.Isp
import org.neighborhoodwatchdog.android.ui.common.ErrorText
import org.neighborhoodwatchdog.android.ui.common.ScreenScaffold
import org.neighborhoodwatchdog.android.ui.common.SectionCard
import org.neighborhoodwatchdog.android.ui.common.ispLabel

/** What the app does and does not do. Nothing is monitored or sent before the phone has joined. */
@Composable
fun IntroScreen(viewModel: IntroViewModel = hiltViewModel()) {
    ScreenScaffold(title = stringResource(R.string.app_name)) {
        Text(stringResource(R.string.welcome_intro), style = MaterialTheme.typography.bodyLarge)
        SectionCard(stringResource(R.string.edu_monitored_title)) { Text(stringResource(R.string.edu_monitored_body)) }
        SectionCard(stringResource(R.string.edu_transmitted_title)) { Text(stringResource(R.string.edu_transmitted_body)) }
        SectionCard(stringResource(R.string.edu_not_collected_title)) { Text(stringResource(R.string.edu_not_collected_body)) }
        SectionCard(stringResource(R.string.edu_cellular_title)) { Text(stringResource(R.string.edu_cellular_body)) }
        SectionCard(stringResource(R.string.edu_background_title)) { Text(stringResource(R.string.edu_background_body)) }
        SectionCard(stringResource(R.string.edu_correlation_title)) { Text(stringResource(R.string.edu_correlation_body)) }
        SectionCard(stringResource(R.string.edu_disable_title)) { Text(stringResource(R.string.edu_disable_body)) }
        SectionCard(stringResource(R.string.edu_leave_title)) { Text(stringResource(R.string.edu_leave_body)) }
        Button(onClick = viewModel::continueToJoin, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

/**
 * Two steps on one screen: the invitation link (skipped when the link opened the app), then the
 * street and an optional house number. Nothing else is asked.
 */
@Composable
fun JoinScreen(onOpenTransparency: () -> Unit, viewModel: JoinViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    ScreenScaffold(title = stringResource(R.string.join_title)) {
        if (state.enrolmentEnded) Text(stringResource(R.string.join_enrolment_ended), color = MaterialTheme.colorScheme.error)
        if (state.token == null) {
            Text(stringResource(R.string.join_link_intro))
            OutlinedTextField(
                value = state.link,
                onValueChange = viewModel::setLink,
                label = { Text(stringResource(R.string.join_invitation_label)) },
                supportingText = { Text(stringResource(R.string.join_invitation_help)) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(state.error?.let { stringResource(it) })
            Button(onClick = viewModel::submitLink, enabled = !state.busy && state.link.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_continue))
            }
        } else {
            state.neighborhoodName?.let { Text(stringResource(R.string.join_neighborhood, it), style = MaterialTheme.typography.titleMedium) }
            OutlinedTextField(
                value = state.streetName,
                onValueChange = viewModel::setStreet,
                label = { Text(stringResource(R.string.join_street_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.houseNumber,
                onValueChange = viewModel::setHouseNumber,
                label = { Text(stringResource(R.string.join_house_number_label)) },
                supportingText = { Text(stringResource(R.string.join_house_number_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionCard(stringResource(R.string.join_privacy_title)) {
                Text(stringResource(R.string.join_privacy_summary))
                TextButton(
                    onClick = {
                        val url = viewModel.config.privacyUrl
                        if (url.isBlank()) {
                            onOpenTransparency()
                        } else {
                            try {
                                uriHandler.openUri(url)
                            } catch (_: Exception) {
                                // No app can open the link: show what the app itself documents.
                                onOpenTransparency()
                            }
                        }
                    },
                ) { Text(stringResource(R.string.join_privacy_full)) }
            }
            ErrorText(state.serverMessage ?: state.error?.let { stringResource(it) })
            Button(onClick = viewModel::join, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.join_button))
            }
            Text(stringResource(R.string.join_acknowledgement), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::changeLink, enabled = !state.busy) { Text(stringResource(R.string.join_other_link)) }
        }
        if (state.busy) CircularProgressIndicator()
    }
}

@Composable
fun IspPicker(selected: Isp?, onSelected: (Isp?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            val name = selected?.let { stringResource(ispLabel(it)) } ?: stringResource(R.string.isp_not_set)
            Text(stringResource(R.string.join_isp_label, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Isp.entries.forEach { isp ->
                DropdownMenuItem(
                    text = { Text(stringResource(ispLabel(isp))) },
                    onClick = {
                        onSelected(isp)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
fun NotificationSetupScreen(viewModel: NotificationSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onNotificationPermissionResult()
    }
    ScreenScaffold(title = stringResource(R.string.setup_title)) {
        Text(stringResource(R.string.setup_joined))
        SectionCard(stringResource(R.string.setup_notifications_title)) {
            Text(stringResource(R.string.setup_notifications_rationale))
            if (!state.pushConfigured) {
                Text(stringResource(R.string.push_not_configured), color = MaterialTheme.colorScheme.error)
            }
            if (state.notificationsGranted) {
                Text(stringResource(R.string.setup_notifications_allowed))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text(stringResource(R.string.setup_notifications_allow))
                }
            } else {
                Text(stringResource(R.string.setup_notifications_blocked))
            }
            when (state.push) {
                PushSetup.REGISTERED -> Text(stringResource(R.string.setup_push_registered))
                PushSetup.FAILED -> Text(stringResource(R.string.setup_push_failed))
                PushSetup.NOT_CONFIGURED, PushSetup.NOT_TRIED -> Unit
            }
        }
        Button(onClick = viewModel::finish, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

@Composable
fun HomeNetworkScreen(viewModel: HomeNetworkViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = stringResource(R.string.home_title)) {
        Text(stringResource(R.string.home_body))
        Text(stringResource(R.string.home_privacy), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (state.onWifi) R.string.home_on_wifi else R.string.home_not_on_wifi))
        ErrorText(state.error?.let { stringResource(it) })
        Button(onClick = viewModel::confirm, enabled = state.onWifi, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_confirm))
        }
        OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_check_again))
        }
        TextButton(onClick = viewModel::skip) { Text(stringResource(R.string.home_skip)) }
        Text(stringResource(R.string.home_skip_note), style = MaterialTheme.typography.bodySmall)
    }
}
