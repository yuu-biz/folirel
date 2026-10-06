package io.github.yuubiz.folirel.ui.home

import io.github.yuubiz.folirel.model.CardLayoutStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U01 / U02 (rules): column counts by width, text size and choice; image height cap. */
class CardGridLayoutTest {
    private fun auto(widthDp: Float, fontScale: Float = 1f) = CardGridLayout.columns(widthDp, fontScale, 0)

    @Test
    fun autoColumnsGrowWithWidthAndNeverExceedFive() {
        assertEquals(1, auto(320f).count)
        assertEquals(1, auto(360f).count) // ordinary phone
        assertEquals(1, auto(412f).count)
        assertEquals(2, auto(480f).count) // foldable / small tablet portrait
        assertEquals(2, auto(600f).count)
        assertEquals(3, auto(733f).count) // wide tablet
        assertEquals(3, auto(840f).count)
        assertEquals(4, auto(930f).count)
        assertEquals(5, auto(1200f).count) // a large tablet, or an unfolded foldable in landscape
        assertEquals(5, auto(1280f).count)
        assertEquals(5, auto(4000f).count)
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
        // Wide windows too (RSP-T12): 200 % text takes columns away instead of shrinking the text.
        assertEquals(5, auto(1200f, 1.0f).count)
        assertEquals(4, auto(1200f, 1.3f).count)
        assertEquals(2, auto(1200f, 2.0f).count)
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
        assertEquals(5, CardGridLayout.columns(1000f, 1f, 9).count)
        assertEquals(1, CardGridLayout.columns(1000f, 1f, 1).count)
    }

    /** RSP-T10 / T11: a requested 4 or 5 is reduced on a narrow window and comes back when the window grows (the stored choice is never touched). */
    @Test
    fun fourAndFiveColumnsAreReducedWhereTheyDoNotFitAndComeBackWhenTheWindowGrows() {
        assertEquals(CardGridLayout.Columns(5, corrected = false), CardGridLayout.columns(1200f, 1f, 5))
        assertEquals(CardGridLayout.Columns(5, corrected = false), CardGridLayout.columns(840f, 1f, 5))
        // Folded: the same wish on a phone is reduced to what fits...
        assertEquals(CardGridLayout.Columns(2, corrected = true), CardGridLayout.columns(411f, 1f, 4))
        assertEquals(CardGridLayout.Columns(2, corrected = true), CardGridLayout.columns(411f, 1f, 5))
        assertEquals(CardGridLayout.Columns(4, corrected = true), CardGridLayout.columns(733f, 1f, 5))
        // ...and unfolded again it is 4 (or 5), because only the width changed.
        assertEquals(CardGridLayout.Columns(4, corrected = false), CardGridLayout.columns(840f, 1f, 4))
        // 200 % text: five do not fit even on a wide window.
        assertEquals(CardGridLayout.Columns(3, corrected = true), CardGridLayout.columns(1200f, 2f, 5))
    }

