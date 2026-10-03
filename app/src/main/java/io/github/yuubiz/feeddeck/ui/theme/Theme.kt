package io.github.yuubiz.feeddeck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.yuubiz.feeddeck.data.preferences.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF1F4E79),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F7),
    onPrimaryContainer = Color(0xFF0B2239),
    secondary = Color(0xFF52606D),
    surface = Color(0xFFFBFCFE),
    surfaceVariant = Color(0xFFE3E7EC),
    onSurfaceVariant = Color(0xFF424A53),
    outlineVariant = Color(0xFFC9CED4),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA2C9F0),
    onPrimary = Color(0xFF0B2239),
    primaryContainer = Color(0xFF1F4E79),
    onPrimaryContainer = Color(0xFFD3E4F7),
    secondary = Color(0xFFB9C3CE),
    surface = Color(0xFF111418),
    surfaceVariant = Color(0xFF2A3036),
    onSurfaceVariant = Color(0xFFC3C9D0),
    outlineVariant = Color(0xFF3E454C),
    error = Color(0xFFF2B8B5),
)

/**
 * App theme. [textScale] multiplies (not adds to) the system font scale, so 200% system text stays honored.
 */
@Composable
fun FeedDeckTheme(themeMode: ThemeMode, textScale: Float, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
    }
}
