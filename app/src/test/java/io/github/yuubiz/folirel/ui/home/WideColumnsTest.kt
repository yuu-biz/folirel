package io.github.yuubiz.folirel.ui.home

import io.github.yuubiz.folirel.model.CardLayoutStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RSP-T10..T17: the Magazine planner on one to five columns; 4 and 5 columns have a grid of their own, 1..3 are what 0.7.2 drew. */
class WideColumnsTest {
    private fun ids(n: Int, prefix: String = "article-") = List(n) { "$prefix${it * 7919 + 13}" }

    /** Rows of [spans]: each run of cards that adds up to [grid] units. */
    private fun rows(spans: IntArray, grid: Int): List<List<Int>> {
        val rows = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        for (span in spans) {
            current.add(span)
            if (current.sum() >= grid) {
                rows.add(current)
                current = ArrayList()
            }
        }
        assertTrue("every row is complete: $current", current.isEmpty())
        return rows
    }

    @Test
    fun theGridHasSixthsUpToThreeColumnsAndOneUnitPerColumnBeyond() {
        assertEquals(listOf(6, 6, 6, 4, 5), (1..5).map { MagazinePlan.gridSpans(it) })
        assertEquals(5, MagazinePlan.gridSpans(9))
    }

    @Test
    fun everyRowFillsTheGridOnOneToFiveColumns() {
        for (columns in 1..5) for (n in listOf(0, 1, 2, 3, 5, 8, 13, 100, 1001)) {
            val grid = MagazinePlan.gridSpans(columns)
            val spans = MagazinePlan.spans(ids(n), columns, CardLayoutStyle.MIXED)
            assertEquals(n, spans.size)
            // A sixth is no card (a third is the smallest); a quarter or a fifth is one.
            val smallest = if (columns <= 3) MagazinePlan.SMALL else 1
            assertTrue("no card narrower than $smallest (columns=$columns n=$n)", spans.all { it >= smallest })
            assertTrue("no card wider than the row", spans.all { it <= grid })
            rows(spans, grid).forEach { assertEquals("columns=$columns n=$n $it", grid, it.sum()) }
        }
    }

    /**
     * RSP-T13: one to three columns are exactly what 0.7.2 drew. The reference is the 0.7.2 algorithm (sixths, a leftover sixth joins
     * its neighbour) copied as it was; the planner must give the same sizes for every article.
     */
    @Test
    fun oneToThreeColumnsGiveTheSameSizesAs072() {
        fun reference(ids: List<String>, columns: Int, style: CardLayoutStyle): IntArray {
            val out = IntArray(ids.size)
            if (style == CardLayoutStyle.EVEN) {
                out.fill(6 / columns)
                return out
            }
            fun preferred(id: String): Int {
                val b = MagazinePlan.bucket(id)
                return when {
                    columns <= 1 -> 6
                    columns == 2 -> if (b < 85) 3 else 6
                    b < 40 -> 2
                    b < 65 -> 3
                    b < 90 -> 4
                    else -> 6
                }
            }
            var remaining = 6
            for (i in ids.indices) {
                var span = minOf(preferred(ids[i]), remaining)
                if (remaining - span == 1) span = remaining
                out[i] = span
                remaining -= span
                if (remaining == 0) remaining = 6
            }
            if (remaining != 6 && ids.isNotEmpty()) out[ids.size - 1] += remaining
            return out
        }
        for (columns in 1..3) for (style in CardLayoutStyle.entries) for (n in listOf(0, 1, 2, 3, 7, 50, 999)) for (prefix in listOf("article-", "post-", "urn:x:")) {
            val list = ids(n, prefix)
            assertEquals("columns=$columns $style n=$n", reference(list, columns, style).toList(), MagazinePlan.spans(list, columns, style).toList())
        }
        assertEquals(listOf(0, 0, 2, 3, 3, 5, 5, 5), MagazinePlan.rowStarts(intArrayOf(2, 4, 6, 3, 3, 2, 2, 2)).toList())
        // The tile picture of one to three columns keeps its 0.7.2 value (a third of 733 dp is about 101 dp of picture).
        assertEquals(101f, CardGridLayout.tileImageHeightDp(733f, 3), 1f)
    }

    /** RSP-T14 / T15: Even on four and five columns is four or five equal cards in every row. */
    @Test
    fun evenOnFourAndFiveColumnsIsReallyFourAndFiveEqualCards() {
        for (columns in 4..5) {
            val spans = MagazinePlan.spans(ids(columns * 6), columns, CardLayoutStyle.EVEN)
            assertEquals(List(columns * 6) { 1 }, spans.toList())
            // columns cards, their gaps and the edges add up to the window.
            val w = 1200f
            assertEquals(w, columns * CardGridLayout.spanWidthDp(w, 1, columns) + (columns - 1) * CardGridLayout.GAP_DP + 2 * CardGridLayout.EDGE_DP, 0.01f)
            assertEquals(CardGridLayout.cardWidthDp(w, columns), CardGridLayout.spanWidthDp(w, 1, columns), 0.01f)
        }
    }

