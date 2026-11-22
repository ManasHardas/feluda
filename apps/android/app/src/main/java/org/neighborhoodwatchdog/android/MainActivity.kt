package org.neighborhoodwatchdog.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.neighborhoodwatchdog.android.push.WatchdogMessagingService
import org.neighborhoodwatchdog.android.ui.AppRoot
import org.neighborhoodwatchdog.android.ui.theme.WatchdogTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Set when the activity was opened from a notification about an incident. */
    private val pendingIncidentId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readIncident(intent)
        setContent {
            val incidentId by pendingIncidentId.collectAsState()
            WatchdogTheme {
                AppRoot(pendingIncidentId = incidentId, onIncidentOpened = { pendingIncidentId.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIncident(intent)
    }

    /** The id only selects which incident to ask the server for; it must look like a UUID. */
    private fun readIncident(intent: Intent?) {
        val id = intent?.getStringExtra(WatchdogMessagingService.KEY_INCIDENT_ID) ?: return
        if (UUID_PATTERN.matches(id)) pendingIncidentId.value = id
    }

    private companion object {
        val UUID_PATTERN = Regex("^[0-9a-fA-F-]{32,36}$")
    }
}
