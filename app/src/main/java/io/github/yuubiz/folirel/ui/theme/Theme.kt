package io.github.yuubiz.folirel.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.CustomColors
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
fun FolirelTheme(
    themeMode: ThemeMode,
    textScale: Float,
    palette: ColorPalette = ColorPalette.CLASSIC,
    customSeed: Int = CustomPalette.DEFAULT_SEED,
    customLight: CustomColors? = null,
    customDark: CustomColors? = null,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode, isSystemInDarkTheme())
    val density = LocalDensity.current
    val context = LocalContext.current
    val drawn = palette.effective(Build.VERSION.SDK_INT)
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
        CompositionLocalProvider(LocalTintedBars provides usesTintedBars(drawn)) {
            MaterialTheme(colorScheme = appColorScheme(context, drawn, dark, customSeed, customLight, customDark), typography = Typography(), content = content)
        }
    }
}

/** The colours [FolirelTheme] draws with; also what the window behind the composition is painted with (see MainActivity). */
fun appColorScheme(
    context: Context,
    palette: ColorPalette,
    dark: Boolean,
    customSeed: Int = CustomPalette.DEFAULT_SEED,
    customLight: CustomColors? = null,
    customDark: CustomColors? = null,
): ColorScheme = Palettes.scheme(palette.effective(Build.VERSION.SDK_INT), dark, context, customSeed, if (dark) customDark else customLight)
