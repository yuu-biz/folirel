package io.github.yuubiz.folirel.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Masonry picture shapes and the Bento blocks, on the JVM. */
class TileLayoutsTest {
    private fun ids(n: Int, prefix: String = "article-") = List(n) { "$prefix${it * 7919 + 13}" }

    @Test
    fun masonryPicturesHaveAStableShapePerArticleAndAreCapped() {
        val many = ids(500)
        val ratios = many.map { MasonryPlan.pictureRatio(it) }.toSet()
        assertTrue("several shapes are used: $ratios", ratios.size >= 3)
        for (id in many) assertEquals(MasonryPlan.pictureRatio(id), MasonryPlan.pictureRatio(id), 0f)
        for (id in many) assertTrue(MasonryPlan.pictureHeightDp(id, 1000f) <= MasonryPlan.MAX_PICTURE_DP)
        assertEquals(190f / MasonryPlan.pictureRatio(many[0]), MasonryPlan.pictureHeightDp(many[0], 190f), 0.01f)
    }

    /** Cells of a block: every cell of every row is covered exactly once (no gap, no overlap). */
    private fun assertGapFree(block: BentoBlock, columns: Int) {
        val covered = Array(block.rows) { IntArray(columns) }
        for (t in block.tiles) {
            for (r in t.row until t.row + t.shape.rowSpan) for (c in t.col until t.col + t.shape.colSpan) {
                assertTrue("tile $t is inside the block of $columns columns and ${block.rows} rows", r < block.rows && c < columns)
                covered[r][c]++
            }
        }
        for (r in 0 until block.rows) for (c in 0 until columns) assertEquals("cell $r/$c of $block", 1, covered[r][c])
    }

    @Test
    fun bentoBlocksFillTheirColumnsWithoutGapsAndKeepTheOrder() {
        for (fromEnd in listOf(false, true)) for (columns in 1..3) {
            for (n in listOf(1, 2, 3, 4, 5, 7, 40, 301)) {
                val blocks = BentoPlan.blocks(ids(n), columns, fromEnd)
                // Every article once, in order.
                assertEquals((0 until n).toList(), blocks.flatMap { b -> b.tiles.map { it.article }.sorted() })
                for (b in blocks) assertGapFree(b, columns)
                // The first article of a block is its top left tile.
                for (b in blocks) b.tiles.minBy { it.article }.let { assertTrue("$b", it.row == 0 && it.col == 0) }
            }
        }
    }

    /** Where every article sits: its block counted from the stable end, and its place and shape inside the block. */
    private fun places(blocks: List<BentoBlock>, ids: List<String>, fromEnd: Boolean): Map<String, List<Any>> {
        val ordered = if (fromEnd) blocks.asReversed() else blocks
        return ordered.flatMapIndexed { b, block -> block.tiles.map { ids[it.article] to listOf(b, it.col, it.row, it.shape) } }.toMap()
    }

    @Test
    fun newArticlesAtTheNewestEndChangeOnlyTheBlockThere() {
        val old = ids(120)
        for (columns in 2..3) for (added in 1..7) {
            val fresh = List(added) { "fresh-$it" }
            // Newest first: new articles come before the old ones.
            val before = places(BentoPlan.blocks(old, columns, fromEnd = true), old, true)
            val withNew = fresh + old
            val after = places(BentoPlan.blocks(withNew, columns, fromEnd = true), withNew, true)
            val moved = old.filter { before[it] != after[it] }
            // Only articles of the old top block (the one left over at the newest end) may change.
            assertTrue("$columns columns, $added new: ${moved.size} old articles moved", moved.size <= 4 && moved.all { old.indexOf(it) < 4 })
            // Oldest first: new articles come after the old ones.
            val beforeAsc = places(BentoPlan.blocks(old, columns), old, false)
            val asc = old + fresh
            val afterAsc = places(BentoPlan.blocks(asc, columns), asc, false)
            val movedAsc = old.filter { beforeAsc[it] != afterAsc[it] }
            assertTrue("oldest first, $columns columns, $added new: $movedAsc", movedAsc.size <= 4 && movedAsc.all { old.indexOf(it) >= old.size - 4 })
        }
    }

    @Test
    fun bentoUsesAllFourShapesOnThreeColumnsAndStaysStablePerArticle() {
        val list = ids(300)
        val shapes = BentoPlan.blocks(list, 3).flatMap { b -> b.tiles.map { it.shape } }.toSet()
        assertTrue("three columns use small, wide, tall and big: $shapes", shapes.containsAll(listOf(BentoShape.SMALL, BentoShape.WIDE, BentoShape.TALL, BentoShape.BIG)))
        val twoShapes = BentoPlan.blocks(list, 2).flatMap { b -> b.tiles.map { it.shape } }.toSet()
        assertTrue("two columns use small, wide and tall: $twoShapes", twoShapes.containsAll(listOf(BentoShape.SMALL, BentoShape.WIDE, BentoShape.TALL)))
        // The same list gives the same blocks (nothing random at run time).
        assertEquals(BentoPlan.blocks(list, 2), BentoPlan.blocks(list, 2))
        // One column: one small (full-width) tile per article.
        assertTrue(BentoPlan.blocks(list, 1).all { it.tiles.single().shape == BentoShape.SMALL && it.rows == 1 })
    }

    @Test
    fun blockOfArticleMapsBothWays() {
        val blocks = BentoPlan.blocks(ids(50), 2)
        val blockOf = BentoPlan.blockOfArticle(blocks, 50)
        blocks.forEachIndexed { b, block -> block.tiles.forEach { assertEquals(b, blockOf[it.article]) } }
        assertEquals(0, blockOf[0])
    }

    @Test
    fun theCellIsTallEnoughForAPictureTwoHeadlineLinesAndTheSourceRow() {
        val cell = BentoPlan.cellWidthDp(411f, 2)
        val h = BentoPlan.cellHeightDp(cell, 21f)
        assertEquals(cell / CardGridLayout.TILE_IMAGE_RATIO + 8f + 42f + 2f + 48f + 4f, h, 0.01f)
        // Larger text makes the cell taller (the text is never shrunk to fit the cell).
        assertTrue(BentoPlan.cellHeightDp(cell, 42f) > h)
    }

    @Test
    fun tileLayoutsGetTwoColumnsOnAPhoneByDefault() {
        assertEquals(2, CardGridLayout.columns(411f, 1f, 0, CardGridLayout.TILE_AUTO_MIN_CARD_DP).count)
        assertEquals(1, CardGridLayout.columns(411f, 1f, 0).count) // the Magazine automatic choice is unchanged
        assertEquals(1, CardGridLayout.columns(411f, 2f, 0, CardGridLayout.TILE_AUTO_MIN_CARD_DP).count) // 200 % text: one
    }
}
