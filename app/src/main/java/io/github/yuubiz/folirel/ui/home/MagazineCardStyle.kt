package io.github.yuubiz.folirel.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuubiz.folirel.model.ArticleRow
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The geometry of a Magazine card in one place (UX-GRID): type sizes, line limits, paddings, the height of the source row. The card
 * drawing ([ArticleItem]) and the measurement that decides a row's height ([MagazineRowPlanner]) both read these, so what is measured
 * is what is drawn. Kept apart from colours: a later colour scheme does not change the shape of a card.
 */
internal object MagazineCardStyle {
    // A tile (a card that shares its row with others) and a card of its own (full width).
    const val TILE_TITLE_SP = 16
    const val TILE_TITLE_LINE_SP = 21
    const val TILE_SUMMARY_SP = 13
    const val TILE_SUMMARY_LINE_SP = 18
    const val SINGLE_TITLE_SP = 18
    const val SINGLE_TITLE_LINE_SP = 24
    const val SINGLE_SUMMARY_SP = 14
    const val SINGLE_SUMMARY_LINE_SP = 20

    /** Lines a headline may take; longer ones end with an ellipsis. */
    const val TITLE_MAX_LINES = 3

    /** Lines a summary may take. A limit, not a reservation: a short summary takes its own height, an empty one none. */
    const val SUMMARY_MAX_LINES = 2

    /** A card without a picture in a row whose neighbours have one may use the free height for more summary, up to this. */
    const val SUMMARY_MAX_LINES_IN_FREE_SPACE = 10

    val TILE_TEXT_PADDING = 10.dp
    val SINGLE_TEXT_PADDING = 14.dp
    val TILE_TEXT_TOP = 8.dp
    val SINGLE_TEXT_TOP = 12.dp
    val SUMMARY_GAP = 4.dp

    /** Between the text and the source row. */
    val TEXT_BOTTOM = 2.dp

    /** The source row holds the 48 dp buttons, so it is never lower than they are. */
    val META_HEIGHT = 48.dp

    /** Pixels of slack on a measured row height, so a rounding difference between measuring and drawing never cuts the source row. */
    const val MEASURE_SLACK_PX = 2
}

/** The text styles of a card; [multi] is a tile, otherwise a card of its own. */
@Immutable
internal data class CardTextStyles(val title: TextStyle, val summary: TextStyle)

@Composable
internal fun cardTextStyles(multi: Boolean): CardTextStyles {
    val s = MagazineCardStyle
    return CardTextStyles(
        title = MaterialTheme.typography.titleMedium.copy(
            fontSize = (if (multi) s.TILE_TITLE_SP else s.SINGLE_TITLE_SP).sp,
            lineHeight = (if (multi) s.TILE_TITLE_LINE_SP else s.SINGLE_TITLE_LINE_SP).sp,
        ),
        summary = MaterialTheme.typography.bodyMedium.copy(
            fontSize = (if (multi) s.TILE_SUMMARY_SP else s.SINGLE_SUMMARY_SP).sp,
            lineHeight = (if (multi) s.TILE_SUMMARY_LINE_SP else s.SINGLE_SUMMARY_LINE_SP).sp,
        ),
    )
}

/** The headline weight follows the read state, which changes how a headline wraps; measuring and drawing use the same. */
internal fun titleWeight(row: ArticleRow): FontWeight = if (row.isRead) FontWeight.Normal else FontWeight.SemiBold

/** How one row of tiles is drawn: its height (all its cards share it) and how many summary lines each card may use. */
@Immutable
internal class RowPlan(val heightPx: Int, val summaryLines: IntArray)

/**
 * Measures the text of the cards of one row of tiles with the same styles, widths and density the cards are drawn with, and gives the
 * row the height of its tallest card's real content (picture, headline, summary if there is one, source row). Nothing is reserved
 * for text an article does not have: a short headline and an empty summary make a low card. A row of tiles is never taller than its
 * own tallest card, so a long article in one row does not stretch another row.
 *
 * A card without a picture in a row whose neighbours have one gets the free height for more summary lines instead of a blank area.
 */
internal object MagazineRowPlanner {
    class Card(val row: ArticleRow, val widthDp: Float, val hasImage: Boolean)

    fun plan(
        cards: List<Card>,
        imageHeightDp: Float,
        styles: CardTextStyles,
        untitled: String,
        measurer: TextMeasurer,
        density: Density,
    ): RowPlan = with(density) {
        val s = MagazineCardStyle
        val imagePx = if (cards.any { it.hasImage }) imageHeightDp.dp.toPx() else 0f
        val fixedPx = (s.TILE_TEXT_TOP + s.TEXT_BOTTOM + s.META_HEIGHT).toPx()
        val gapPx = s.SUMMARY_GAP.toPx()
        class Measured(val titlePx: Int, val summary: String, val summaryPx: Int)
        val measured = cards.map { card ->
            val width = ((card.widthDp.dp - s.TILE_TEXT_PADDING * 2).toPx()).roundToInt().coerceAtLeast(1)
            val title = measurer.measure(
                card.row.title.ifBlank { untitled },
                style = styles.title.copy(fontWeight = titleWeight(card.row)),
                overflow = TextOverflow.Ellipsis,
                maxLines = s.TITLE_MAX_LINES,
                constraints = Constraints(maxWidth = width),
            ).size.height
            val summary = CardSummary.displayed(card.row.summaryText)
            val summaryPx = if (summary.isEmpty()) 0 else measurer.measure(
                summary,
                style = styles.summary,
                overflow = TextOverflow.Ellipsis,
                maxLines = s.SUMMARY_MAX_LINES,
                constraints = Constraints(maxWidth = width),
            ).size.height
            Measured(title, summary, summaryPx)
        }
        val heights = cards.indices.map { i ->
            val m = measured[i]
            (if (cards[i].hasImage) imagePx else 0f) + fixedPx + m.titlePx + (if (m.summary.isEmpty()) 0f else gapPx + m.summaryPx)
        }
        val rowPx = ceil(heights.maxOrNull() ?: 0f).toInt() + s.MEASURE_SLACK_PX
        val linePx = styles.summary.lineHeight.toPx()
        val lines = IntArray(cards.size) { i ->
            val m = measured[i]
            if (cards[i].hasImage || imagePx == 0f || m.summary.isEmpty() || linePx <= 0f) {
                s.SUMMARY_MAX_LINES
            } else {
                val free = rowPx - s.MEASURE_SLACK_PX - fixedPx - m.titlePx - gapPx
                floor(free / linePx).toInt().coerceIn(s.SUMMARY_MAX_LINES, s.SUMMARY_MAX_LINES_IN_FREE_SPACE)
            }
        }
        RowPlan(rowPx, lines)
    }
}
