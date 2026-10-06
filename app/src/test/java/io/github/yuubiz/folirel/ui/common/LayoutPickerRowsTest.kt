package io.github.yuubiz.folirel.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the five layouts (and the five colour sets) are split into rows: no row of four plus one, and names keep room at large text. */
class LayoutPickerRowsTest {
    @Test
    fun fiveInARowOnlyWhenTheyFitAndOtherwiseThreeAndTwo() {
        assertEquals(5, layoutPickerPerRow(733f))
        assertEquals(5, layoutPickerPerRow(472f))
        assertEquals(3, layoutPickerPerRow(411f - 32f))
        assertEquals(3, layoutPickerPerRow(320f - 32f))
    }

    @Test
    fun neverFourAndOne() {
        for (width in 100..900 step 4) for (scale in listOf(1f, 1.15f, 1.3f, 1.5f, 2f, 2.6f)) {
            val perRow = layoutPickerPerRow(width.toFloat(), scale)
            assertTrue("width $width scale $scale gives $perRow", perRow in 1..5 && perRow != 4)
        }
    }

    @Test
    fun largeTextGivesWiderTilesAndSoFewerPerRow() {
        val narrow = 320f - 32f
        assertEquals(3, layoutPickerPerRow(narrow, 1f))
        assertEquals(2, layoutPickerPerRow(narrow, 1.5f))
        assertEquals(1, layoutPickerPerRow(narrow, 2f))
        assertEquals(1, layoutPickerPerRow(0f, 1f))
        // The same width with less text never has fewer.
        for (width in 100..900 step 10) assertTrue(layoutPickerPerRow(width.toFloat(), 1f) >= layoutPickerPerRow(width.toFloat(), 2f))
    }
}
