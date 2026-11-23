package org.neighborhoodwatchdog.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.neighborhoodwatchdog.android.session.OnboardingStage
import org.neighborhoodwatchdog.android.session.SessionCoordinator
import org.neighborhoodwatchdog.android.ui.incidents.IncidentDetailScreen
import org.neighborhoodwatchdog.android.ui.incidents.IncidentListScreen
import org.neighborhoodwatchdog.android.ui.main.MainScreen
import org.neighborhoodwatchdog.android.ui.onboarding.HomeNetworkScreen
import org.neighborhoodwatchdog.android.ui.onboarding.IntroScreen
import org.neighborhoodwatchdog.android.ui.onboarding.JoinScreen
import org.neighborhoodwatchdog.android.ui.onboarding.NotificationSetupScreen
import org.neighborhoodwatchdog.android.ui.settings.SettingsScreen
import org.neighborhoodwatchdog.android.ui.transparency.TransparencyScreen
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(session: SessionCoordinator) : ViewModel() {
    val stage: StateFlow<OnboardingStage> = session.signal.version
        .map { session.stage() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.stage())
}

private object Routes {
    const val MAIN = "main"
    const val INCIDENTS = "incidents"
    const val INCIDENT = "incident/{incidentId}"
    const val SETTINGS = "settings"
    const val TRANSPARENCY = "transparency"
    const val ONBOARDING_PREFIX = "onboarding/"

    fun incident(id: String) = "incident/$id"

    fun forStage(stage: OnboardingStage): String = when (stage) {
        OnboardingStage.INTRO -> "onboarding/intro"
        OnboardingStage.JOIN -> "onboarding/join"
        OnboardingStage.NOTIFICATIONS -> "onboarding/notifications"
        OnboardingStage.HOME_NETWORK -> "onboarding/home-network"
        OnboardingStage.READY -> MAIN
    }
}

/**
 * One navigation graph. The onboarding stage decides the root screen; when the stage changes the
 * back stack is replaced, so a resident can never navigate "back" past the introduction or joining.
 */
@Composable
fun AppRoot(pendingIncidentId: String?, onIncidentOpened: () -> Unit, viewModel: AppViewModel = hiltViewModel()) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val start = remember { Routes.forStage(viewModel.stage.value) }

    LaunchedEffect(stage) {
        val target = Routes.forStage(stage)
        val current = navController.currentDestination?.route
        val alreadyThere = if (stage == OnboardingStage.READY) current != null && !current.startsWith(Routes.ONBOARDING_PREFIX) else current == target
        if (!alreadyThere) {
            navController.navigate(target) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(stage, pendingIncidentId) {
        if (stage == OnboardingStage.READY && pendingIncidentId != null) {
            navController.navigate(Routes.incident(pendingIncidentId)) { launchSingleTop = true }
            onIncidentOpened()
        }
    }
    AppNavHost(navController, start)
}

@Composable
private fun AppNavHost(navController: NavHostController, start: String) {
    NavHost(navController = navController, startDestination = start) {
        composable(Routes.forStage(OnboardingStage.INTRO)) { IntroScreen() }
        composable(Routes.forStage(OnboardingStage.JOIN)) { JoinScreen(onOpenTransparency = { navController.navigate(Routes.TRANSPARENCY) }) }
        composable(Routes.forStage(OnboardingStage.NOTIFICATIONS)) { NotificationSetupScreen() }
        composable(Routes.forStage(OnboardingStage.HOME_NETWORK)) { HomeNetworkScreen() }
        composable(Routes.MAIN) {
            MainScreen(
                onOpenIncidents = { navController.navigate(Routes.INCIDENTS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenTransparency = { navController.navigate(Routes.TRANSPARENCY) },
            )
        }
        composable(Routes.INCIDENTS) {
            IncidentListScreen(onBack = { navController.popBackStack() }, onOpen = { navController.navigate(Routes.incident(it)) })
        }
        composable(Routes.INCIDENT, arguments = listOf(navArgument("incidentId") { type = NavType.StringType })) {
            IncidentDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() }, onOpenTransparency = { navController.navigate(Routes.TRANSPARENCY) })
        }
        composable(Routes.TRANSPARENCY) { TransparencyScreen(onBack = { navController.popBackStack() }) }
    }
}
