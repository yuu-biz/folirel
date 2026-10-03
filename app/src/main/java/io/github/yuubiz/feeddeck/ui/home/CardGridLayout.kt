package io.github.yuubiz.feeddeck.ui.home

import io.github.yuubiz.feeddeck.model.CardLayoutStyle
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Column rules of the card list (UX-GRID). Pure functions so they are testable without a device.
 *
 * Auto picks the number of columns that keeps every card at least [AUTO_MIN_CARD_DP] wide. An explicit choice
 * (1/2/3) may go narrower, down to [MANUAL_MIN_CARD_DP], because the user asked for it; below that a headline is
 * no longer readable, so the choice is corrected to the largest count that fits. Both minimums grow with the text
 * size: text is never shrunk to keep columns.
 */
object CardGridLayout {
    const val MAX_COLUMNS = 3
    const val AUTO_MIN_CARD_DP = 220f
    const val MANUAL_MIN_CARD_DP = 150f

    /** Space at the screen edges and between cards. Dense on purpose (the point is to see many articles). */
    const val EDGE_DP = 8f
    const val GAP_DP = 8f

    /** Thumbnail height never exceeds this, whatever the card width. */
    const val MAX_IMAGE_HEIGHT_DP = 200f

    /** A single wide column switches to image-left/text-right instead of a very wide, cropped image. */
    const val HORIZONTAL_FROM_CARD_DP = 560f

    /** Cards narrower than this show only the "more" menu as action; save is inside it. */
    const val COMPACT_ACTIONS_BELOW_DP = 230f

    /** [count] columns are shown; [corrected] is true when the user asked for more than fits. */
    data class Columns(val count: Int, val corrected: Boolean)

    /** [preference]: 0 = auto, otherwise the wanted count (1..3). */
    fun columns(widthDp: Float, fontScale: Float, preference: Int): Columns {
        val scale = max(1f, fontScale)
        fun fits(minCardDp: Float): Int {
            val available = widthDp - 2 * EDGE_DP
            return floor((available + GAP_DP) / (minCardDp * scale + GAP_DP)).toInt().coerceIn(1, MAX_COLUMNS)
        }
        if (preference <= 0) return Columns(fits(AUTO_MIN_CARD_DP), corrected = false)
        val wanted = preference.coerceIn(1, MAX_COLUMNS)
        val count = min(wanted, fits(MANUAL_MIN_CARD_DP))
        return Columns(count, corrected = count < wanted)
    }

    fun cardWidthDp(widthDp: Float, columns: Int): Float = (widthDp - 2 * EDGE_DP - GAP_DP * (columns - 1)) / columns

    fun imageHeightDp(cardWidthDp: Float): Float = min(cardWidthDp * 9f / 16f, MAX_IMAGE_HEIGHT_DP)

    fun isHorizontal(columns: Int, cardWidthDp: Float): Boolean = columns == 1 && cardWidthDp >= HORIZONTAL_FROM_CARD_DP

    fun isCompact(cardWidthDp: Float): Boolean = cardWidthDp < COMPACT_ACTIONS_BELOW_DP
}

/**
 * The rhythm of the mixed card list (UX-GRID, magazine style). Rows follow each other in the list order (newest to
 * oldest, left to right); a row's size depends only on its position, so the same list always looks the same:
 * with three columns available `3 → wide → 2 → wide` repeats, with two `2 → wide`, with one every card is full width.
 * A last row that has fewer articles than its pattern calls for shares the width among them.
 */
object MagazinePlan {
    /** Grid columns that every row size (1, 2, 3) divides evenly. */
    const val GRID_SPANS = 6

    private val THREE = listOf(3, 1, 2, 1)
    private val TWO = listOf(2, 1)
    private val ONE = listOf(1)

    fun pattern(maxColumns: Int, style: CardLayoutStyle): List<Int> = when {
        maxColumns <= 1 -> ONE
        style == CardLayoutStyle.EVEN -> listOf(maxColumns.coerceAtMost(CardGridLayout.MAX_COLUMNS))
        maxColumns == 2 -> TWO
        else -> THREE
    }

    /** For each of [count] articles, how many cards share its row (1, 2 or 3). */
    fun rowSizes(count: Int, pattern: List<Int>, even: Boolean = false): IntArray {
        val sizes = IntArray(count)
        var i = 0
        var step = 0
        while (i < count) {
            val wanted = pattern[step % pattern.size]
            // Even grids keep every card the size of a full row; mixed ones let a short last row share the width.
            val actual = if (even) wanted else minOf(wanted, count - i)
            val take = minOf(wanted, count - i)
            for (j in 0 until take) sizes[i + j] = actual
            i += take
            step++
        }
        return sizes
    }

    /** Grid span (of [GRID_SPANS]) of a card in a row of [rowSize]. */
    fun span(rowSize: Int): Int = GRID_SPANS / rowSize.coerceIn(1, CardGridLayout.MAX_COLUMNS)
}
