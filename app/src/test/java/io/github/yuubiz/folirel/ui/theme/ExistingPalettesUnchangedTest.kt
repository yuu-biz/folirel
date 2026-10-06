package io.github.yuubiz.folirel.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3C-T28, T29, T30 (0.9.3): adding Custom does not change a colour of the sets that exist. The values are those of 0.9.2 (the sets as
 * they were drawn after the 0.9.2 colour work), written out; Classic is also pinned exactly by `PalettesTest.classicIsExactlyTheSetOf060`.
 */
@RunWith(AndroidJUnit4::class)
class ExistingPalettesUnchangedTest {
    private fun hex(c: Color) = "0x%08X".format(c.toArgb())

    private class Golden(
        val palette: ColorPalette, val dark: Boolean,
        val primary: String, val onPrimary: String, val primaryContainer: String, val onPrimaryContainer: String,
        val secondary: String, val tertiary: String, val surface: String, val onSurface: String,
        val surfaceVariant: String, val onSurfaceVariant: String, val outline: String, val outlineVariant: String,
        val low: String, val container: String, val high: String, val highest: String, val dim: String, val bright: String, val inversePrimary: String,
    )

    private val golden = listOf(
        Golden(ColorPalette.LEAF, false, "0xFF2E6B3E", "0xFFFFFFFF", "0xFFCDEBD0", "0xFF0A2B14", "0xFF4F6352", "0xFF38656F", "0xFFF0F7EC", "0xFF191D19", "0xFFD3E3D0", "0xFF404941", "0xFF6B756C", "0xFFC0C9BF", "0xFFE4EEE1", "0xFFDEEADB", "0xFFD6E4D4", "0xFFCEDECC", "0xFFDCE9D8", "0xFFF0F7EC", "0xFF9FD6A9"),
        Golden(ColorPalette.LEAF, true, "0xFF9FD6A9", "0xFF06381A", "0xFF245230", "0xFFBAF2C3", "0xFFB6CCB8", "0xFFA1CED9", "0xFF0E1B12", "0xFFE0E4DD", "0xFF2A3E2E", "0xFFC0C9BF", "0xFF8A938A", "0xFF404941", "0xFF19291D", "0xFF1D2E21", "0xFF223427", "0xFF273B2C", "0xFF040A05", "0xFF1F3124", "0xFF2E6B3E"),
        Golden(ColorPalette.SAND, false, "0xFF8A4F00", "0xFFFFFFFF", "0xFFFFDDB8", "0xFF2C1700", "0xFF6F5B40", "0xFF5A6334", "0xFFFBF3E6", "0xFF201B14", "0xFFEAD8BF", "0xFF4E4538", "0xFF7A6E5E", "0xFFD4C4B0", "0xFFF4E9D9", "0xFFF1E3D2", "0xFFEDDDCA", "0xFFE8D6C1", "0xFFEFE0CB", "0xFFFBF3E6", "0xFFFFB86B"),
        Golden(ColorPalette.SAND, true, "0xFFFFB86B", "0xFF4A2900", "0xFF6A3C00", "0xFFFFDDB8", "0xFFDBC3A2", "0xFFC2CC93", "0xFF1C150A", "0xFFEBE1D5", "0xFF45371F", "0xFFD4C4B0", "0xFF9C8F7D", "0xFF4E4538", "0xFF2D2111", "0xFF332514", "0xFF3B2B17", "0xFF43311B", "0xFF0B0703", "0xFF372816", "0xFF8A4F00"),
        Golden(ColorPalette.VIOLET, false, "0xFF6A45B5", "0xFFFFFFFF", "0xFFE6DCFF", "0xFF22005C", "0xFF625B71", "0xFF7D4D63", "0xFFF6F0FE", "0xFF1C1B20", "0xFFE0D6EE", "0xFF48454E", "0xFF78747F", "0xFFCAC4D0", "0xFFEDE6FA", "0xFFE8E1F8", "0xFFE2DAF6", "0xFFDCD3F3", "0xFFE7DEF3", "0xFFF6F0FE", "0xFFCFBCFF"),
        Golden(ColorPalette.VIOLET, true, "0xFFCFBCFF", "0xFF38128B", "0xFF503391", "0xFFE6DCFF", "0xFFCCC2DC", "0xFFEFB8CE", "0xFF171222", "0xFFE6E0E9", "0xFF3B3350", "0xFFCAC4D0", "0xFF948F99", "0xFF48454E", "0xFF251F33", "0xFF292338", "0xFF302940", "0xFF362F48", "0xFF08050E", "0xFF2D263C", "0xFF6A45B5"),
    )

    @Test
    fun leafSandAndVioletKeepEveryColourOf092() {
        for (g in golden) {
            val s = Palettes.fixedScheme(g.palette, g.dark)
            val name = "${g.palette} dark=${g.dark}"
            assertEquals("$name primary", g.primary, hex(s.primary))
            assertEquals("$name onPrimary", g.onPrimary, hex(s.onPrimary))
            assertEquals("$name primaryContainer", g.primaryContainer, hex(s.primaryContainer))
            assertEquals("$name onPrimaryContainer", g.onPrimaryContainer, hex(s.onPrimaryContainer))
            assertEquals("$name secondary", g.secondary, hex(s.secondary))
            assertEquals("$name tertiary", g.tertiary, hex(s.tertiary))
            assertEquals("$name surface", g.surface, hex(s.surface))
            assertEquals("$name onSurface", g.onSurface, hex(s.onSurface))
            assertEquals("$name surfaceVariant", g.surfaceVariant, hex(s.surfaceVariant))
            assertEquals("$name onSurfaceVariant", g.onSurfaceVariant, hex(s.onSurfaceVariant))
            assertEquals("$name outline", g.outline, hex(s.outline))
            assertEquals("$name outlineVariant", g.outlineVariant, hex(s.outlineVariant))
            assertEquals("$name surfaceContainerLow", g.low, hex(s.surfaceContainerLow))
            assertEquals("$name surfaceContainer", g.container, hex(s.surfaceContainer))
            assertEquals("$name surfaceContainerHigh", g.high, hex(s.surfaceContainerHigh))
            assertEquals("$name surfaceContainerHighest", g.highest, hex(s.surfaceContainerHighest))
            assertEquals("$name surfaceDim", g.dim, hex(s.surfaceDim))
            assertEquals("$name surfaceBright", g.bright, hex(s.surfaceBright))
            assertEquals("$name inversePrimary", g.inversePrimary, hex(s.inversePrimary))
        }
    }

    @Test
    fun theSchemeThatIsDrawnForTheExistingSetsDoesNotDependOnTheCustomSeed() {
        val context = io.github.yuubiz.folirel.testutil.TestEnv.context()
        for (p in listOf(ColorPalette.CLASSIC, ColorPalette.LEAF, ColorPalette.SAND, ColorPalette.VIOLET)) for (dark in listOf(false, true)) {
            assertEquals(Palettes.fixedScheme(p, dark), Palettes.scheme(p, dark, context, 0xFFFF0000.toInt()))
            assertEquals(Palettes.fixedScheme(p, dark), Palettes.scheme(p, dark, context))
        }
    }

    /** T30: Custom draws tinted bars like every set but Classic. */
    @Test
    fun customUsesTintedBarsAndClassicDoesNot() {
        assertFalse(usesTintedBars(ColorPalette.CLASSIC))
        for (p in ColorPalette.entries - ColorPalette.CLASSIC) assertTrue("$p", usesTintedBars(p))
        assertTrue(usesTintedBars(ColorPalette.CUSTOM))
    }
}
