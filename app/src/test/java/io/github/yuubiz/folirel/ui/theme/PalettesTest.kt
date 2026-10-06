package io.github.yuubiz.folirel.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-A01 and P2-T12: every palette is a coherent light/dark pair, and Classic is exactly what 0.6.0 drew. */
class PalettesTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    private val fixedPalettes = ColorPalette.entries - ColorPalette.DYNAMIC

    /** The colours of 0.6.0, written out. An update must not change how an existing install looks (P2-A02, P2-T01, P2-T02). */
    @Test
    fun classicIsExactlyTheSetOf060() {
        val light = Palettes.fixedScheme(ColorPalette.CLASSIC, dark = false)
        assertEquals(Color(0xFF1F4E79), light.primary)
        assertEquals(Color.White, light.onPrimary)
        assertEquals(Color(0xFFD3E4F7), light.primaryContainer)
        assertEquals(Color(0xFF0B2239), light.onPrimaryContainer)
        assertEquals(Color(0xFF52606D), light.secondary)
        assertEquals(Color(0xFFFBFCFE), light.surface)
        assertEquals(Color(0xFFE3E7EC), light.surfaceVariant)
        assertEquals(Color(0xFF424A53), light.onSurfaceVariant)
        assertEquals(Color(0xFFC9CED4), light.outlineVariant)
        assertEquals(Color(0xFFB3261E), light.error)
        val dark = Palettes.fixedScheme(ColorPalette.CLASSIC, dark = true)
        assertEquals(Color(0xFFA2C9F0), dark.primary)
        assertEquals(Color(0xFF0B2239), dark.onPrimary)
        assertEquals(Color(0xFF1F4E79), dark.primaryContainer)
        assertEquals(Color(0xFFD3E4F7), dark.onPrimaryContainer)
        assertEquals(Color(0xFFB9C3CE), dark.secondary)
        assertEquals(Color(0xFF111418), dark.surface)
        assertEquals(Color(0xFF2A3036), dark.surfaceVariant)
        assertEquals(Color(0xFFC3C9D0), dark.onSurfaceVariant)
        assertEquals(Color(0xFF3E454C), dark.outlineVariant)
        assertEquals(Color(0xFFF2B8B5), dark.error)
        // Every role not listed above is Material's baseline, as it was in 0.6.0 (the builders are given only the roles above).
        assertEquals(lightColorScheme().onSurface, light.onSurface)
        assertEquals(lightColorScheme().outline, light.outline)
        assertEquals(lightColorScheme().surfaceContainer, light.surfaceContainer)
        assertEquals(darkColorScheme().onSurface, dark.onSurface)
        assertEquals(darkColorScheme().outline, dark.outline)
        assertEquals(darkColorScheme().surfaceContainer, dark.surfaceContainer)
    }

    @Test
    fun everyPaletteHasALightAndADarkSetThatDiffer() {
        for (p in fixedPalettes) {
            val l = Palettes.fixedScheme(p, dark = false)
            val d = Palettes.fixedScheme(p, dark = true)
            assertTrue("$p light surface is light", l.surface.luminance() > 0.7f)
            assertTrue("$p dark surface is dark", d.surface.luminance() < 0.1f)
            assertNotEquals(l.primary, d.primary)
        }
        val primaries = fixedPalettes.map { Palettes.fixedScheme(it, false).primary }
        assertEquals("each palette has its own primary", primaries.size, primaries.toSet().size)
    }

    /** Text on its background and the accent used as text or icon colour reach WCAG AA; outlines reach the 3:1 of non-text contrast. */
    @Test
    fun colourPairsKeepTheirContrast() {
        for (p in fixedPalettes - ColorPalette.CLASSIC) for (dark in listOf(false, true)) {
            val s = Palettes.fixedScheme(p, dark)
            val name = "$p ${if (dark) "dark" else "light"}"
            fun check(label: String, fg: Color, bg: Color, min: Float = 4.5f) =
                assertTrue("$name $label ${contrast(fg, bg)} < $min", contrast(fg, bg) >= min)
            check("onPrimary/primary", s.onPrimary, s.primary)
            check("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer)
            check("onSecondary/secondary", s.onSecondary, s.secondary)
            check("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer)
            check("onTertiary/tertiary", s.onTertiary, s.tertiary)
            check("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer)
            check("onSurface/surface", s.onSurface, s.surface, 7f)
            check("onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
            check("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant)
            check("onSurface/surfaceVariant", s.onSurface, s.surfaceVariant)
            check("onSurface/surfaceContainerHighest", s.onSurface, s.surfaceContainerHighest)
            check("onSurfaceVariant/surfaceContainerHighest", s.onSurfaceVariant, s.surfaceContainerHighest)
            check("primary/surface (titles, active icons)", s.primary, s.surface)
            check("primary/surfaceContainerHighest", s.primary, s.surfaceContainerHighest)
            check("secondary/surface", s.secondary, s.surface)
            check("error/surface", s.error, s.surface)
            check("outline/surface (borders of controls)", s.outline, s.surface, 3f)
            check("primary/surface as a border", s.primary, s.surface, 3f)
        }
    }

    /** Classic is not changed (that is its point); it is only checked for the pairs the app draws text with. */
    @Test
    fun classicTextPairsAreReadable() {
        for (dark in listOf(false, true)) {
            val s = Palettes.fixedScheme(ColorPalette.CLASSIC, dark)
            assertTrue(contrast(s.onPrimary, s.primary) >= 4.5f)
            assertTrue(contrast(s.onPrimaryContainer, s.primaryContainer) >= 4.5f)
            assertTrue(contrast(s.onSurface, s.surface) >= 7f)
            assertTrue(contrast(s.onSurfaceVariant, s.surfaceVariant) >= 4.5f)
            assertTrue(contrast(s.primary, s.surface) >= 4.5f)
        }
    }

    /** Surface containers follow the palette's hue instead of Material's baseline lilac, which would show in sheets and cards. */
    @Test
    fun surfaceContainersOfTheNewPalettesFollowThePalette() {
        for (p in fixedPalettes - ColorPalette.CLASSIC) for (dark in listOf(false, true)) {
            val s: ColorScheme = Palettes.fixedScheme(p, dark)
            val baseline = if (dark) darkColorScheme() else lightColorScheme()
            assertNotEquals("$p $dark surfaceContainerHighest", baseline.surfaceContainerHighest, s.surfaceContainerHighest)
            assertEquals(s.surface, s.background)
        }
    }
}
