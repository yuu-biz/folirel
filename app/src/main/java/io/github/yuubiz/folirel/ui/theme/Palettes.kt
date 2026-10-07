package io.github.yuubiz.folirel.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.CustomColors

/**
 * The colour sets. Each fixed palette has a light and a dark [ColorScheme] defined as one coherent set (primary, secondary and
 * tertiary with their containers, surface and its variants, outline, error), not a primary swapped into the baseline; the
 * contrast of every text/background pair is checked by `PalettesTest`.
 *
 * [ColorPalette.CLASSIC] is the definition used up to 0.6.0 (the unset roles are Material's baseline) except for the text roles: since
 * 0.9.5 every preset draws its text in neutral greys ([neutralText]). Photos and icons are never tinted by any of these colours.
 */
object Palettes {
    val classicLight: ColorScheme = lightColorScheme(
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

    val classicDark: ColorScheme = darkColorScheme(
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

    private val leafLight = fixed(
        dark = false,
        primary = 0xFF2E6B3E, onPrimary = 0xFFFFFFFF, primaryContainer = 0xFFCDEBD0, onPrimaryContainer = 0xFF0A2B14,
        secondary = 0xFF4F6352, onSecondary = 0xFFFFFFFF, secondaryContainer = 0xFFD2E8D4, onSecondaryContainer = 0xFF0E1F11,
        tertiary = 0xFF38656F, onTertiary = 0xFFFFFFFF, tertiaryContainer = 0xFFCBE8F0, onTertiaryContainer = 0xFF001F26,
        surface = 0xFFF0F7EC, onSurface = 0xFF191D19, surfaceVariant = 0xFFD3E3D0, onSurfaceVariant = 0xFF404941,
        outline = 0xFF6B756C, outlineVariant = 0xFFC0C9BF, error = 0xFFB3261E, inversePrimary = 0xFF9FD6A9,
    )
    private val leafDark = fixed(
        dark = true,
        primary = 0xFF9FD6A9, onPrimary = 0xFF06381A, primaryContainer = 0xFF245230, onPrimaryContainer = 0xFFBAF2C3,
        secondary = 0xFFB6CCB8, onSecondary = 0xFF213524, secondaryContainer = 0xFF384B3B, onSecondaryContainer = 0xFFD2E8D4,
        tertiary = 0xFFA1CED9, onTertiary = 0xFF023640, tertiaryContainer = 0xFF204C57, onTertiaryContainer = 0xFFCBE8F0,
        surface = 0xFF0E1B12, onSurface = 0xFFE0E4DD, surfaceVariant = 0xFF2A3E2E, onSurfaceVariant = 0xFFC0C9BF,
        outline = 0xFF8A938A, outlineVariant = 0xFF404941, error = 0xFFF2B8B5, inversePrimary = 0xFF2E6B3E,
    )
    private val sandLight = fixed(
        dark = false,
        primary = 0xFF8A4F00, onPrimary = 0xFFFFFFFF, primaryContainer = 0xFFFFDDB8, onPrimaryContainer = 0xFF2C1700,
        secondary = 0xFF6F5B40, onSecondary = 0xFFFFFFFF, secondaryContainer = 0xFFF7E0C0, onSecondaryContainer = 0xFF261A06,
        tertiary = 0xFF5A6334, onTertiary = 0xFFFFFFFF, tertiaryContainer = 0xFFDEE8AD, onTertiaryContainer = 0xFF181E00,
        surface = 0xFFFBF3E6, onSurface = 0xFF201B14, surfaceVariant = 0xFFEAD8BF, onSurfaceVariant = 0xFF4E4538,
        outline = 0xFF7A6E5E, outlineVariant = 0xFFD4C4B0, error = 0xFFB3261E, inversePrimary = 0xFFFFB86B,
    )
    private val sandDark = fixed(
        dark = true,
        primary = 0xFFFFB86B, onPrimary = 0xFF4A2900, primaryContainer = 0xFF6A3C00, onPrimaryContainer = 0xFFFFDDB8,
        secondary = 0xFFDBC3A2, onSecondary = 0xFF3D2E16, secondaryContainer = 0xFF56452B, onSecondaryContainer = 0xFFF7E0C0,
        tertiary = 0xFFC2CC93, onTertiary = 0xFF2D3408, tertiaryContainer = 0xFF424B1D, onTertiaryContainer = 0xFFDEE8AD,
        surface = 0xFF1C150A, onSurface = 0xFFEBE1D5, surfaceVariant = 0xFF45371F, onSurfaceVariant = 0xFFD4C4B0,
        outline = 0xFF9C8F7D, outlineVariant = 0xFF4E4538, error = 0xFFF2B8B5, inversePrimary = 0xFF8A4F00,
    )
    private val violetLight = fixed(
        dark = false,
        primary = 0xFF6A45B5, onPrimary = 0xFFFFFFFF, primaryContainer = 0xFFE6DCFF, onPrimaryContainer = 0xFF22005C,
        secondary = 0xFF625B71, onSecondary = 0xFFFFFFFF, secondaryContainer = 0xFFE8DEF8, onSecondaryContainer = 0xFF1D192B,
        tertiary = 0xFF7D4D63, onTertiary = 0xFFFFFFFF, tertiaryContainer = 0xFFFFD8E8, onTertiaryContainer = 0xFF31101F,
        surface = 0xFFF6F0FE, onSurface = 0xFF1C1B20, surfaceVariant = 0xFFE0D6EE, onSurfaceVariant = 0xFF48454E,
        outline = 0xFF78747F, outlineVariant = 0xFFCAC4D0, error = 0xFFB3261E, inversePrimary = 0xFFCFBCFF,
    )
    private val violetDark = fixed(
        dark = true,
        primary = 0xFFCFBCFF, onPrimary = 0xFF38128B, primaryContainer = 0xFF503391, onPrimaryContainer = 0xFFE6DCFF,
        secondary = 0xFFCCC2DC, onSecondary = 0xFF332D41, secondaryContainer = 0xFF4A4458, onSecondaryContainer = 0xFFE8DEF8,
        tertiary = 0xFFEFB8CE, onTertiary = 0xFF4A2538, tertiaryContainer = 0xFF633B50, onTertiaryContainer = 0xFFFFD8E8,
        surface = 0xFF171222, onSurface = 0xFFE6E0E9, surfaceVariant = 0xFF3B3350, onSurfaceVariant = 0xFFCAC4D0,
        outline = 0xFF948F99, outlineVariant = 0xFF48454E, error = 0xFFF2B8B5, inversePrimary = 0xFF6A45B5,
    )

    /** The scheme of a palette that does not depend on the device: everything except [ColorPalette.DYNAMIC]. */
    fun fixedScheme(palette: ColorPalette, dark: Boolean): ColorScheme = when (palette) {
        ColorPalette.CLASSIC, ColorPalette.DYNAMIC -> if (dark) drawn.classicDark else drawn.classicLight
        ColorPalette.LEAF -> if (dark) drawn.leafDark else drawn.leafLight
        ColorPalette.SAND -> if (dark) drawn.sandDark else drawn.sandLight
        ColorPalette.VIOLET -> if (dark) drawn.violetDark else drawn.violetLight
        // Custom needs the user's seed; asked for without one it is made from the default seed.
        ColorPalette.CUSTOM -> CustomPalette.scheme(CustomPalette.DEFAULT_SEED, dark)
    }

    /** The presets as drawn, with [neutralText], made once (a scheme is compared by identity and asked for at every recomposition). */
    private val drawn by lazy {
        object {
            val classicLight = neutralText(Palettes.classicLight, false)
            val classicDark = neutralText(Palettes.classicDark, true)
            val leafLight = neutralText(Palettes.leafLight, false)
            val leafDark = neutralText(Palettes.leafDark, true)
            val sandLight = neutralText(Palettes.sandLight, false)
            val sandDark = neutralText(Palettes.sandDark, true)
            val violetLight = neutralText(Palettes.violetLight, false)
            val violetDark = neutralText(Palettes.violetDark, true)
        }
    }

    /**
     * The scheme to draw. [palette] should already be the effective one ([ColorPalette.effective]): Dynamic is read from the system
     * wallpaper colours and exists only on Android 12 and later; asked for on an older system it is Classic. [customColors] are the
     * colours the reader set by hand for this mode of the Custom set; null (none set) makes it from [customSeed].
     */
    fun scheme(
        palette: ColorPalette,
        dark: Boolean,
        context: Context,
        customSeed: Int = CustomPalette.DEFAULT_SEED,
        customColors: CustomColors? = null,
    ): ColorScheme =
        when {
            palette == ColorPalette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> neutralText(dynamic(dark, context), dark)
            palette == ColorPalette.CUSTOM -> CustomPalette.scheme(customSeed, dark, customColors)
            else -> fixedScheme(palette, dark)
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun dynamic(dark: Boolean, context: Context): ColorScheme =
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    /** Body text, titles and labels of the preset colour sets (0.9.5): neutral greys, not the hue of the set. */
    val LIGHT_TEXT = Color(0xFF1B1B1B)
    val LIGHT_TEXT_VARIANT = Color(0xFF474747)
    val DARK_TEXT = Color(0xFFE8E8E8)
    val DARK_TEXT_VARIANT = Color(0xFFC4C4C4)

    /**
     * The text roles of a preset made neutral (0.9.5): ordinary text, article titles and the labels of the screens are drawn in
     * [ColorScheme.onSurface] / [ColorScheme.onBackground] and secondary text in [ColorScheme.onSurfaceVariant]; in the sets these
     * leaned towards the set's hue, which showed most in dark mode. The colour of the set stays where Material puts it: selection,
     * underlines, buttons and chips, icon accents, containers and the content drawn on them (`on*Container`).
     */
    internal fun neutralText(scheme: ColorScheme, dark: Boolean): ColorScheme = scheme.copy(
        onSurface = if (dark) DARK_TEXT else LIGHT_TEXT,
        onBackground = if (dark) DARK_TEXT else LIGHT_TEXT,
        onSurfaceVariant = if (dark) DARK_TEXT_VARIANT else LIGHT_TEXT_VARIANT,
    )

    /** Surface containers are the surface tinted towards the primary, so they stay in the hue of the palette. */
    @Suppress("LongParameterList")
    private fun fixed(
        dark: Boolean,
        primary: Long, onPrimary: Long, primaryContainer: Long, onPrimaryContainer: Long,
        secondary: Long, onSecondary: Long, secondaryContainer: Long, onSecondaryContainer: Long,
        tertiary: Long, onTertiary: Long, tertiaryContainer: Long, onTertiaryContainer: Long,
        surface: Long, onSurface: Long, surfaceVariant: Long, onSurfaceVariant: Long,
        outline: Long, outlineVariant: Long, error: Long, inversePrimary: Long,
    ): ColorScheme {
        val s = Color(surface)
        val p = Color(primary)
        fun tint(share: Float) = lerp(s, p, share)
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = p, onPrimary = Color(onPrimary), primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
            inversePrimary = Color(inversePrimary),
            secondary = Color(secondary), onSecondary = Color(onSecondary), secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
            tertiary = Color(tertiary), onTertiary = Color(onTertiary), tertiaryContainer = Color(tertiaryContainer), onTertiaryContainer = Color(onTertiaryContainer),
            background = s, onBackground = Color(onSurface),
            surface = s, onSurface = Color(onSurface), surfaceVariant = Color(surfaceVariant), onSurfaceVariant = Color(onSurfaceVariant),
            surfaceTint = p,
            inverseSurface = Color(onSurface), inverseOnSurface = s,
            outline = Color(outline), outlineVariant = Color(outlineVariant),
            error = Color(error),
            // Not a Material role the palette defines, but it must follow the hue: the error container of the baseline is pink.
            errorContainer = if (dark) Color(0xFF8C1D18) else Color(0xFFF9DEDC),
            onErrorContainer = if (dark) Color(0xFFF9DEDC) else Color(0xFF410E0B),
            onError = if (dark) Color(0xFF601410) else Color.White,
            scrim = Color.Black,
            surfaceDim = if (dark) lerp(s, Color.Black, 0.35f) else lerp(s, Color(surfaceVariant), 0.7f),
            surfaceBright = if (dark) tint(0.14f) else s,
            surfaceContainerLowest = if (dark) lerp(s, Color.Black, 0.4f) else Color.White,
            surfaceContainerLow = tint(if (dark) 0.09f else 0.06f),
            surfaceContainer = tint(if (dark) 0.12f else 0.09f),
            surfaceContainerHigh = tint(if (dark) 0.16f else 0.13f),
            surfaceContainerHighest = tint(if (dark) 0.20f else 0.17f),
        )
    }
}
