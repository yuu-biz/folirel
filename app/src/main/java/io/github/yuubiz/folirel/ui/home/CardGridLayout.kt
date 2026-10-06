package io.github.yuubiz.folirel.ui.home

import io.github.yuubiz.folirel.model.CardLayoutStyle
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Column rules of the Magazine layout (UX-GRID). Pure functions so they are testable without a device.
 *
 * Auto picks the number of columns that keeps every card at least [AUTO_MIN_CARD_DP] wide. An explicit choice
 * (1/2/3) may go narrower, down to [MANUAL_MIN_CARD_DP], because the user asked for it; below that a headline is
 * no longer readable, so the choice is corrected to the largest count that fits. Both minimums grow with the text
 * size: text is never shrunk to keep columns.
 */
object CardGridLayout {
    const val MAX_COLUMNS = 3
    const val AUTO_MIN_CARD_DP = 220f

    /** Automatic columns of the Masonry and Bento layouts: small tiles are their point, so a phone gets two. */
    const val TILE_AUTO_MIN_CARD_DP = 150f
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

    /** [preference]: 0 = auto, otherwise the wanted count (1..3). [autoMinCardDp]: the narrowest card the automatic choice allows. */
    fun columns(widthDp: Float, fontScale: Float, preference: Int, autoMinCardDp: Float = AUTO_MIN_CARD_DP): Columns {
        val scale = max(1f, fontScale)
        fun fits(minCardDp: Float): Int {
            val available = widthDp - 2 * EDGE_DP
            return floor((available + GAP_DP) / (minCardDp * scale + GAP_DP)).toInt().coerceIn(1, MAX_COLUMNS)
        }
        if (preference <= 0) return Columns(fits(autoMinCardDp), corrected = false)
        val wanted = preference.coerceIn(1, MAX_COLUMNS)
        val count = min(wanted, fits(MANUAL_MIN_CARD_DP))
        return Columns(count, corrected = count < wanted)
    }

    fun cardWidthDp(widthDp: Float, columns: Int): Float = (widthDp - 2 * EDGE_DP - GAP_DP * (columns - 1)) / columns

    /** Width of a card that spans [span] of the [MagazinePlan.GRID_SPANS] grid columns, gaps included. */
    fun spanWidthDp(widthDp: Float, span: Int): Float {
        val cell = (widthDp - 2 * EDGE_DP - GAP_DP * (MagazinePlan.GRID_SPANS - 1)) / MagazinePlan.GRID_SPANS
        return span * cell + GAP_DP * (span - 1)
    }

    /** Width : height of the picture of a tile (a card sharing its row): flatter than 16:9, still large enough to tell articles apart. */
    const val TILE_IMAGE_RATIO = 2.3f

    /** A tile's picture is never taller than this. */
    const val MAX_TILE_IMAGE_HEIGHT_DP = 160f

    /** The picture at the left of a wide card: about this share of the card's width, within [WIDE_THUMB_MIN_DP]..[WIDE_THUMB_MAX_DP]. */
    const val WIDE_THUMB_SHARE = 0.24f
    const val WIDE_THUMB_MIN_DP = 88f
    const val WIDE_THUMB_MAX_DP = 140f

    /**
     * One picture height for every tile of the page (the [TILE_IMAGE_RATIO] height of the narrowest tile, capped), so cards of
     * different widths in one row line up and the page keeps an even rhythm. A single column keeps the 16:9 picture of a card of its own.
     */
    fun tileImageHeightDp(widthDp: Float, columns: Int): Float {
        val count = columns.coerceIn(1, MAX_COLUMNS)
        val narrowest = spanWidthDp(widthDp, MagazinePlan.GRID_SPANS / count)
        return if (count == 1) imageHeightDp(narrowest) else min(narrowest / TILE_IMAGE_RATIO, MAX_TILE_IMAGE_HEIGHT_DP)
    }

    /** The 16:9 picture of a card of its own, capped. */
    fun imageHeightDp(cardWidthDp: Float): Float = min(cardWidthDp * 9f / 16f, MAX_IMAGE_HEIGHT_DP)

    fun wideThumbnailWidthDp(cardWidthDp: Float): Float = (cardWidthDp * WIDE_THUMB_SHARE).coerceIn(WIDE_THUMB_MIN_DP, WIDE_THUMB_MAX_DP)

