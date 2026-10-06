package io.github.yuubiz.folirel.ui.settings

import io.github.yuubiz.folirel.ui.theme.SeedColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3C-T23..T25 (0.9.3): what the picker holds while it is open. The picker applies a colour only when the caller asks for it
 * ([CustomColorDraft.applyOrNull]); Cancel is "never ask", so nothing is stored; text that is not `#RRGGBB` is never a colour to store
 * and never changes what the preview draws.
 */
class CustomColorDraftTest {
    private val start = 0xFF009688.toInt()

    @Test
    fun aNewDraftIsTheCurrentColourAndNothingChangesUntilItIsMoved() {
        val d = CustomColorDraft(start)
        assertEquals(start, d.shown)
        assertEquals("#009688", d.hexText)
        assertTrue(d.valid)
        assertEquals(start, d.applyOrNull())
    }

    @Test
    fun movingThePointerChangesTheColourAndTheText() {
        val d = CustomColorDraft(start)
        d.movePointer(1f, 1f)
        d.moveHue(0f)
        assertEquals(0xFFFF0000.toInt(), d.shown)
        assertEquals("#FF0000", d.hexText)
        assertEquals(0xFFFF0000.toInt(), d.applyOrNull())
        d.movePointer(0f, 0f)
        assertEquals(0xFF000000.toInt(), d.shown)
        // Out-of-area positions are clamped.
        d.movePointer(5f, -3f)
        assertEquals(1f, d.saturation, 0f)
        assertEquals(0f, d.value, 0f)
    }

    @Test
    fun aValidTypedColourMovesThePointerAndThePreview() {
        val d = CustomColorDraft(start)
        d.typeHex("#3366CC")
        assertEquals(0xFF3366CC.toInt(), d.shown)
        assertEquals(0xFF3366CC.toInt(), SeedColor.fromHsv(d.hue, d.saturation, d.value).let { it })
        assertEquals(0xFF3366CC.toInt(), d.applyOrNull())
    }

    @Test
    fun anInvalidTextIsNeverApplicableAndDoesNotChangeThePreview() {
        val d = CustomColorDraft(start)
        d.typeHex("#3366CC")
        val before = d.shown
        for (bad in listOf("", "#", "#3366C", "#3366CCC", "#33XX66", "blue", "#3366CC ZZ")) {
            d.typeHex(bad)
            assertFalse("'$bad'", d.valid)
            assertNull("'$bad' must not be stored", d.applyOrNull())
            assertEquals("the preview keeps the last valid colour while typing '$bad'", before, d.shown)
        }
        // Typing on to a valid value makes it applicable again.
        d.typeHex("#3366CD")
        assertTrue(d.valid)
        assertEquals(0xFF3366CD.toInt(), d.applyOrNull())
    }

    @Test
    fun greyAndBlackKeepTheHueSliderWhereItWas() {
        val d = CustomColorDraft(start)
        d.moveHue(200f)
        d.typeHex("#808080")
        assertEquals(200f, d.hue, 0f)
        d.typeHex("#000000")
        assertEquals(200f, d.hue, 0f)
        d.typeHex("#FF0000")
        assertEquals(0f, d.hue, 0.5f)
    }
}
