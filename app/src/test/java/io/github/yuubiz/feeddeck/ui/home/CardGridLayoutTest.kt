package io.github.yuubiz.feeddeck.ui.home

import io.github.yuubiz.feeddeck.model.CardLayoutStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U01 / U02 (rules): column counts by width, text size and choice; image height cap. */
class CardGridLayoutTest {
    private fun auto(widthDp: Float, fontScale: Float = 1f) = CardGridLayout.columns(widthDp, fontScale, 0)

    @Test
    fun autoColumnsGrowWithWidthAndNeverExceedThree() {
        assertEquals(1, auto(320f).count)
        assertEquals(1, auto(360f).count) // ordinary phone
        assertEquals(1, auto(412f).count)
        assertEquals(2, auto(480f).count) // foldable / small tablet portrait
        assertEquals(2, auto(600f).count)
        assertEquals(3, auto(733f).count) // wide tablet
        assertEquals(3, auto(840f).count)
        assertEquals(3, auto(1280f).count)
        assertEquals(3, auto(4000f).count)
        // Wide never means one huge column.
        assertTrue(auto(840f).count > 1)
        assertFalse(auto(840f).corrected)
    }

    @Test
    fun largerTextNeedsWiderCardsSoColumnsDropInsteadOfShrinkingText() {
        assertEquals(3, auto(733f, 1.0f).count)
        assertEquals(2, auto(733f, 1.3f).count)
        assertEquals(1, auto(733f, 2.0f).count)
        assertEquals(1, auto(480f, 2.0f).count)
        // Below 1.0 does not squeeze more columns in.
        assertEquals(auto(733f, 1.0f).count, auto(733f, 0.8f).count)
    }

    @Test
    fun explicitChoiceMayBeNarrowerThanAutoButIsReducedWhenUnreadable() {
        // Two columns on a 360 dp phone: 168 dp cards, allowed (the user asked for it).
        assertEquals(CardGridLayout.Columns(2, corrected = false), CardGridLayout.columns(360f, 1f, 2))
        // Three do not fit there.
        assertEquals(CardGridLayout.Columns(2, corrected = true), CardGridLayout.columns(360f, 1f, 3))
        // One is always fine.
        assertEquals(CardGridLayout.Columns(1, corrected = false), CardGridLayout.columns(320f, 2f, 1))
        // Large text on the phone: two columns become one, and the app says so (corrected).
        assertEquals(CardGridLayout.Columns(1, corrected = true), CardGridLayout.columns(360f, 1.3f, 2))
        assertEquals(CardGridLayout.Columns(1, corrected = true), CardGridLayout.columns(360f, 2f, 3))
        // Split screen of 300 dp.
        assertEquals(CardGridLayout.Columns(1, corrected = true), CardGridLayout.columns(300f, 1f, 2))
        // A tablet shows all three.
        assertEquals(CardGridLayout.Columns(3, corrected = false), CardGridLayout.columns(733f, 1f, 3))
        assertEquals(CardGridLayout.Columns(1, corrected = false), CardGridLayout.columns(733f, 1f, 1))
        // Out-of-range values are clamped, never crash.
        assertEquals(3, CardGridLayout.columns(1000f, 1f, 9).count)
        assertEquals(1, CardGridLayout.columns(1000f, 1f, 1).count)
    }

    @Test
    fun cardWidthAndImageHeightAreBoundedByTheCard() {
        for (columns in 1..3) {
            val width = CardGridLayout.cardWidthDp(840f, columns)
            val total = width * columns + CardGridLayout.GAP_DP * (columns - 1) + 2 * CardGridLayout.EDGE_DP
            assertEquals(840f, total, 0.01f)
        }
        // A phone-width single card keeps (almost) 16:9; a huge one is capped, not half the screen.
        assertEquals(344f * 9 / 16, CardGridLayout.imageHeightDp(344f), 0.01f)
        assertEquals(CardGridLayout.MAX_IMAGE_HEIGHT_DP, CardGridLayout.imageHeightDp(824f), 0.01f)
        assertTrue(CardGridLayout.imageHeightDp(10_000f) <= CardGridLayout.MAX_IMAGE_HEIGHT_DP)
        // Multi-column images follow the card width (about a third of the screen width on a tablet).
        assertEquals(CardGridLayout.cardWidthDp(733f, 3) * 9 / 16, CardGridLayout.imageHeightDp(CardGridLayout.cardWidthDp(733f, 3)), 0.01f)
    }

