package io.github.yuubiz.folirel.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.ListMode

/** Actions offered for one row. Opening never changes read/saved state. */
class ArticleActions(
    val open: (ArticleRow) -> Unit,
    val openExternal: (ArticleRow) -> Unit,
    val toggleSaved: (ArticleRow) -> Unit,
    val setRead: (ArticleRow, Boolean) -> Unit,
    val share: (ArticleRow) -> Unit,
    val showSummary: (ArticleRow) -> Unit,
)

/**
 * Size of one card in the Magazine layout: how much of the row it spans and how wide it is (dp). [wide] marks the
 * full-width card of a mixed (magazine) page, drawn with the image beside the text.
 */
@Immutable
class CardMetrics(
    /** Units of the row this card spans: sixths up to three columns (2 = a third, 3 = a half, 4 = two thirds, 6 = full width), see [gridSpans]. */
    val span: Int,
    val widthDp: Float,
    val wide: Boolean = false,
    val rowHasImage: Boolean = true,
    /** The picture height every tile of the page shares. */
    val imageHeightDp: Float = CardGridLayout.imageHeightDp(widthDp),
    /** Units in a full row (6, or the number of columns on four and five columns): a card of fewer shares its row. */
    val gridSpans: Int = MagazinePlan.GRID_SPANS,
) {
    val multiColumn: Boolean get() = span < gridSpans
    val horizontal: Boolean get() = wide || (!multiColumn && CardGridLayout.isHorizontal(1, widthDp))
    val compact: Boolean get() = CardGridLayout.isCompact(widthDp)
    val imageHeight: Dp get() = imageHeightDp.dp

    companion object {
        /** One ordinary phone-width column (used where no grid measured the width). */
        val SingleColumn = CardMetrics(MagazinePlan.GRID_SPANS, 360f)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleItem(
    row: ArticleRow,
    mode: ListMode,
    showImages: Boolean,
    imageLoader: ImageLoader,
    nowMillis: Long,
    actions: ArticleActions,
    modifier: Modifier = Modifier,
    card: CardMetrics = CardMetrics.SingleColumn,
    /** Magazine tiles: the height of their row ([MagazineRowPlanner]); null for a card of its own (its content decides). */
    rowHeight: Dp? = null,
    /** Magazine: lines the summary may use (more only for a card without picture beside cards with one). */
    summaryLines: Int = MagazineCardStyle.SUMMARY_MAX_LINES,
    /** Bento: the tile's shape and size. */
    bento: BentoCell? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val saveLabel = stringResource(if (row.isSaved) R.string.action_unsave else R.string.action_save)
    val readLabel = stringResource(if (row.isRead) R.string.action_mark_unread else R.string.action_mark_read)
    val shareLabel = stringResource(R.string.action_share)
    val openLabel = stringResource(R.string.action_open_article)
    val menuLabel = stringResource(R.string.action_more)
    val rowModifier = Modifier
        .fillMaxWidth()
        .combinedClickable(
            onClickLabel = openLabel,
            onLongClickLabel = menuLabel,
            onClick = { actions.open(row) },
            onLongClick = { if (row.linkUrl == null) actions.showSummary(row) else menuOpen = true },
        )
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(saveLabel) { actions.toggleSaved(row); true },
                CustomAccessibilityAction(readLabel) { actions.setRead(row, !row.isRead); true },
                CustomAccessibilityAction(shareLabel) { actions.share(row); true },
            )
        }
    val bookmarkButton: @Composable () -> Unit = {
        IconButton(onClick = { actions.toggleSaved(row) }) {
            Icon(
                painter = painterResource(if (row.isSaved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border),
                contentDescription = saveLabel,
                tint = if (row.isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    val moreButton: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = menuLabel, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(readLabel) }, onClick = { menuOpen = false; actions.setRead(row, !row.isRead) })
                DropdownMenuItem(text = { Text(saveLabel) }, onClick = { menuOpen = false; actions.toggleSaved(row) })
                DropdownMenuItem(text = { Text(shareLabel) }, onClick = { menuOpen = false; actions.share(row) })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_open_external)) },
                    enabled = row.linkUrl != null,
                    onClick = { menuOpen = false; actions.openExternal(row) },
                )
                if (row.linkUrl == null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_show_summary)) },
                        onClick = { menuOpen = false; actions.showSummary(row) },
                    )
                }
            }
        }
    }
    val buttons: @Composable () -> Unit = {
        bookmarkButton()
        moreButton()
    }

    if (mode == ListMode.BENTO && bento != null) {
        BentoCard(row, showImages, imageLoader, nowMillis, bento, bookmarkButton, moreButton, modifier, rowModifier)
        return
    }
    if (mode == ListMode.MAGAZINE || mode == ListMode.MASONRY || mode == ListMode.BENTO) {
        ArticleCard(row, showImages, imageLoader, nowMillis, card, rowHeight, summaryLines, bookmarkButton, moreButton, modifier, rowModifier)
        return
    }
    Column(modifier) {
        if (mode == ListMode.LIST) {
            Row(
                modifier = rowModifier.padding(start = 12.dp, end = 4.dp, top = 8.dp).heightIn(min = 72.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    ArticleTitle(row, fontSize = 16, lineHeight = 22, maxLines = 3)
                    if (row.summaryText.isNotBlank()) {
                        Spacer(Modifier.padding(top = 2.dp))
                        Text(
                            text = row.summaryText,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showImages) Thumbnail(row.imageUrl, 96.dp, imageLoader)
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                MetaText(row, nowMillis, Modifier.weight(1f))
                buttons()
            }
        } else {
            // Compact list: one row, title (2 lines) + meta, small thumbnail, 48 dp buttons on the same row.
            Row(
                modifier = rowModifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    ArticleTitle(row, fontSize = 15, lineHeight = 20, maxLines = 2)
                    MetaText(row, nowMillis, Modifier.padding(top = 2.dp))
                }
                if (showImages) {
                    Spacer(Modifier.width(8.dp))
                    Thumbnail(row.imageUrl, 56.dp, imageLoader, bottomPadding = 0.dp)
                }
                buttons()
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

/**
 * Card (UX-GRID). Picture on top (a fixed area, known before the picture arrives, so loading, failure and the cache never change
 * the card's height), headline (up to 3 lines), the summary if it says something (up to 2 lines; [CardSummary] drops a summary
 * that is only a "read more" label), then the source row. Nothing is reserved for text an article does not have.
 *
 * Tiles (several cards in a row) get [rowHeight]: the height of the tallest card of their own row ([MagazineRowPlanner]), so the
 * cards of a row end together while a short row stays short. A wide card puts a small picture at the left. Images off: no picture
 * area and no request.
 */
@Composable
private fun ArticleCard(
    row: ArticleRow,
    showImages: Boolean,
    imageLoader: ImageLoader,
    nowMillis: Long,
    metrics: CardMetrics,
    rowHeight: Dp?,
    summaryLines: Int,
    bookmarkButton: @Composable () -> Unit,
    moreButton: @Composable () -> Unit,
    modifier: Modifier,
    clickModifier: Modifier,
) {
    val style = MagazineCardStyle
    val multi = metrics.multiColumn
    val hasImage = showImages && row.imageUrl != null
    val styles = cardTextStyles(multi)
    val summary = CardSummary.displayed(row.summaryText)
    val textPadding = if (multi) style.TILE_TEXT_PADDING else style.SINGLE_TEXT_PADDING

    val metaRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = style.META_HEIGHT).padding(start = textPadding), verticalAlignment = Alignment.CenterVertically) {
            SourceMeta(row, nowMillis, Modifier.weight(1f), stacked = multi)
            if (metrics.compact) {
                if (row.isSaved) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark),
                        contentDescription = stringResource(R.string.action_unsave),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                moreButton()
            } else {
                bookmarkButton()
                moreButton()
            }
        }
    }

    ElevatedCard(modifier.fillMaxWidth().then(if (rowHeight != null) Modifier.height(rowHeight) else Modifier)) {
        val textBlock: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Text(
                    text = row.title.ifBlank { stringResource(R.string.untitled) },
                    style = styles.title,
                    fontWeight = titleWeight(row),
                    color = if (row.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = style.TITLE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary.isNotEmpty()) {
                    Spacer(Modifier.height(style.SUMMARY_GAP))
                    Text(
                        text = summary,
                        style = styles.summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = summaryLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("card_summary"),
                    )
                }
            }
        }
        if (metrics.horizontal) {
            // Wide card: a small picture at the left (about a quarter of the width, within the size of a list thumbnail and a
            // little more on a wide screen), headline and summary beside it. The picture never makes the card taller than its text needs.
            val thumb = CardGridLayout.wideThumbnailWidthDp(metrics.widthDp).dp
            // The source row sits beside the picture too, so the picture spans the whole card and adds no height of its own.
            Row(clickModifier.height(IntrinsicSize.Min)) {
                if (hasImage) {
                    CardImage(row.imageUrl, imageLoader, Modifier.width(thumb).heightIn(min = thumb * 3 / 4).fillMaxHeight())
                }
                Column(Modifier.weight(1f)) {
                    textBlock(Modifier.padding(start = textPadding, end = textPadding, top = style.SINGLE_TEXT_TOP, bottom = style.TEXT_BOTTOM))
                    Spacer(Modifier.weight(1f))
                    metaRow()
                }
            }
        } else {
            Column(clickModifier) {
                if (hasImage) CardImage(row.imageUrl, imageLoader, Modifier.fillMaxWidth().height(metrics.imageHeight))
                textBlock(
                    Modifier.padding(
                        start = textPadding,
                        end = textPadding,
                        top = if (multi) style.TILE_TEXT_TOP else style.SINGLE_TEXT_TOP,
                        bottom = style.TEXT_BOTTOM,
                    ),
                )
            }
        }
        if (!metrics.horizontal) {
            // A tile shorter than its row: the free space sits above the source row, so the source rows of a row line up.
            if (rowHeight != null) Spacer(Modifier.weight(1f))
            metaRow()
        }
    }
}

