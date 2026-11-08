package com.healthanalyzer.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light"),
}

enum class ThemePreset(
    val title: String,
    val subtitle: String,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
) {
    RUNNER_TEAL(
        title = "Runner Teal",
        subtitle = "Endurance teal, coral pulse & AI purple",
        primary = RunnerTeal,
        secondary = PulseCoral,
        tertiary = AiPurple,
    ),
    GARMIN_BLUE(
        title = "Garmin Connect",
        subtitle = "Telemetry blue, cyan & indigo",
        primary = GarminBlue,
        secondary = Color(0xFF00E5BE),
        tertiary = Color(0xFF7C4DFF),
    ),
    SAMSUNG_GREEN(
        title = "Galaxy Emerald",
        subtitle = "Vitality green, mint & sky blue",
        primary = SamsungGreen,
        secondary = Color(0xFF00E676),
        tertiary = Color(0xFF29B6F6),
    ),
    TRACKSIDE_BLAZE(
        title = "Trackside Blaze",
        subtitle = "Racing orange, warm gold & neon rose",
        primary = Color(0xFFFF5722),
        secondary = Color(0xFFFFC107),
        tertiary = Color(0xFFFF4081),
    ),
    CYBER_NEON(
        title = "Cyber Neon",
        subtitle = "Electric violet, neon cyan & coral",
        primary = AiPurple,
        secondary = Color(0xFF00E5FF),
        tertiary = PulseCoral,
    ),
    CARDIO_CRIMSON(
        title = "Cardio Crimson",
        subtitle = "High-intensity red, amber & deep violet",
        primary = Color(0xFFFF3B5C),
        secondary = Color(0xFFFF9800),
        tertiary = Color(0xFF8C52FF),
    ),
    COBALT_ENDURANCE(
        title = "Cobalt Endurance",
        subtitle = "Marathon blue, ocean teal & purple",
        primary = Color(0xFF3B82F6),
        secondary = Color(0xFF14B8A6),
        tertiary = Color(0xFFA855F7),
    ),
    CHAMPIONSHIP_GOLD(
        title = "Championship Gold",
        subtitle = "Podium amber, forest emerald & indigo",
        primary = Color(0xFFF59E0B),
        secondary = Color(0xFF10B981),
        tertiary = Color(0xFF6366F1),
    ),
    CUSTOM(
        title = "Custom Palette",
        subtitle = "Personalized primary, secondary & tertiary colors",
        primary = RunnerTeal,
        secondary = PulseCoral,
        tertiary = AiPurple,
    );

    companion object {
        val selectablePresets: List<ThemePreset> = entries.filter { it != CUSTOM }
    }
}

data class ThemeConfig(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val preset: ThemePreset = ThemePreset.RUNNER_TEAL,
    val amoledBlack: Boolean = false,
    val customPrimaryArgb: Int = RunnerTeal.toArgb(),
    val customSecondaryArgb: Int = PulseCoral.toArgb(),
    val customTertiaryArgb: Int = AiPurple.toArgb(),
) {
    val activePrimary: Color
        get() = if (preset == ThemePreset.CUSTOM) Color(customPrimaryArgb) else preset.primary

    val activeSecondary: Color
        get() = if (preset == ThemePreset.CUSTOM) Color(customSecondaryArgb) else preset.secondary

    val activeTertiary: Color
        get() = if (preset == ThemePreset.CUSTOM) Color(customTertiaryArgb) else preset.tertiary

    companion object {
        private const val PREFS = "ai_settings"
        private const val KEY_MODE = "theme_mode"
        private const val KEY_PRESET = "theme_preset"
        private const val KEY_AMOLED = "theme_amoled"
        private const val KEY_PRIMARY = "theme_custom_primary"
        private const val KEY_SECONDARY = "theme_custom_secondary"
        private const val KEY_TERTIARY = "theme_custom_tertiary"

        fun load(context: Context): ThemeConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mode = p.getString(KEY_MODE, null)?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrNull()
            } ?: ThemeMode.SYSTEM
            val preset = p.getString(KEY_PRESET, null)?.let {
                runCatching { ThemePreset.valueOf(it) }.getOrNull()
            } ?: ThemePreset.RUNNER_TEAL
            val amoled = p.getBoolean(KEY_AMOLED, false)
            val primary = p.getInt(KEY_PRIMARY, preset.primary.toArgb())
            val secondary = p.getInt(KEY_SECONDARY, preset.secondary.toArgb())
            val tertiary = p.getInt(KEY_TERTIARY, preset.tertiary.toArgb())
            return ThemeConfig(
                mode = mode,
                preset = preset,
                amoledBlack = amoled,
                customPrimaryArgb = primary,
                customSecondaryArgb = secondary,
                customTertiaryArgb = tertiary,
            )
        }

        fun save(context: Context, config: ThemeConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODE, config.mode.name)
                .putString(KEY_PRESET, config.preset.name)
                .putBoolean(KEY_AMOLED, config.amoledBlack)
                .putInt(KEY_PRIMARY, config.customPrimaryArgb)
                .putInt(KEY_SECONDARY, config.customSecondaryArgb)
                .putInt(KEY_TERTIARY, config.customTertiaryArgb)
                .apply()
        }

        fun toHex(color: Color): String {
            val argb = color.toArgb()
            return String.format("#%06X", 0xFFFFFF and argb)
        }

        fun parseHex(input: String): Color? {
            val clean = input.trim().removePrefix("#")
            if (clean.length != 6) return null
            val rgb = clean.toLongOrNull(16) ?: return null
            return Color((0xFF000000L or rgb).toInt())
        }
    }
}

