package io.github.yuubiz.folirel.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.testutil.TestEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 0.9.5: the presets (Classic, Leaf, Sand, Violet, Dynamic) draw ordinary text, article titles and the labels of the screens in neutral
 * greys, dark in light mode and white to light grey in dark mode, whatever the hue of the set. The hue stays in the accent roles.
 */
@RunWith(AndroidJUnit4::class)
class NeutralTextTest {
    private val presets = listOf(ColorPalette.CLASSIC, ColorPalette.LEAF, ColorPalette.SAND, ColorPalette.VIOLET, ColorPalette.DYNAMIC)

    private fun scheme(p: ColorPalette, dark: Boolean): ColorScheme = Palettes.scheme(p, dark, TestEnv.context())

    private fun assertNeutral(label: String, argb: Int, minL: Double = 0.0, maxL: Double = 100.0) {
        val lch = Lch.of(argb)
        assertTrue("$label has chroma ${lch.c} (not a neutral grey)", lch.c < 1.0)
        assertTrue("$label lightness ${lch.l} not in $minL..$maxL", lch.l in minL..maxL)
    }

    @Test
    fun everyPresetDrawsItsTextInNeutralGreysInLightAndDark() {
        for (p in presets) {
            val light = scheme(p, dark = false)
            assertNeutral("$p light onSurface", light.onSurface.toArgb(), maxL = 25.0)
            assertNeutral("$p light onBackground", light.onBackground.toArgb(), maxL = 25.0)
            assertNeutral("$p light onSurfaceVariant", light.onSurfaceVariant.toArgb(), maxL = 40.0)
            val dark = scheme(p, dark = true)
            assertNeutral("$p dark onSurface", dark.onSurface.toArgb(), minL = 85.0)
            assertNeutral("$p dark onBackground", dark.onBackground.toArgb(), minL = 85.0)
            assertNeutral("$p dark onSurfaceVariant", dark.onSurfaceVariant.toArgb(), minL = 70.0)
        }
    }

    /** Dynamic is the system's scheme with the same neutral text (Robolectric runs a system with dynamic colour). */
    @Test
    fun dynamicIsTheSystemSchemeWithNeutralText() {
        for (dark in listOf(false, true)) {
            val s = scheme(ColorPalette.DYNAMIC, dark)
            assertEquals(if (dark) Palettes.DARK_TEXT else Palettes.LIGHT_TEXT, s.onSurface)
            assertEquals(if (dark) Palettes.DARK_TEXT_VARIANT else Palettes.LIGHT_TEXT_VARIANT, s.onSurfaceVariant)
        }
    }

    /** The colour of the set is still where Material puts it: the accents differ between the sets, the text does not. */
    @Test
    fun theHueStaysInTheAccentsAndContainers() {
        for (dark in listOf(false, true)) {
            val fixed = listOf(ColorPalette.CLASSIC, ColorPalette.LEAF, ColorPalette.SAND, ColorPalette.VIOLET).map { scheme(it, dark) }
            assertEquals(1, fixed.map { it.onSurface }.toSet().size)
            assertEquals(fixed.size, fixed.map { it.primary }.toSet().size)
            assertEquals(fixed.size, fixed.map { it.primaryContainer }.toSet().size)
            for (s in fixed) {
                assertTrue(Lch.of(s.primary.toArgb()).c > 15.0)
                assertNotEquals(s.onSurface, s.onPrimaryContainer)
            }
        }
    }

    /** The scheme of a preset is one object: asked for again (every recomposition), it is the same. */
    @Test
    fun aPresetSchemeIsTheSameObjectEachTime() {
        for (p in presets - ColorPalette.DYNAMIC) for (dark in listOf(false, true)) {
            assertTrue("$p dark=$dark", Palettes.fixedScheme(p, dark) === Palettes.fixedScheme(p, dark))
        }
    }
}
