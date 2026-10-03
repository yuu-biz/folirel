package io.github.yuubiz.feeddeck.ui.home

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
