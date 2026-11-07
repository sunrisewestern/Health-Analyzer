package com.healthanalyzer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = RunnerTeal,
    onPrimary = Color(0xFF00382E),
    primaryContainer = RunnerTealContainer,
    onPrimaryContainer = RunnerTealLight,
    secondary = PulseCoral,
    onSecondary = Color(0xFF5A000A),
    secondaryContainer = PulseCoralContainer,
    onSecondaryContainer = PulseCoralLight,
    tertiary = AiPurple,
    onTertiary = Color(0xFF26005E),
    tertiaryContainer = AiPurpleContainer,
    onTertiaryContainer = AiPurpleLight,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFF2B364C),
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

private val LightColorScheme = lightColorScheme(
    primary = RunnerTealDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7FFF2),
    onPrimaryContainer = Color(0xFF004D40),
    secondary = Color(0xFFD32F2F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD8),
    onSecondaryContainer = Color(0xFF410006),
    tertiary = Color(0xFF6A3CE8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8DDFF),
    onTertiaryContainer = Color(0xFF21005D),
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFFCBD5E1),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

@Composable
fun HealthAnalyzerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
