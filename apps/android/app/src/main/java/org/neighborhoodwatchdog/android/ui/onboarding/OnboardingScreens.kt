package org.neighborhoodwatchdog.android.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.neighborhoodwatchdog.android.R
import org.neighborhoodwatchdog.android.domain.model.Isp
import org.neighborhoodwatchdog.android.ui.common.ErrorText
import org.neighborhoodwatchdog.android.ui.common.ScreenScaffold
import org.neighborhoodwatchdog.android.ui.common.SectionCard
import org.neighborhoodwatchdog.android.ui.common.ispLabel

/** Education first, then explicit consent. Nothing is monitored or sent before the button is pressed. */
@Composable
fun ConsentScreen(viewModel: ConsentViewModel = hiltViewModel()) {
    var agreed by rememberSaveable { mutableStateOf(false) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text(stringResource(R.string.consent_checkbox), modifier = Modifier.weight(1f))
        }
        Button(onClick = viewModel::giveConsent, enabled = agreed, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.consent_button))
        }
    }
}

@Composable
fun SignInScreen(viewModel: SignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = stringResource(R.string.sign_in_title)) {
        if (!state.codeRequested) {
            Text(stringResource(R.string.sign_in_email_help))
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::setEmail,
                label = { Text(stringResource(R.string.sign_in_email_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(state.error?.let { stringResource(it) })
            Button(onClick = viewModel::requestCode, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sign_in_send_code))
            }
        } else {
            Text(stringResource(R.string.sign_in_code_help, state.email.trim()))
            OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::setCode,
                label = { Text(stringResource(R.string.sign_in_code_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(state.error?.let { stringResource(it) })
            Button(onClick = viewModel::verify, enabled = !state.busy && state.code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sign_in_verify))
            }
            TextButton(onClick = viewModel::requestCode, enabled = !state.busy) { Text(stringResource(R.string.sign_in_resend)) }
            TextButton(onClick = viewModel::changeEmail, enabled = !state.busy) { Text(stringResource(R.string.sign_in_change_email)) }
        }
        if (state.busy) CircularProgressIndicator()
    }
}

@Composable
fun MembershipScreen(viewModel: MembershipViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = stringResource(R.string.join_title)) {
        when {
            state.checking -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.join_checking))
            }
            !state.showJoinForm -> {
                ErrorText(state.error?.let { stringResource(it) })
                Button(onClick = viewModel::check) { Text(stringResource(R.string.action_retry)) }
                TextButton(onClick = viewModel::signOut) { Text(stringResource(R.string.settings_sign_out)) }
            }
            else -> {
                Text(stringResource(R.string.join_intro))
                OutlinedTextField(
                    value = state.invitation,
                    onValueChange = { value -> viewModel.edit { it.copy(invitation = value) } },
                    label = { Text(stringResource(R.string.join_invitation_label)) },
                    supportingText = { Text(stringResource(R.string.join_invitation_help)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.houseNumber,
                    onValueChange = { value -> viewModel.edit { it.copy(houseNumber = value.take(16)) } },
                    label = { Text(stringResource(R.string.join_house_number_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.streetName,
                    onValueChange = { value -> viewModel.edit { it.copy(streetName = value.take(80)) } },
                    label = { Text(stringResource(R.string.join_street_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.displayLabel,
                    onValueChange = { value -> viewModel.edit { it.copy(displayLabel = value.take(40)) } },
                    label = { Text(stringResource(R.string.join_label_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IspPicker(selected = state.isp, onSelected = { isp -> viewModel.edit { it.copy(isp = isp) } })
                Text(stringResource(R.string.join_address_note), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.privacyAcknowledged,
                        onCheckedChange = { value -> viewModel.edit { it.copy(privacyAcknowledged = value) } },
                    )
                    Text(stringResource(R.string.join_privacy_checkbox), modifier = Modifier.weight(1f))
                }
                state.neighborhoodName?.let { Text(stringResource(R.string.join_neighborhood, it)) }
                ErrorText(state.error?.let { stringResource(it) })
                Button(onClick = viewModel::join, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.join_button))
                }
                if (state.busy) CircularProgressIndicator()
                TextButton(onClick = viewModel::signOut) { Text(stringResource(R.string.settings_sign_out)) }
            }
        }
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
fun DeviceSetupScreen(viewModel: DeviceSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onNotificationPermissionResult()
    }
    ScreenScaffold(title = stringResource(R.string.setup_title)) {
        SectionCard(stringResource(R.string.setup_device_title)) {
            Text(stringResource(R.string.setup_device_body))
            when {
                state.registering -> CircularProgressIndicator()
                state.registered -> Text(stringResource(R.string.setup_device_done))
                else -> {
                    ErrorText(state.error?.let { stringResource(it) })
                    Button(onClick = viewModel::register) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
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
        Button(onClick = viewModel::finish, enabled = state.registered, modifier = Modifier.fillMaxWidth()) {
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