    @Test
    fun wideSingleColumnUsesImageBesideText() {
        assertFalse(CardGridLayout.isHorizontal(1, 344f))
        assertTrue(CardGridLayout.isHorizontal(1, 824f))
        assertFalse(CardGridLayout.isHorizontal(2, 824f))
        assertTrue(CardGridLayout.isCompact(168f))
        assertFalse(CardGridLayout.isCompact(344f))
    }

    // Magazine rhythm

    @Test
    fun mixedPageRepeatsSmallWideLargeWide() {
        val sizes = MagazinePlan.rowSizes(14, MagazinePlan.pattern(3, CardLayoutStyle.MIXED))
        // 3 small, 1 wide, 2 larger, 1 wide, then again 3 small, 1 wide, 2 larger, 1 wide.
        assertEquals(listOf(3, 3, 3, 1, 2, 2, 1, 3, 3, 3, 1, 2, 2, 1), sizes.toList())
        // Every row fills the 6 grid columns exactly.
        var i = 0
        while (i < sizes.size) {
            val size = sizes[i]
            assertEquals(MagazinePlan.GRID_SPANS, MagazinePlan.span(size) * size)
            i += size
        }
        // Two columns: 2, wide, 2, wide. One column: every card full width.
        assertEquals(listOf(2, 2, 1, 2, 2, 1), MagazinePlan.rowSizes(6, MagazinePlan.pattern(2, CardLayoutStyle.MIXED)).toList())
        assertEquals(listOf(1, 1, 1), MagazinePlan.rowSizes(3, MagazinePlan.pattern(1, CardLayoutStyle.MIXED)).toList())
    }

    @Test
    fun aShortLastRowSharesTheWidthAndEvenKeepsOneSize() {
        // 5 articles: a row of 3, a wide one, then one article alone takes the next pattern row (2) as a full row of 1.
        assertEquals(listOf(3, 3, 3, 1, 1), MagazinePlan.rowSizes(5, MagazinePlan.pattern(3, CardLayoutStyle.MIXED)).toList())
        // Two articles only: they share the row.
        assertEquals(listOf(2, 2), MagazinePlan.rowSizes(2, MagazinePlan.pattern(3, CardLayoutStyle.MIXED)).toList())
        assertEquals(emptyList<Int>(), MagazinePlan.rowSizes(0, MagazinePlan.pattern(3, CardLayoutStyle.MIXED)).toList())
        val even = MagazinePlan.rowSizes(7, MagazinePlan.pattern(3, CardLayoutStyle.EVEN), even = true)
        assertEquals(List(7) { 3 }, even.toList())
        assertEquals(2, MagazinePlan.span(3))
        assertEquals(6, MagazinePlan.span(1))
    }

    @Test
    fun theSameCountAlwaysGivesTheSameLayout() {
        val a = MagazinePlan.rowSizes(40, MagazinePlan.pattern(3, CardLayoutStyle.MIXED)).toList()
        val b = MagazinePlan.rowSizes(40, MagazinePlan.pattern(3, CardLayoutStyle.MIXED)).toList()
        assertEquals(a, b)
        // A prefix of a longer list has the same card sizes (older articles keep their size when more are loaded).
        val longer = MagazinePlan.rowSizes(60, MagazinePlan.pattern(3, CardLayoutStyle.MIXED)).toList()
        assertEquals(a.take(36), longer.take(36))
    }
}