    /** RSP-T16 / T17: Mixed on four and five columns is deterministic, gap-free, and mixes featured cards in. */
    @Test
    fun mixedOnFourAndFiveColumnsIsVariedAndNotAllSmallCards() {
        for (columns in 4..5) {
            val grid = MagazinePlan.gridSpans(columns)
            val list = ids(900)
            val spans = MagazinePlan.spans(list, columns, CardLayoutStyle.MIXED)
            assertEquals("deterministic", spans.toList(), MagazinePlan.spans(list, columns, CardLayoutStyle.MIXED).toList())
            val rows = rows(spans, grid)
            val kinds = rows.map { it.joinToString("+") }.toSet()
            assertTrue("a row of equal small cards occurs: $kinds", List(columns) { 1 }.joinToString("+") in kinds)
            assertTrue("a full-width card occurs: $kinds", "$grid" in kinds)
            assertTrue("many kinds of row: ${kinds.size}", kinds.size >= 8)
            // Featured cards (a third of the row or more) are a real share of the cards, and no long run of rows is only small cards.
            val featured = spans.count { it * 3 >= grid }
            assertTrue("featured share ${featured * 100 / spans.size}% at $columns columns", featured * 100 / spans.size in 25..60)
            var run = 0
            var longest = 0
            for (row in rows) {
                run = if (row.any { it * 3 >= grid }) 0 else run + 1
                longest = maxOf(longest, run)
            }
            assertTrue("longest run of rows without a featured card: $longest", longest <= 5)
            // And it is not a short cycle.
            val sequence = rows.map { it.joinToString("+") }
            for (period in 1..8) assertFalse("period $period", (0 until sequence.size - period).all { sequence[it] == sequence[it + period] })
        }
    }

    @Test
    fun fourAndFiveColumnSizesFollowTheirSharesAndAreStablePerArticle() {
        val many = ids(8000, "post-")
        for ((columns, shares) in mapOf(4 to listOf(38, 30, 22, 10), 5 to listOf(30, 28, 22, 12, 8))) {
            val counts = many.groupingBy { MagazinePlan.preferredSpan(it, columns) }.eachCount()
            shares.forEachIndexed { i, share ->
                assertEquals("columns=$columns span=${i + 1}", share.toDouble(), (counts[i + 1] ?: 0) * 100.0 / many.size, 3.0)
            }
            assertTrue(counts.keys.all { it in 1..shares.size })
            many.take(200).forEach { id -> assertEquals(MagazinePlan.preferredSpan(id, columns), MagazinePlan.preferredSpan(id, columns)) }
        }
    }

    @Test
    fun anArticleKeepsItsSizeWhenOlderOnesAreLoadedAfterItOnFourAndFiveColumns() {
        for (columns in 4..5) {
            val grid = MagazinePlan.gridSpans(columns)
            val short = ids(60)
            val long = short + ids(40, "older-")
            val a = MagazinePlan.spans(short, columns, CardLayoutStyle.MIXED)
            val b = MagazinePlan.spans(long, columns, CardLayoutStyle.MIXED)
            val keep = rows(a, grid).dropLast(1).sumOf { it.size }
            assertEquals(a.take(keep), b.take(keep))
        }
    }

    @Test
    fun fourAndFiveColumnSpansAddUpToTheWindowAndTheTilePictureFollowsAUnit() {
        for (columns in 4..5) {
            val w = 1100f
            // A unit and the rest of the row, one gap and the edges: the window.
            val sum = CardGridLayout.spanWidthDp(w, 1, columns) + CardGridLayout.spanWidthDp(w, columns - 1, columns) +
                CardGridLayout.GAP_DP + 2 * CardGridLayout.EDGE_DP
            assertEquals(w, sum, 0.01f)
            val unit = CardGridLayout.spanWidthDp(w, 1, columns)
            assertEquals(minOf(unit / CardGridLayout.TILE_IMAGE_RATIO, CardGridLayout.MAX_TILE_IMAGE_HEIGHT_DP), CardGridLayout.tileImageHeightDp(w, columns), 0.01f)
        }
        // 1 + 2 + 1, 3 + 1, 4: three rows of four units.
        assertEquals(listOf(0, 0, 0, 3, 3, 5), MagazinePlan.rowStarts(intArrayOf(1, 2, 1, 3, 1, 4), 4).toList())
    }
}