/** A tile of the Bento layout: its shape and its size in dp (fixed by the grid of cells). */
@Immutable
class BentoCell internal constructor(internal val shape: BentoShape, val widthDp: Float, val heightDp: Float)

/**
 * A tile of the Bento layout (fixed size, set by the cell grid). Small: a picture over the headline. Wide: the picture at the left,
 * headline and summary beside it. Tall and big: a large picture over the headline and as much summary as fits. Line limits are
 * worked out from the tile's height and the text size so that no line is cut; without a picture the text takes the space.
 */
@Composable
private fun BentoCard(
    row: ArticleRow,
    showImages: Boolean,
    imageLoader: ImageLoader,
    nowMillis: Long,
    cell: BentoCell,
    bookmarkButton: @Composable () -> Unit,
    moreButton: @Composable () -> Unit,
    modifier: Modifier,
    clickModifier: Modifier,
) {
    val style = MagazineCardStyle
    val styles = cardTextStyles(multi = true)
    val density = LocalDensity.current
    val hasImage = showImages && row.imageUrl != null
    val summary = CardSummary.displayed(row.summaryText)
    val titleLineDp = with(density) { styles.title.lineHeight.toDp().value }
    val summaryLineDp = with(density) { styles.summary.lineHeight.toDp().value }
    val metaDp = style.META_HEIGHT.value
    val padding = style.TILE_TEXT_PADDING
    val compact = CardGridLayout.isCompact(cell.widthDp)
    val textTop = style.TILE_TEXT_TOP.value
    val gap = style.SUMMARY_GAP.value

    /** Lines of headline and summary that fit [textDp] of height: headline first (up to 3), the rest for the summary. */
    fun lines(textDp: Float): Pair<Int, Int> {
        val title = (textDp / titleLineDp).toInt().coerceIn(1, style.TITLE_MAX_LINES)
        val rest = textDp - title * titleLineDp - gap
        val summaryLines = if (summary.isEmpty() || rest < summaryLineDp) 0 else (rest / summaryLineDp).toInt().coerceAtMost(style.SUMMARY_MAX_LINES_IN_FREE_SPACE)
        return title to summaryLines
    }

    val text: @Composable (Modifier, Int, Int) -> Unit = { m, titleLines, summaryLines ->
        Column(m.clipToBounds()) {
            Text(
                text = row.title.ifBlank { stringResource(R.string.untitled) },
                style = styles.title,
                fontWeight = titleWeight(row),
                color = if (row.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (summaryLines > 0) {
                Spacer(Modifier.height(style.SUMMARY_GAP))
                Text(
                    text = summary,
                    style = styles.summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = summaryLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("card_summary"),
                )
            }
        }
    }
    val meta: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().height(style.META_HEIGHT).padding(start = padding), verticalAlignment = Alignment.CenterVertically) {
            SourceMeta(row, nowMillis, Modifier.weight(1f), stacked = true)
            if (compact) moreButton() else {
                bookmarkButton()
                moreButton()
            }
        }
    }
    ElevatedCard(modifier.size(cell.widthDp.dp, cell.heightDp.dp)) {
        when {
            cell.shape.horizontal -> {
                val picture = if (hasImage) minOf(cell.widthDp * 0.4f, cell.heightDp * 1.2f) else 0f
                Row(clickModifier.fillMaxHeight()) {
                    if (hasImage) CardImage(row.imageUrl, imageLoader, Modifier.width(picture.dp).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        val (t, s) = lines(cell.heightDp - textTop - style.TEXT_BOTTOM.value - metaDp)
                        text(Modifier.weight(1f).padding(start = padding, end = padding, top = style.TILE_TEXT_TOP), t, s)
                        meta()
                    }
                }
            }
            else -> {
                val picture = when {
                    !hasImage -> 0f
                    cell.shape == BentoShape.SMALL -> cell.widthDp / CardGridLayout.TILE_IMAGE_RATIO
                    else -> cell.heightDp * 0.5f
                }
                Column(clickModifier.weight(1f)) {
                    if (hasImage) CardImage(row.imageUrl, imageLoader, Modifier.fillMaxWidth().height(picture.dp))
                    val (t, s) = lines(cell.heightDp - picture - textTop - style.TEXT_BOTTOM.value - metaDp)
                    text(Modifier.weight(1f).padding(start = padding, end = padding, top = style.TILE_TEXT_TOP), t, s)
                }
                meta()
            }
        }
    }
}