    fun isHorizontal(columns: Int, cardWidthDp: Float): Boolean = columns == 1 && cardWidthDp >= HORIZONTAL_FROM_CARD_DP

    fun isCompact(cardWidthDp: Float): Boolean = cardWidthDp < COMPACT_ACTIONS_BELOW_DP
}

/**
 * The mixed arrangement of the Magazine layout (UX-GRID). Every article prefers a card size, chosen from its id alone
 * (a fixed hash, so the same article always asks for the same size, whatever its position, the sort order or when
 * the list is redrawn; nothing is random at run time). Cards are packed left to right into rows of [GRID_SPANS]
 * sixths: small (1/3), medium (1/2), large (2/3) and wide (full width). The result looks irregular, like a magazine
 * page, with rows such as 1/3+1/3+1/3, 1/2+1/2, 2/3+1/3, 1/3+2/3 and a full-width card, and no row ever has a gap:
 * a card that does not fit the rest of its row takes exactly the rest.
 *
 * Wider screens use thirds; with only two columns available only halves and full-width cards are used; with one
 * column every card is full width.
 */
object MagazinePlan {
    /** Grid columns that thirds, halves and two-thirds all divide evenly. */
    const val GRID_SPANS = 6
    const val SMALL = 2
    const val MEDIUM = 3
    const val LARGE = 4
    const val WIDE = 6

    /** Share (percent) of each preferred size when thirds are available: 40 / 25 / 25 / 10. */
    private const val SMALL_UNTIL = 40
    private const val MEDIUM_UNTIL = 65
    private const val LARGE_UNTIL = 90

    /** Share (percent) of half-width cards when only two columns fit; the rest are full width. */
    private const val HALF_SHARE = 85

    /** 0..99, stable for an id (32-bit FNV-1a). */
    fun bucket(articleId: String): Int {
        var hash = -0x7ee3623b // 2166136261
        for (c in articleId) {
            hash = hash xor c.code
            hash *= 16777619
        }
        return ((hash.toLong() and 0xffffffffL) % 100).toInt()
    }

    /** The size (in sixths) an article asks for. Depends only on its id and the number of columns that fit. */
    fun preferredSpan(articleId: String, maxColumns: Int): Int {
        val b = bucket(articleId)
        return when {
            maxColumns <= 1 -> WIDE
            maxColumns == 2 -> if (b < HALF_SHARE) MEDIUM else WIDE
            b < SMALL_UNTIL -> SMALL
            b < MEDIUM_UNTIL -> MEDIUM
            b < LARGE_UNTIL -> LARGE
            else -> WIDE
        }
    }

    /**
     * Span (in sixths) of every article, in list order. Each row sums to exactly [GRID_SPANS]: a card that does not
     * fit the rest of its row takes the rest, a card that would leave a single sixth takes it too, and a short final
     * row is stretched. [EVEN] gives every card the same width.
     */
    fun spans(ids: List<String>, maxColumns: Int, style: CardLayoutStyle): IntArray {
        val columns = maxColumns.coerceIn(1, CardGridLayout.MAX_COLUMNS)
        val out = IntArray(ids.size)
        if (style == CardLayoutStyle.EVEN) {
            out.fill(GRID_SPANS / columns)
            return out
        }
        var remaining = GRID_SPANS
        for (i in ids.indices) {
            var span = minOf(preferredSpan(ids[i], columns), remaining)
            if (remaining - span == 1) span = remaining
            out[i] = span
            remaining -= span
            if (remaining == 0) remaining = GRID_SPANS
        }
        if (remaining != GRID_SPANS && ids.isNotEmpty()) out[ids.size - 1] += remaining
        return out
    }

    /** Row number of every article for [spans] (rows are runs of cards that add up to [GRID_SPANS]). */
    fun rowStarts(spans: IntArray): IntArray {
        val starts = IntArray(spans.size)
        var rowStart = 0
        var filled = 0
        for (i in spans.indices) {
            if (filled == 0) rowStart = i
            starts[i] = rowStart
            filled += spans[i]
            if (filled >= GRID_SPANS) filled = 0
        }
        return starts
    }
}
