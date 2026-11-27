package org.neighborhoodwatchdog.android.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.neighborhoodwatchdog.android.R
import org.neighborhoodwatchdog.android.domain.update.UpdateState

/**
 * Shown instead of every other screen while the server refuses this app version. It cannot be
 * dismissed: there is nothing behind it to go back to (back leaves the app, as on any root screen),
 * and the only ways on are the update or a check that finds the version accepted again.
 */
@Composable
fun UpdateRequiredScreen(updateUrl: String?, onCheckAgain: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.update_required_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.update_required_body), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.update_required_paused), style = MaterialTheme.typography.bodyMedium)
            if (updateUrl != null) {
                UpdateButton(updateUrl, Modifier.fillMaxWidth())
            } else {
                Text(stringResource(R.string.update_required_tester), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = onCheckAgain, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_check_again))
            }
        }
    }
}

/** Non-blocking notice on the main screen: a newer version exists, this one still works. */
@Composable
fun UpdateAvailableBanner(state: UpdateState, onDismiss: () -> Unit) {
    if (!state.showBanner) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.update_banner_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            state.latestVersion?.let { Text(stringResource(R.string.update_banner_version, it), style = MaterialTheme.typography.bodyMedium) }
            if (state.updateUrl == null) Text(stringResource(R.string.update_banner_tester), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.updateUrl?.let { UpdateButton(it) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_banner_dismiss)) }
            }
        }
    }
}

@Composable
private fun UpdateButton(url: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Button(
        onClick = {
            try {
                uriHandler.openUri(url)
            } catch (_: Exception) {
                // No app can open the link (no browser). Nothing else to do here.
            }
        },
        modifier = modifier,
    ) { Text(stringResource(R.string.update_action)) }
}
