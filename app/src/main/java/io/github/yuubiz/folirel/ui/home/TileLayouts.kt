package io.github.yuubiz.folirel.ui.home

import kotlin.math.max
import kotlin.math.min

/**
 * Masonry layout (lanes of cards of different heights, each card placed under the shortest lane). A picture keeps a shape chosen from
 * the article's id alone, so its area is known before the picture arrives and nothing moves when it loads; text decides the rest of
 * a card's height. Pure, testable on the JVM.
 */
internal object MasonryPlan {
    /** Width : height of the picture of a card, by the article's bucket: portrait, square, 4:3 and wide shapes. */
    private val RATIOS = floatArrayOf(0.8f, 1f, 1.33f, 1.33f, 1.78f)

    fun pictureRatio(articleId: String): Float = RATIOS[MagazinePlan.bucket(articleId) % RATIOS.size]

    /** The picture height of a card [cardWidthDp] wide, never taller than [MAX_PICTURE_DP]. */
    fun pictureHeightDp(articleId: String, cardWidthDp: Float): Float = min(cardWidthDp / pictureRatio(articleId), MAX_PICTURE_DP)

    const val MAX_PICTURE_DP = 260f

    /** Summary lines of a Masonry card (a limit, not a reservation). */
    const val SUMMARY_MAX_LINES = 3
}

/** The shape of a tile of the Bento layout, in cells. */
internal enum class BentoShape(val colSpan: Int, val rowSpan: Int) {
    SMALL(1, 1),
    WIDE(2, 1),
    TALL(1, 2),
    BIG(2, 2),

    /** Three columns of a row of four or five. */
    WIDE3(3, 1),

    /** All three columns: only for a last article that is left alone on three columns, so the end of the list has no gap. */
    FULL(3, 1),

    /** All the columns of four or five: only for a last article that is left alone, so the end of the list has no gap. */
    FULL4(4, 1),
    FULL5(5, 1);

    /** Wider than tall: the picture goes at the left of the text. */
    val horizontal: Boolean get() = colSpan > rowSpan
}

/** One article in a block: where it sits in the block's cells and how many it covers. */
internal data class BentoTile(val article: Int, val col: Int, val row: Int, val shape: BentoShape)

/** A run of articles that fills [rows] rows of all the columns without a gap. */
internal data class BentoBlock(val tiles: List<BentoTile>, val rows: Int) {
    val first: Int get() = tiles.minOf { it.article }
    val last: Int get() = tiles.maxOf { it.article }
}

/**
 * Bento layout: tiles of four shapes (small, wide, tall, big) packed into blocks that fill the columns without gaps, like a tiled
 * page. Articles stay in their order (left to right, top to bottom inside a block). Packing starts at the end of the list that does
 * not grow: new articles arrive at the newest end, so with newest first the blocks are packed from the bottom up (each block turned
 * half a circle so its reading order still runs down the page). The pattern of a block is chosen from the id of the article it
 * starts with (a fixed hash), so new arrivals change only the block at the newest end and every block under it keeps its tiles.
 * Pure, testable.
 *
 * Two columns: a tall tile with two small ones beside it (left or right), a wide tile, two small tiles, or a wide tile over two
 * small ones. Three columns: those shapes on three columns plus a big tile with two small ones beside it. One column: wide tiles only.
 * Four and five columns have patterns of their own (a row of small tiles, a wide tile with small ones, a big tile beside tall and
 * small ones, ...), each of which fills every column of its rows, so a wide window has no empty column on its right.
 */
internal object BentoPlan {
    private class Pattern(val tiles: List<Triple<Int, Int, BentoShape>>, val rows: Int, val weight: Int) {
        val size: Int get() = tiles.size
    }

    private fun p(rows: Int, weight: Int, vararg tiles: Triple<Int, Int, BentoShape>) = Pattern(tiles.toList(), rows, weight)
    private fun t(col: Int, row: Int, shape: BentoShape) = Triple(col, row, shape)

    private val TWO = listOf(
        p(2, 22, t(0, 0, BentoShape.TALL), t(1, 0, BentoShape.SMALL), t(1, 1, BentoShape.SMALL)),
        p(2, 18, t(0, 0, BentoShape.SMALL), t(0, 1, BentoShape.SMALL), t(1, 0, BentoShape.TALL)),
        p(1, 15, t(0, 0, BentoShape.WIDE)),
        p(1, 30, t(0, 0, BentoShape.SMALL), t(1, 0, BentoShape.SMALL)),
        p(2, 15, t(0, 0, BentoShape.WIDE), t(0, 1, BentoShape.SMALL), t(1, 1, BentoShape.SMALL)),
    )

