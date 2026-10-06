package io.github.yuubiz.folirel.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3C-T18..T22 (0.9.3): the Custom scheme made from one seed. Light and dark are both generated, both are complete (not a primary dropped
 * into a baseline), and every text, icon and control colour reaches its contrast whatever the seed is: the user never chooses those.
 */
class CustomPaletteTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    /** Seeds that are hard on a generator: no colour at all, extreme lightness, extreme saturation, and every hue at several strengths. */
    private val hardSeeds: List<Pair<String, Int>> = listOf(
        "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "grey" to 0xFF808080.toInt(), "dark grey" to 0xFF202020.toInt(),
        "light grey" to 0xFFE0E0E0.toInt(), "bright yellow" to 0xFFFFFF00.toInt(), "saturated red" to 0xFFFF0000.toInt(),
        "saturated green" to 0xFF00FF00.toInt(), "saturated blue" to 0xFF0000FF.toInt(), "dark navy" to 0xFF000080.toInt(),
        "cyan" to 0xFF00FFFF.toInt(), "magenta" to 0xFFFF00FF.toInt(), "pale yellow" to 0xFFFFFFCC.toInt(), "near black blue" to 0xFF000010.toInt(),
        "default" to CustomPalette.DEFAULT_SEED,
    )

    private val sweep: List<Pair<String, Int>> = (0 until 24).flatMap { i ->
        listOf(0.15f to 0.3f, 0.5f to 0.5f, 0.9f to 0.9f, 1f to 1f, 1f to 0.2f, 0.3f to 1f).map { (s, v) ->
            "h${i * 15} s$s v$v" to SeedColor.fromHsv(i * 15f, s, v)
        }
    }

    private val allSeeds = hardSeeds + sweep

    /** Every colour role of a scheme (ColorScheme has no value equality of its own). */
    private fun roles(s: ColorScheme): List<Int> = listOf(
        s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer, s.inversePrimary, s.secondary, s.onSecondary, s.secondaryContainer,
        s.onSecondaryContainer, s.tertiary, s.onTertiary, s.tertiaryContainer, s.onTertiaryContainer, s.background, s.onBackground, s.surface,
        s.onSurface, s.surfaceVariant, s.onSurfaceVariant, s.surfaceTint, s.inverseSurface, s.inverseOnSurface, s.error, s.onError,
        s.errorContainer, s.onErrorContainer, s.outline, s.outlineVariant, s.scrim, s.surfaceBright, s.surfaceDim, s.surfaceContainer,
        s.surfaceContainerHigh, s.surfaceContainerHighest, s.surfaceContainerLow, s.surfaceContainerLowest,
    ).map { it.toArgb() }

    private fun check(name: String, fg: Color, bg: Color, min: Float) =
        assertTrue("$name ${contrast(fg, bg)} < $min", contrast(fg, bg) >= min)

    private fun assertReadable(name: String, s: ColorScheme, dark: Boolean) {
        check("$name onPrimary/primary", s.onPrimary, s.primary, 4.5f)
        check("$name onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer, 4.5f)
        check("$name onSecondary/secondary", s.onSecondary, s.secondary, 4.5f)
        check("$name onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 4.5f)
        check("$name onTertiary/tertiary", s.onTertiary, s.tertiary, 4.5f)
        check("$name onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, 4.5f)
        check("$name onError/error", s.onError, s.error, 4.5f)
        check("$name onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer, 4.5f)
        check("$name onSurface/surface", s.onSurface, s.surface, 7f)
        check("$name onBackground/background", s.onBackground, s.background, 7f)
        check("$name onSurface/surfaceContainerHighest", s.onSurface, s.surfaceContainerHighest, 7f)
        check("$name onSurfaceVariant/surface", s.onSurfaceVariant, s.surface, 4.5f)
        check("$name onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant, 4.5f)
        check("$name onSurfaceVariant/surfaceContainerHighest", s.onSurfaceVariant, s.surfaceContainerHighest, 4.5f)
        // The bars: title and icons on primaryContainer (also the tab strip, whose text is onPrimaryContainer).
        check("$name onSurfaceVariant/primaryContainer (icons on the bar)", s.onSurfaceVariant, s.primaryContainer, 3f)
        check("$name onSurface/primaryContainer", s.onSurface, s.primaryContainer, 4.5f)
        // Accents used as text or icon colour on the page and on cards.
        check("$name primary/surface", s.primary, s.surface, 4.5f)
        check("$name primary/surfaceContainerHighest", s.primary, s.surfaceContainerHighest, 4.5f)
        check("$name secondary/surface", s.secondary, s.surface, 4.5f)
        check("$name tertiary/surface", s.tertiary, s.surface, 4.5f)
        check("$name tertiary/surfaceContainerHigh (dialogs)", s.tertiary, s.surfaceContainerHigh, 4.5f)
        check("$name error/surface", s.error, s.surface, 4.5f)
        check("$name outline/surface", s.outline, s.surface, 3f)
        check("$name primary/surface as a border", s.primary, s.surface, 3f)
        check("$name inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface, 4.5f)
        // The page is light in light mode and dark in dark mode, whatever the seed; the bar can be told from the page.
        if (dark) assertTrue("$name dark surface", s.surface.luminance() < 0.1f) else assertTrue("$name light surface", s.surface.luminance() > 0.7f)
        check("$name bar/page", s.primaryContainer, s.surface, 1.1f)
        check("$name card/page", s.surfaceContainerLow, s.surface, 1.04f)
    }

    @Test
    fun everySeedGivesReadableLightAndDarkSchemes() {
        for ((name, seed) in allSeeds) {
            assertReadable("$name light", CustomPalette.scheme(seed, dark = false), dark = false)
            assertReadable("$name dark", CustomPalette.scheme(seed, dark = true), dark = true)
        }
    }

    @Test
    fun blackSeedGivesAReadableNeutralScheme() {
        val black = 0xFF000000.toInt()
        for (dark in listOf(false, true)) {
            val s = CustomPalette.scheme(black, dark)
            assertReadable("black dark=$dark", s, dark)
            // No colour was asked for, so none is invented.
            val c = s.primary
            assertTrue(maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) < 0.02f)
        }
    }

    @Test
    fun whiteSeedGivesAReadableNeutralScheme() {
        for (dark in listOf(false, true)) assertReadable("white dark=$dark", CustomPalette.scheme(0xFFFFFFFF.toInt(), dark), dark)
        assertNotEquals("light and dark are different sets", CustomPalette.scheme(0xFFFFFFFF.toInt(), false).surface, CustomPalette.scheme(0xFFFFFFFF.toInt(), true).surface)
    }

    @Test
    fun brightAndSaturatedSeedsStayReadable() {
        for (seed in listOf(0xFFFFFF00.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF000080.toInt(), 0xFFFF00FF.toInt())) for (dark in listOf(false, true)) {
            assertReadable("%08X dark=%s".format(seed, dark), CustomPalette.scheme(seed, dark), dark)
        }
    }

    @Test
    fun theSchemeIsCompleteAndFollowsTheSeedNotJustThePrimary() {
        val red = 0xFFD32F2F.toInt()
        val blue = 0xFF1976D2.toInt()
        for (dark in listOf(false, true)) {
            val a = CustomPalette.scheme(red, dark)
            val b = CustomPalette.scheme(blue, dark)
            // Every family differs between two seeds: accents, containers, surfaces, outlines, bars.
            assertNotEquals(a.primary, b.primary)
            assertNotEquals(a.primaryContainer, b.primaryContainer)
            assertNotEquals(a.secondary, b.secondary)
            assertNotEquals(a.tertiary, b.tertiary)
            assertNotEquals(a.surface, b.surface)
            assertNotEquals(a.surfaceVariant, b.surfaceVariant)
            assertNotEquals(a.surfaceContainerHighest, b.surfaceContainerHighest)
            assertNotEquals(a.outline, b.outline)
            assertNotEquals(a.inversePrimary, b.inversePrimary)
            // The background is the surface (one page colour), and nothing is the Material baseline lilac.
            assertEquals(a.surface, a.background)
            val baseline = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
            assertNotEquals(baseline.surfaceContainerHighest, a.surfaceContainerHighest)
            assertNotEquals(baseline.outline, a.outline)
            assertNotEquals(baseline.inverseSurface, a.inverseSurface)
        }
    }

    @Test
    fun lightAndDarkComeFromTheSameSeedAndShareItsHue() {
        for (seed in listOf(0xFFD32F2F.toInt(), 0xFF1976D2.toInt(), 0xFF2E7D32.toInt(), CustomPalette.DEFAULT_SEED)) {
            val hue = Lch.of(seed).h
            val light = Lch.of(CustomPalette.scheme(seed, false).primaryContainer.toArgb())
            val dark = Lch.of(CustomPalette.scheme(seed, true).primaryContainer.toArgb())
            fun gap(a: Double, b: Double) = minOf(Math.abs(a - b), 360 - Math.abs(a - b))
            assertTrue("light container hue ${light.h} vs seed $hue", gap(light.h, hue) < 12)
            assertTrue("dark container hue ${dark.h} vs seed $hue", gap(dark.h, hue) < 12)
        }
    }

    @Test
    fun theSameSeedAlwaysGivesTheSameColoursAndAlphaIsIgnored() {
        val seed = 0xFF8E24AA.toInt()
        assertEquals(roles(CustomPalette.scheme(seed, false)), roles(CustomPalette.scheme(seed, false)))
        assertEquals(roles(CustomPalette.scheme(seed, true)), roles(CustomPalette.scheme(seed, true)))
        assertEquals(roles(CustomPalette.scheme(seed, true)), roles(CustomPalette.scheme(0x228E24AA, true)))
    }

    @Test
    fun theDefaultSeedIsNotClassicBlue() {
        // Custom is chosen to look different from the blue everybody already has.
        val classic = Palettes.fixedScheme(io.github.yuubiz.folirel.data.preferences.ColorPalette.CLASSIC, false)
        assertNotEquals(classic.primary, CustomPalette.scheme(CustomPalette.DEFAULT_SEED, false).primary)
    }

    @Test
    fun lchRoundTripsTheSeedsItIsGiven() {
        for ((name, seed) in hardSeeds) {
            val v = Lch.of(seed)
            // At its own lightness, hue and chroma a colour that is inside sRGB comes back as itself.
            val back = Lch.argb(v.l, v.c, v.h)
            for (shift in listOf(16, 8, 0)) {
                val a = (seed shr shift) and 0xFF
                val b = (back shr shift) and 0xFF
                assertTrue("$name channel@$shift $a vs $b", Math.abs(a - b) <= 2)
            }
        }
    }

    // --- the seed as text and as a pointer position ---

    @Test
    fun hexIsParsedOnlyWhenItIsSixDigits() {
        assertEquals(0xFF009688.toInt(), SeedColor.parse("#009688"))
        assertEquals(0xFF009688.toInt(), SeedColor.parse("009688"))
        assertEquals(0xFF009688.toInt(), SeedColor.parse("  #009688 "))
        assertEquals(0xFFABCDEF.toInt(), SeedColor.parse("#abcdef"))
        for (bad in listOf("", "#", "#12345", "#1234567", "#GGGGGG", "12 34 56", "#00968", "red", "#12345G", "##123456", "0x123456", "#FF009688", "-12345", "#１２３４５６")) {
            assertNull("'$bad'", SeedColor.parse(bad))
        }
    }

    @Test
    fun hexIsFormattedUpperCaseWithoutAlpha() {
        assertEquals("#009688", SeedColor.hex(0xFF009688.toInt()))
        assertEquals("#00ABCD".uppercase(), SeedColor.hex(0x7700ABCD))
        assertEquals("#000000", SeedColor.hex(0xFF000000.toInt()))
        for ((_, seed) in hardSeeds) assertEquals(seed or 0xFF000000.toInt(), SeedColor.parse(SeedColor.hex(seed)))
    }

    @Test
    fun hsvRoundTripsAndClamps() {
        for ((name, seed) in hardSeeds + sweep) {
            val h = SeedColor.hsv(seed)
            val back = SeedColor.fromHsv(h.hue, h.saturation, h.value)
            for (shift in listOf(16, 8, 0)) assertTrue("$name", Math.abs(((seed shr shift) and 0xFF) - ((back shr shift) and 0xFF)) <= 1)
        }
        assertEquals(0xFF000000.toInt(), SeedColor.fromHsv(123f, 0.5f, 0f))
        assertEquals(0xFFFFFFFF.toInt(), SeedColor.fromHsv(0f, 0f, 1f))
        assertEquals(SeedColor.fromHsv(0f, 1f, 1f), SeedColor.fromHsv(360f, 1f, 1f))
        assertEquals(SeedColor.fromHsv(359f, 1f, 1f), SeedColor.fromHsv(-1f, 1f, 1f))
        assertEquals(0xFFFF0000.toInt(), SeedColor.fromHsv(0f, 5f, 5f))
    }
}