    @Test
    fun cardWidthAndImageHeightAreBoundedByTheCard() {
        for (columns in 1..5) {
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

    // Magazine page: article-driven sizes, packed without gaps

    private fun ids(n: Int, prefix: String = "article-") = List(n) { "$prefix${it * 7919 + 13}" }

    /** Rows of [spans]: each run of cards that adds up to the grid width. */
    private fun rows(spans: IntArray): List<List<Int>> {
        val rows = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        for (span in spans) {
            current.add(span)
            if (current.sum() >= MagazinePlan.GRID_SPANS) {
                rows.add(current)
                current = ArrayList()
            }
        }
        assertTrue("every row is complete", current.isEmpty())
        return rows
    }

    @Test
    fun everyRowFillsTheGridExactlyWithoutGaps() {
        for (maxColumns in 1..3) for (n in listOf(0, 1, 2, 3, 5, 8, 13, 100, 1001)) {
            val spans = MagazinePlan.spans(ids(n), maxColumns, CardLayoutStyle.MIXED)
            assertEquals(n, spans.size)
            assertTrue("no card narrower than a third", spans.all { it >= MagazinePlan.SMALL || maxColumns == 0 })
            rows(spans).forEach { assertEquals("columns=$maxColumns n=$n $it", MagazinePlan.GRID_SPANS, it.sum()) }
        }
    }

    @Test
    fun rowsAreVariedNotARepeatingPattern() {
        val spans = MagazinePlan.spans(ids(600), 3, CardLayoutStyle.MIXED)
        val kinds = rows(spans).map { it.joinToString("+") }.toSet()
        // Thirds, halves, two-thirds in both orders and full width all occur.
        for (expected in listOf("2+2+2", "3+3", "4+2", "2+4", "6")) assertTrue("missing row kind $expected in $kinds", expected in kinds)
        // And it is not a short cycle: the row sequence does not repeat with a period of 8 rows or fewer.
        val sequence = rows(spans).map { it.joinToString("+") }
        for (period in 1..8) assertFalse("period $period", (0 until sequence.size - period).all { sequence[it] == sequence[it + period] })
    }

    @Test
    fun sizeChoicesFollowTheirSharesAndAreStablePerArticle() {
        val many = ids(5000, "post-")
        val counts = many.groupingBy { MagazinePlan.preferredSpan(it, 3) }.eachCount()
        fun share(span: Int) = (counts[span] ?: 0) * 100.0 / many.size
        assertEquals(40.0, share(MagazinePlan.SMALL), 4.0)
        assertEquals(25.0, share(MagazinePlan.MEDIUM), 4.0)
        assertEquals(25.0, share(MagazinePlan.LARGE), 4.0)
        assertEquals(10.0, share(MagazinePlan.WIDE), 3.0)
        // The same id asks for the same size every time, wherever it is.
        many.take(200).forEach { id -> assertEquals(MagazinePlan.preferredSpan(id, 3), MagazinePlan.preferredSpan(id, 3)) }
        assertEquals(MagazinePlan.bucket("x"), MagazinePlan.bucket("x"))
        assertTrue(many.all { MagazinePlan.bucket(it) in 0..99 })
    }

    @Test
    fun fewerColumnsUseOnlyHalvesAndFullWidthOrFullWidthOnly() {
        val two = MagazinePlan.spans(ids(400), 2, CardLayoutStyle.MIXED)
        assertTrue(two.all { it == MagazinePlan.MEDIUM || it == MagazinePlan.WIDE })
        assertTrue("both occur", two.contains(MagazinePlan.MEDIUM) && two.contains(MagazinePlan.WIDE))
        val one = MagazinePlan.spans(ids(50), 1, CardLayoutStyle.MIXED)
        assertTrue(one.all { it == MagazinePlan.WIDE })
    }

    @Test
    fun evenStyleGivesEveryCardTheSameWidth() {
        assertEquals(List(9) { 2 }, MagazinePlan.spans(ids(9), 3, CardLayoutStyle.EVEN).toList())
        assertEquals(List(5) { 3 }, MagazinePlan.spans(ids(5), 2, CardLayoutStyle.EVEN).toList())
        assertEquals(List(4) { 6 }, MagazinePlan.spans(ids(4), 1, CardLayoutStyle.EVEN).toList())
    }

    @Test
    fun anArticleKeepsItsSizeWhenOlderOnesAreLoadedAfterIt() {
        val short = ids(60)
        val long = short + ids(40, "older-")
        val a = MagazinePlan.spans(short, 3, CardLayoutStyle.MIXED)
        val b = MagazinePlan.spans(long, 3, CardLayoutStyle.MIXED)
        // Everything up to the last complete row of the shorter list is identical.
        val completeRows = rows(a).dropLast(1) // the last row of the shorter list may have been stretched
        val keep = completeRows.sumOf { it.size }
        assertEquals(a.take(keep), b.take(keep))
    }

    @Test
    fun rowStartsGroupCardsIntoTheirRows() {
        val spans = intArrayOf(2, 4, 6, 3, 3, 2, 2, 2)
        assertEquals(listOf(0, 0, 2, 3, 3, 5, 5, 5), MagazinePlan.rowStarts(spans).toList())
    }

    @Test
    fun spanWidthsAddUpToTheScreenAndTileImagesShareOneHeight() {
        for (span in listOf(2, 3, 4, 6)) assertTrue(CardGridLayout.spanWidthDp(800f, span) > 0)
        // 3 thirds + 2 gaps + edges = the width; half + half likewise; two thirds + a third likewise.
        val w = 733f
        assertEquals(w, 3 * CardGridLayout.spanWidthDp(w, 2) + 2 * CardGridLayout.GAP_DP + 2 * CardGridLayout.EDGE_DP, 0.01f)
        assertEquals(w, 2 * CardGridLayout.spanWidthDp(w, 3) + CardGridLayout.GAP_DP + 2 * CardGridLayout.EDGE_DP, 0.01f)
        assertEquals(w, CardGridLayout.spanWidthDp(w, 4) + CardGridLayout.spanWidthDp(w, 2) + CardGridLayout.GAP_DP + 2 * CardGridLayout.EDGE_DP, 0.01f)
        // The picture height follows the narrowest tile at 2.3:1 (a third at 733 dp is 233 dp wide, so about 101 dp; it was
        // 131 dp at 16:9 before the density change) and is capped.
        assertEquals(101f, CardGridLayout.tileImageHeightDp(w, 3), 1f)
        assertTrue(CardGridLayout.tileImageHeightDp(2000f, 1) <= CardGridLayout.MAX_IMAGE_HEIGHT_DP)
    }

    @Test
    fun tilePicturesAreFlatterThanSixteenToNineButStillAPicture() {
        // Two columns on a 411 dp phone: tiles of about 193 dp get about 84 dp of picture (16:9 would be about 109 dp).
        val phone = CardGridLayout.tileImageHeightDp(411f, 2)
        assertEquals(CardGridLayout.spanWidthDp(411f, 3) / CardGridLayout.TILE_IMAGE_RATIO, phone, 0.01f)
        assertTrue("not a thin strip: $phone", phone >= 70f)
        // Wide tiles are capped; a single column keeps the 16:9 picture of a card of its own.
        assertEquals(CardGridLayout.MAX_TILE_IMAGE_HEIGHT_DP, CardGridLayout.tileImageHeightDp(3000f, 2), 0.01f)
        assertEquals(CardGridLayout.imageHeightDp(CardGridLayout.spanWidthDp(411f, 6)), CardGridLayout.tileImageHeightDp(411f, 1), 0.01f)
    }

    @Test
    fun theWideCardHasASmallPictureThatGrowsALittleWithTheWidth() {
        // A full-width card on a phone (395 dp): about a quarter of it, within the size of a list thumbnail.
        assertEquals(94.8f, CardGridLayout.wideThumbnailWidthDp(395f), 0.1f)
        assertEquals(CardGridLayout.WIDE_THUMB_MIN_DP, CardGridLayout.wideThumbnailWidthDp(250f), 0.01f)
        assertEquals(CardGridLayout.WIDE_THUMB_MAX_DP, CardGridLayout.wideThumbnailWidthDp(1200f), 0.01f)
    }
}