    private val THREE = listOf(
        p(2, 22, t(0, 0, BentoShape.TALL), t(1, 0, BentoShape.SMALL), t(2, 0, BentoShape.SMALL), t(1, 1, BentoShape.SMALL), t(2, 1, BentoShape.SMALL)),
        p(2, 18, t(0, 0, BentoShape.SMALL), t(0, 1, BentoShape.SMALL), t(1, 0, BentoShape.BIG)),
        p(1, 15, t(0, 0, BentoShape.WIDE), t(2, 0, BentoShape.SMALL)),
        p(1, 15, t(0, 0, BentoShape.SMALL), t(1, 0, BentoShape.WIDE)),
        p(1, 30, t(0, 0, BentoShape.SMALL), t(1, 0, BentoShape.SMALL), t(2, 0, BentoShape.SMALL)),
    )

    // The first tile of a pattern is at its top left and the last one covers its bottom right, so a block turned half a circle (packing
    // from the end of the list) still starts at the top left.
    private val FOUR = listOf(
        // A big tile with four small ones beside it.
        p(2, 20, t(0, 0, BentoShape.BIG), t(2, 0, BentoShape.SMALL), t(3, 0, BentoShape.SMALL), t(2, 1, BentoShape.SMALL), t(3, 1, BentoShape.SMALL)),
        // A tall tile, two small ones and two wide ones.
        p(2, 16, t(0, 0, BentoShape.TALL), t(1, 0, BentoShape.SMALL), t(2, 0, BentoShape.WIDE), t(1, 1, BentoShape.SMALL), t(2, 1, BentoShape.WIDE)),
        // Small tiles at the left, a big tile, a tall one at the right.
        p(2, 14, t(0, 0, BentoShape.SMALL), t(0, 1, BentoShape.SMALL), t(1, 0, BentoShape.BIG), t(3, 0, BentoShape.TALL)),
        p(1, 20, t(0, 0, BentoShape.SMALL), t(1, 0, BentoShape.SMALL), t(2, 0, BentoShape.SMALL), t(3, 0, BentoShape.SMALL)),
        p(1, 12, t(0, 0, BentoShape.WIDE), t(2, 0, BentoShape.SMALL), t(3, 0, BentoShape.SMALL)),
        p(1, 8, t(0, 0, BentoShape.SMALL), t(1, 0, BentoShape.WIDE), t(3, 0, BentoShape.SMALL)),
        p(1, 10, t(0, 0, BentoShape.WIDE), t(2, 0, BentoShape.WIDE)),
    )

    private val FIVE = listOf(
        p(2, 18, t(0, 0, BentoShape.BIG), t(2, 0, BentoShape.TALL), t(3, 0, BentoShape.SMALL), t(4, 0, BentoShape.SMALL), t(3, 1, BentoShape.WIDE)),
        p(2, 16, t(0, 0, BentoShape.TALL), t(1, 0, BentoShape.SMALL), t(2, 0, BentoShape.SMALL), t(1, 1, BentoShape.SMALL), t(2, 1, BentoShape.SMALL), t(3, 0, BentoShape.BIG)),
        p(2, 10, t(0, 0, BentoShape.WIDE3), t(3, 0, BentoShape.SMALL), t(4, 0, BentoShape.SMALL), t(0, 1, BentoShape.SMALL), t(1, 1, BentoShape.SMALL), t(2, 1, BentoShape.WIDE3)),
        p(2, 12, t(0, 0, BentoShape.SMALL), t(0, 1, BentoShape.SMALL), t(1, 0, BentoShape.BIG), t(3, 0, BentoShape.SMALL), t(3, 1, BentoShape.SMALL), t(4, 0, BentoShape.TALL)),
        p(1, 20, t(0, 0, BentoShape.SMALL), t(1, 0, BentoShape.SMALL), t(2, 0, BentoShape.SMALL), t(3, 0, BentoShape.SMALL), t(4, 0, BentoShape.SMALL)),
        p(1, 14, t(0, 0, BentoShape.WIDE), t(2, 0, BentoShape.SMALL), t(3, 0, BentoShape.SMALL), t(4, 0, BentoShape.SMALL)),
        p(1, 6, t(0, 0, BentoShape.SMALL), t(1, 0, BentoShape.SMALL), t(2, 0, BentoShape.WIDE3)),
        p(1, 6, t(0, 0, BentoShape.WIDE), t(2, 0, BentoShape.WIDE3)),
    )