private fun blend(a: Color, b: Color, ratio: Float): Color {
    val t = ratio.coerceIn(0f, 1f)
    return Color(
        red = a.red * (1f - t) + b.red * t,
        green = a.green * (1f - t) + b.green * t,
        blue = a.blue * (1f - t) + b.blue * t,
        alpha = 1f,
    )
}

private fun buildDarkColorScheme(config: ThemeConfig): ColorScheme {
    val p = config.activePrimary
    val s = config.activeSecondary
    val t = config.activeTertiary

    val bg = if (config.amoledBlack) Color(0xFF000000) else DarkBackground
    val surface = if (config.amoledBlack) Color(0xFF070A10) else DarkSurface
    val surfaceVariant = if (config.amoledBlack) Color(0xFF121824) else DarkSurfaceVariant
    val surfaceContainer = if (config.amoledBlack) Color(0xFF0C1018) else DarkSurfaceContainer
    val surfaceContainerHigh = if (config.amoledBlack) Color(0xFF161D2B) else DarkSurfaceContainerHigh
    val surfaceContainerHighest = if (config.amoledBlack) Color(0xFF20293C) else Color(0xFF2B364C)

    return darkColorScheme(
        primary = p,
        onPrimary = if (p.luminance() > 0.35f) blend(p, Color.Black, 0.82f) else Color.White,
        primaryContainer = blend(p, bg, 0.72f),
        onPrimaryContainer = blend(p, Color.White, 0.42f),
        secondary = s,
        onSecondary = if (s.luminance() > 0.35f) blend(s, Color.Black, 0.82f) else Color.White,
        secondaryContainer = blend(s, bg, 0.72f),
        onSecondaryContainer = blend(s, Color.White, 0.42f),
        tertiary = t,
        onTertiary = if (t.luminance() > 0.35f) blend(t, Color.Black, 0.82f) else Color.White,
        tertiaryContainer = blend(t, bg, 0.72f),
        onTertiaryContainer = blend(t, Color.White, 0.42f),
        background = bg,
        onBackground = DarkOnBackground,
        surface = surface,
        onSurface = DarkOnSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
    )
}

private fun buildLightColorScheme(config: ThemeConfig): ColorScheme {
    val rawP = config.activePrimary
    val rawS = config.activeSecondary
    val rawT = config.activeTertiary

    // Ensure enough contrast on white cards in light mode
    val p = if (rawP.luminance() > 0.45f) blend(rawP, Color.Black, 0.25f) else rawP
    val s = if (rawS.luminance() > 0.45f) blend(rawS, Color.Black, 0.22f) else rawS
    val t = if (rawT.luminance() > 0.45f) blend(rawT, Color.Black, 0.22f) else rawT

    return lightColorScheme(
        primary = p,
        onPrimary = Color.White,
        primaryContainer = blend(rawP, Color.White, 0.80f),
        onPrimaryContainer = blend(rawP, Color.Black, 0.68f),
        secondary = s,
        onSecondary = Color.White,
        secondaryContainer = blend(rawS, Color.White, 0.82f),
        onSecondaryContainer = blend(rawS, Color.Black, 0.68f),
        tertiary = t,
        onTertiary = Color.White,
        tertiaryContainer = blend(rawT, Color.White, 0.82f),
        onTertiaryContainer = blend(rawT, Color.Black, 0.68f),
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
}

@Composable
fun HealthAnalyzerTheme(
    themeConfig: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeConfig.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colorScheme = if (useDark) buildDarkColorScheme(themeConfig) else buildLightColorScheme(themeConfig)

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
