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
        for (fromEnd in listOf(false, true)) for (columns in 1..5) {
            for (n in listOf(1, 2, 3, 4, 5, 6, 7, 9, 11, 40, 301)) {
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
        for (columns in 2..5) for (added in 1..7) {
            val fresh = List(added) { "fresh-$it" }
            // Newest first: new articles come before the old ones.
            val before = places(BentoPlan.blocks(old, columns, fromEnd = true), old, true)
            val withNew = fresh + old
            val after = places(BentoPlan.blocks(withNew, columns, fromEnd = true), withNew, true)
            val moved = old.filter { before[it] != after[it] }
            // Only articles of the old top block (the one left over at the newest end) may change.
            val most = BentoPlan.maxPatternSize(columns) - 1
            assertTrue("$columns columns, $added new: ${moved.size} old articles moved", moved.size <= most && moved.all { old.indexOf(it) < most })
            // Oldest first: new articles come after the old ones.
            val beforeAsc = places(BentoPlan.blocks(old, columns), old, false)
            val asc = old + fresh
            val afterAsc = places(BentoPlan.blocks(asc, columns), asc, false)
            val movedAsc = old.filter { beforeAsc[it] != afterAsc[it] }
            assertTrue("oldest first, $columns columns, $added new: $movedAsc", movedAsc.size <= most && movedAsc.all { old.indexOf(it) >= old.size - most })
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

    /** RSP-T20 / T21: four and five columns have patterns of their own, every one of which fills its columns. */
    @Test
    fun bentoOnFourAndFiveColumnsUsesAllShapesAndNeverLeavesAnEmptyColumn() {
        val list = ids(600)
        for (columns in 4..5) {
            val blocks = BentoPlan.blocks(list, columns)
            val shapes = blocks.flatMap { b -> b.tiles.map { it.shape } }.toSet()
            assertTrue("$columns columns use small, wide, tall and big: $shapes", shapes.containsAll(listOf(BentoShape.SMALL, BentoShape.WIDE, BentoShape.TALL, BentoShape.BIG)))
            // Every block covers every cell of its rows once, so no column of a block is empty (checked for both packing directions).
            for (fromEnd in listOf(false, true)) for (b in BentoPlan.blocks(list, columns, fromEnd)) assertGapFree(b, columns)
            assertEquals(blocks, BentoPlan.blocks(list, columns))
            // A block never holds more articles than the pattern bound says.
            assertTrue(blocks.all { it.tiles.size <= BentoPlan.maxPatternSize(columns) })
            // Rows of blocks are one or two high.
            assertTrue(blocks.all { it.rows in 1..2 })
        }
        assertTrue("five columns use a three-wide tile", BentoPlan.blocks(list, 5).flatMap { b -> b.tiles.map { it.shape } }.contains(BentoShape.WIDE3))
    }

    /** The end of the list: one to a few articles left over still fill every column (the last block is a shorter pattern or one full-width tile). */
    @Test
    fun theLastBlockOfFourAndFiveColumnsIsGapFreeForEveryNumberOfArticlesLeft() {
        for (columns in 4..5) for (n in 1..40) for (fromEnd in listOf(false, true)) {
            val blocks = BentoPlan.blocks(ids(n, "tail-"), columns, fromEnd)
            for (b in blocks) assertGapFree(b, columns)
            assertEquals((0 until n).toList(), blocks.flatMap { b -> b.tiles.map { it.article } }.sorted())
        }
        assertEquals(BentoShape.FULL4, BentoPlan.blocks(ids(1), 4).single().tiles.single().shape)
        assertEquals(BentoShape.FULL5, BentoPlan.blocks(ids(1), 5).single().tiles.single().shape)
        assertEquals(BentoShape.FULL, BentoPlan.blocks(ids(1), 3).single().tiles.single().shape)
    }

    /**
     * RSP-T13 for Bento: one to three columns are exactly the blocks 0.7.2 drew. The strings were produced by an independent copy of
     * the 0.7.2 packer (patterns, fall-backs and the half-turn for newest-first lists) for fourteen articles, in both packing directions.
     */
    @Test
    fun oneToThreeColumnBentoBlocksAreUnchangedFrom072() {
        val list = List(14) { "article-${it * 7919 + 13}" }
        fun show(columns: Int, fromEnd: Boolean) = BentoPlan.blocks(list, columns, fromEnd).joinToString(" | ") { b ->
            "${b.rows}:" + b.tiles.joinToString(",") { "${it.article}@${it.col}.${it.row}${it.shape.name[0]}" }
        }
        val one = (0..13).joinToString(" | ") { "1:$it@0.0S" }
        assertEquals(one, show(1, false))
        assertEquals(one, show(1, true))
        assertEquals("1:0@0.0W | 1:1@0.0S,2@1.0S | 1:3@0.0S,4@1.0S | 2:5@0.0T,6@1.0S,7@1.1S | 2:8@0.0W,9@0.1S,10@1.1S | 1:11@0.0W | 1:12@0.0S,13@1.0S", show(2, false))
        assertEquals("1:0@0.0S,1@1.0S | 1:2@0.0S,3@1.0S | 2:4@0.0T,5@1.0S,6@1.1S | 1:7@0.0W | 2:8@0.0S,9@0.1S,10@1.0T | 2:11@0.0T,12@1.0S,13@1.1S", show(2, true))
        assertEquals("1:0@0.0W,1@2.0S | 1:2@0.0S,3@1.0W | 1:4@0.0S,5@1.0S,6@2.0S | 1:7@0.0W,8@2.0S | 1:9@0.0S,10@1.0W | 1:11@0.0W,12@2.0S | 1:13@0.0F", show(3, false))
        assertEquals("1:0@0.0F | 2:1@0.0S,2@1.0S,3@0.1S,4@1.1S,5@2.0T | 2:6@0.0S,7@1.0S,8@0.1S,9@1.1S,10@2.0T | 2:11@0.0B,12@2.0S,13@2.1S", show(3, true))
        assertEquals(5, BentoPlan.maxPatternSize(3))
        assertEquals(3, BentoPlan.maxPatternSize(2))
        assertEquals(1, BentoPlan.maxPatternSize(1))
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

    /** RSP-T19: Masonry and Bento use four and five lanes of a wide window. */
    @Test
    fun tileLayoutsUseFourAndFiveColumnsOnWideWindows() {
        val tile = CardGridLayout.TILE_AUTO_MIN_CARD_DP
        assertEquals(3, CardGridLayout.columns(600f, 1f, 0, tile).count)
        assertEquals(4, CardGridLayout.columns(733f, 1f, 0, tile).count)
        assertEquals(5, CardGridLayout.columns(1200f, 1f, 0, tile).count)
        assertEquals(3, CardGridLayout.columns(1200f, 2f, 0, tile).count)
        for (columns in 4..5) {
            val cell = BentoPlan.cellWidthDp(1200f, columns)
            assertEquals(1200f, cell * columns + CardGridLayout.GAP_DP * (columns - 1) + 2 * CardGridLayout.EDGE_DP, 0.01f)
        }
    }
}