    /** The most articles one block of [columns] columns holds: a list that grows by one article changes at most one block. */
    fun maxPatternSize(columns: Int): Int = patternsFor(columns.coerceIn(1, CardGridLayout.MAX_COLUMNS))?.maxOf { it.size } ?: 1

    private fun patternsFor(count: Int): List<Pattern>? = when {
        count >= 5 -> FIVE
        count == 4 -> FOUR
        count == 3 -> THREE
        count == 2 -> TWO
        else -> null
    }

    /** Patterns that fit when fewer articles are left than a chosen pattern needs: the end of the list is still gap-free. */
    private fun fallback(columns: Int, left: Int): Pattern = when {
        columns >= 5 -> when {
            left >= 5 -> FIVE[4]
            left == 4 -> FIVE[5]
            left == 3 -> FIVE[6]
            left == 2 -> FIVE[7]
            else -> p(1, 1, t(0, 0, BentoShape.FULL5))
        }
        columns == 4 -> when {
            left >= 4 -> FOUR[3]
            left == 3 -> FOUR[4]
            left == 2 -> FOUR[6]
            else -> p(1, 1, t(0, 0, BentoShape.FULL4))
        }
        columns >= 3 && left >= 3 -> THREE[4]
        columns >= 3 && left == 2 -> THREE[2]
        columns >= 3 -> p(1, 1, t(0, 0, BentoShape.FULL))
        columns >= 2 && left >= 2 -> TWO[3]
        else -> p(1, 1, t(0, 0, if (columns >= 2) BentoShape.WIDE else BentoShape.SMALL))
    }

    /** [fromEnd]: pack from the last article (the list is newest first and grows at its start). */
    fun blocks(ids: List<String>, columns: Int, fromEnd: Boolean = false): List<BentoBlock> {
        if (!fromEnd) return pack(ids, columns)
        val n = ids.size
        val count = columns.coerceIn(1, CardGridLayout.MAX_COLUMNS)
        return pack(ids.asReversed(), columns).asReversed().map { block ->
            BentoBlock(
                block.tiles.map { t ->
                    BentoTile(n - 1 - t.article, count - t.col - t.shape.colSpan, block.rows - t.row - t.shape.rowSpan, t.shape)
                }.sortedBy { it.article },
                block.rows,
            )
        }
    }

    private fun pack(ids: List<String>, columns: Int): List<BentoBlock> {
        val count = columns.coerceIn(1, CardGridLayout.MAX_COLUMNS)
        val out = ArrayList<BentoBlock>()
        var i = 0
        while (i < ids.size) {
            val left = ids.size - i
            val pattern = when (count) {
                1 -> p(1, 1, t(0, 0, BentoShape.SMALL))
                else -> {
                    val patterns = patternsFor(count)!!
                    val chosen = choose(patterns, MagazinePlan.bucket(ids[i]))
                    if (chosen.size <= left) chosen else fallback(count, left)
                }
            }
            out += BentoBlock(pattern.tiles.mapIndexed { k, (col, row, shape) -> BentoTile(i + k, col, row, shape) }, pattern.rows)
            i += pattern.size
        }
        return out
    }

    private fun choose(patterns: List<Pattern>, bucket: Int): Pattern {
        val total = patterns.sumOf { it.weight }
        var point = bucket * total / 100
        for (pattern in patterns) {
            if (point < pattern.weight) return pattern
            point -= pattern.weight
        }
        return patterns.last()
    }

    /** Block of every article (for positions kept by article). */
    fun blockOfArticle(blocks: List<BentoBlock>, size: Int): IntArray {
        val out = IntArray(size)
        blocks.forEachIndexed { b, block -> block.tiles.forEach { out[it.article] = b } }
        return out
    }

    /**
     * Height of one cell (dp): the picture of a small tile (2.3:1 of the cell's width) and two headline lines over the source row.
     * [titleLineDp] already includes the text size.
     */
    fun cellHeightDp(cellWidthDp: Float, titleLineDp: Float): Float =
        cellWidthDp / CardGridLayout.TILE_IMAGE_RATIO + 8f + 2 * titleLineDp + 2f + 48f + 4f

    /** Width of a cell, the gaps between cells and the edges of the screen taken out. */
    fun cellWidthDp(widthDp: Float, columns: Int): Float = CardGridLayout.cardWidthDp(widthDp, max(1, columns))
}
