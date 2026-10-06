package io.github.yuubiz.folirel.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.ui.theme.Palettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The brand logo keeps its own colours; only the light or dark variant follows the surface it is drawn on. */
class BrandLogoTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    // The leaf and lettering colours of artwork/folirel/folirel-logo.svg and folirel-logo-dark.svg.
    private val logoOnLight = Color(0xFF146A3F)
    private val logoOnDark = Color(0xFF2E9A62)

    @Test
    fun theVariantFollowsTheSurfaceOfEveryColourSetInBothModes() {
        for (palette in ColorPalette.entries - ColorPalette.DYNAMIC) {
            val light = Palettes.fixedScheme(palette, dark = false).surface
            val dark = Palettes.fixedScheme(palette, dark = true).surface
            assertEquals("$palette light", false, brandLogoForDarkSurface(light))
            assertEquals("$palette dark", true, brandLogoForDarkSurface(dark))
        }
    }

    /** A graphical object needs 3:1 against its background (WCAG 1.4.11); the variant is chosen so that it has it. */
    @Test
    fun theChosenVariantHasContrastOnEverySurface() {
        for (palette in ColorPalette.entries - ColorPalette.DYNAMIC) {
            for (darkMode in listOf(false, true)) {
                val surface = Palettes.fixedScheme(palette, darkMode).surface
                val logo = if (brandLogoForDarkSurface(surface)) logoOnDark else logoOnLight
                assertTrue("$palette dark=$darkMode: ${contrast(logo, surface)}", contrast(logo, surface) >= 3f)
            }
        }
    }
}
