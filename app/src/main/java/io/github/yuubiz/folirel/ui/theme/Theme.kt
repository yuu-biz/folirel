package io.github.yuubiz.folirel.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.ThemeMode

/** Whether [themeMode] draws dark colours, given what the system currently asks for. */
fun isDarkTheme(themeMode: ThemeMode, systemDark: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * App theme. [textScale] multiplies (not adds to) the system font scale, so 200% system text stays honored. Light/dark
 * ([themeMode]) and the colour set ([palette]) are independent; a [palette] this system cannot draw (Dynamic before Android 12)
 * is drawn as Classic, the same rule the settings screen uses to say which one is selected.
 */
@Composable
fun FolirelTheme(themeMode: ThemeMode, textScale: Float, palette: ColorPalette = ColorPalette.CLASSIC, content: @Composable () -> Unit) {
    val dark = isDarkTheme(themeMode, isSystemInDarkTheme())
    val density = LocalDensity.current
    val context = LocalContext.current
    val drawn = palette.effective(Build.VERSION.SDK_INT)
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
        MaterialTheme(colorScheme = Palettes.scheme(drawn, dark, context), typography = Typography(), content = content)
    }
}