@Composable
private fun CardImage(url: String?, imageLoader: ImageLoader, modifier: Modifier) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).testTag("card_image")) {
        AsyncImage(
            model = url,
            contentDescription = null,
            imageLoader = imageLoader,
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun ArticleTitle(row: ArticleRow, fontSize: Int, lineHeight: Int, maxLines: Int) {
    Text(
        text = row.title.ifBlank { stringResource(R.string.untitled) },
        style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize.sp, lineHeight = lineHeight.sp),
        fontWeight = if (row.isRead) FontWeight.Normal else FontWeight.SemiBold,
        color = if (row.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MetaText(row: ArticleRow, nowMillis: Long, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SourceIcon(row.feedId, row.feedName, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = metaLine(row, nowMillis),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Source icon, then the name over the time (several columns) or "name · time" on one line (single column). */
@Composable
private fun SourceMeta(row: ArticleRow, nowMillis: Long, modifier: Modifier, stacked: Boolean) {
    if (!stacked) {
        MetaText(row, nowMillis, modifier)
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SourceIcon(row.feedId, row.feedName, size = 20.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            val style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 15.sp)
            Text(sourceName(row), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(timeText(row, nowMillis), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Thumbnail(url: String?, size: Dp, imageLoader: ImageLoader, bottomPadding: Dp = 4.dp) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    Box(
        Modifier
            .padding(bottom = bottomPadding)
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("thumbnail"),
    ) {
        if (url != null) {
            // Fixed-size box: loading, failure and success never change the row height.
            AsyncImage(
                model = url,
                contentDescription = null,
                imageLoader = imageLoader,
                placeholder = placeholder,
                error = placeholder,
                fallback = placeholder,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

@Composable
private fun sourceName(row: ArticleRow): String = row.feedName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unknown_source)

@Composable
private fun timeText(row: ArticleRow, nowMillis: Long): String {
    val context = LocalContext.current
    val time = row.publishedAt?.let { published ->
        if (published in (nowMillis - DateUtils.DAY_IN_MILLIS)..nowMillis) {
            DateUtils.getRelativeTimeSpanString(published, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()
        } else {
            DateUtils.formatDateTime(
                context,
                published,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_NUMERIC_DATE,
            )
        }
    } ?: stringResource(R.string.date_unknown)
    return if (row.feedSubscribed) time else time + " · " + stringResource(R.string.unsubscribed_label)
}

@Composable
private fun metaLine(row: ArticleRow, nowMillis: Long): String = sourceName(row) + " · " + timeText(row, nowMillis)
