package org.neighborhoodwatchdog.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.neighborhoodwatchdog.android.domain.model.StatusColor

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F4E79),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E4F5),
    onPrimaryContainer = Color(0xFF0B1F33),
    secondary = Color(0xFF4A6072),
    background = Color(0xFFFAFBFC),
    surface = Color(0xFFFAFBFC),
    surfaceVariant = Color(0xFFE6EBF0),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8EC),
    onPrimary = Color(0xFF0B2A47),
    primaryContainer = Color(0xFF1F4E79),
    onPrimaryContainer = Color(0xFFD5E4F5),
    secondary = Color(0xFFB4C8DA),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF2A3138),
    error = Color(0xFFF2B8B5),
)

@Composable
fun WatchdogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

/** The colour is always shown together with its meaning in words; it is never the only signal. */
fun statusColorValue(color: StatusColor): Color = when (color) {
    StatusColor.GREEN -> Color(0xFF2E7D32)
    StatusColor.YELLOW -> Color(0xFFF9A825)
    StatusColor.ORANGE -> Color(0xFFEF6C00)
    StatusColor.RED -> Color(0xFFC62828)
    StatusColor.GREY -> Color(0xFF7A7F85)
}
