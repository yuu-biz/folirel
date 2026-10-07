package io.github.yuubiz.folirel.ui.settings

import androidx.compose.runtime.saveable.SaverScope
import io.github.yuubiz.folirel.data.preferences.CustomColors
import io.github.yuubiz.folirel.data.preferences.CustomColors.Role
import io.github.yuubiz.folirel.ui.theme.CustomPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.9.5: what the detail editor holds while it is open. It starts from what the mode shows, keeps an edit when another colour is
 * selected, never stores an invalid `#RRGGBB`, offers Save only for a change, and only warns about low contrast.
 */
class CustomColorsDraftTest {
    private val seed = 0xFF00796B.toInt()
    private val start = CustomPalette.generatedColors(seed, dark = false)

    @Test
    fun theEditorStartsFromTheColoursOfTheGeneratedScheme() {
        val s = CustomPalette.scheme(seed, dark = false)
        assertEquals(s.primary.value, androidx.compose.ui.graphics.Color(start.accent).value)
        assertEquals(s.onSurface.value, androidx.compose.ui.graphics.Color(start.text).value)
        assertEquals(s.surface.value, androidx.compose.ui.graphics.Color(start.background).value)
        val d = CustomColorsDraft(start)
        assertEquals(start, d.shown)
        assertEquals(Role.ACCENT, d.role)
        assertFalse("nothing changed: nothing to save", d.canSave)
    }

    @Test
    fun anEditIsKeptWhenAnotherColourIsSelectedAndAllFiveCanBeChanged() {
        val d = CustomColorsDraft(start)
        d.picker.typeHex("#112233")
        d.select(Role.TEXT)
        assertEquals(0xFF112233.toInt(), d.shown.accent)
        assertEquals(start.text, d.picker.shown)
        d.picker.typeHex("#FAFAFA")
        d.select(Role.BACKGROUND)
        d.picker.typeHex("#0A0A0A")
        d.select(Role.ACCENT_CONTAINER)
        d.picker.typeHex("#334455")
        d.select(Role.SECONDARY)
        d.picker.typeHex("#665544")
        val saved = d.saveOrNull()!!
        assertEquals(CustomColors(0xFF112233.toInt(), 0xFF334455.toInt(), 0xFF665544.toInt(), 0xFF0A0A0A.toInt(), 0xFFFAFAFA.toInt()), saved)
        assertTrue(d.canSave)
    }

    @Test
    fun anInvalidHexIsNeverSavedAndDoesNotChangeThePreview() {
        val d = CustomColorsDraft(start)
        d.select(Role.TEXT)
        d.picker.typeHex("#12")
        assertNull(d.saveOrNull())
        assertFalse(d.canSave)
        assertEquals(start, d.shown)
        for (bad in listOf("123456789", "#GGGGGG", "", "#FFFFFFFF", "blue")) {
            d.picker.typeHex(bad)
            assertNull(bad, d.saveOrNull())
        }
        d.picker.typeHex("#123456")
        assertEquals(0xFF123456.toInt(), d.saveOrNull()!!.text)
    }

    @Test
    fun lowContrastIsReportedButSaveStaysPossible() {
        val d = CustomColorsDraft(start)
        d.select(Role.TEXT)
        d.picker.typeHex(CustomColors.hex(start.background))
        assertTrue(d.lowContrast(dark = false))
        assertTrue(d.canSave)
        assertEquals(start.background, d.saveOrNull()!!.text)
    }

    @Test
    fun theDraftSurvivesBeingSavedAndRestored() {
        val d = CustomColorsDraft(start)
        d.picker.typeHex("#112233")
        d.select(Role.TEXT)
        d.picker.typeHex("#4455")
        val saved = with(CustomColorsDraft.Saver) { SaverScope { true }.save(d) }!!
        val back = CustomColorsDraft.Saver.restore(saved)!!
        assertEquals(start, back.initial)
        assertEquals(Role.TEXT, back.role)
        assertEquals("#4455", back.picker.hexText)
        assertEquals(0xFF112233.toInt(), back.shown.accent)
        assertNull(back.saveOrNull())
    }
}
