package io.github.yuubiz.feeddeck.ui.home

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
import io.github.yuubiz.feeddeck.R
import io.github.yuubiz.feeddeck.model.ArticleRow
import io.github.yuubiz.feeddeck.model.ListMode

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
 * Size of one card in the card list: how much of the row it spans and how wide it is (dp). [wide] marks the
 * full-width card of a mixed (magazine) page, drawn with the image beside the text.
 */
@Immutable
class CardMetrics(
    /** Sixths of the row this card spans (2 = a third, 3 = a half, 4 = two thirds, 6 = full width). */
    val span: Int,
    val widthDp: Float,
    val wide: Boolean = false,
    val rowHasImage: Boolean = true,
    /** The image height every tile of the page shares. */
    val imageHeightDp: Float = CardGridLayout.imageHeightDp(widthDp),
) {
    val multiColumn: Boolean get() = span < MagazinePlan.GRID_SPANS
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

    if (mode == ListMode.CARD) {
        ArticleCard(row, showImages, imageLoader, nowMillis, card, bookmarkButton, moreButton, modifier, rowModifier)
        return
    }
    Column(modifier) {
        if (mode == ListMode.MAGAZINE) {
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
 * Card (UX-GRID). Single column: image on top, headline (3 lines), summary (2 lines), source/time and actions; a
 * single very wide column puts the image on the left instead. Several columns: every card has the same height
 * (image area + a fixed text area), so rows stay even whatever the headline lengths; an article without an image
 * uses that image area for more summary text, and the thumbnail area is fixed while loading or after a failure, so
 * the list never jumps. The image height is capped ([CardGridLayout.MAX_IMAGE_HEIGHT_DP]). Images off: no image
 * area and no request.
 */
@Composable
private fun ArticleCard(
    row: ArticleRow,
    showImages: Boolean,
    imageLoader: ImageLoader,
    nowMillis: Long,
    metrics: CardMetrics,
    bookmarkButton: @Composable () -> Unit,
    moreButton: @Composable () -> Unit,
    modifier: Modifier,
    clickModifier: Modifier,
) {
    val multi = metrics.multiColumn
    val hasImage = showImages && row.imageUrl != null
    // One type size for all tiles (like the reference magazine page); larger only for a card of its own.
    val titleSize = if (!multi) 18 else 16
    val titleLine = if (!multi) 24 else 21
    val summarySize = if (!multi) 14 else 13
    val summaryLine = if (!multi) 20 else 18
    // Enough of the article to tell what it is about: four lines on every card, five beside a wide card's image.
    val baseSummaryLines = 4
    val density = LocalDensity.current
    val textPadding = if (multi) 10.dp else 14.dp
    // Several columns: the text area has a fixed height of 3 title lines + 2 summary lines, plus the image height
    // for an article that has no image (so all cards of a row are equally tall).
    val fixedTextHeight: Dp? = if (multi) {
        val base = with(density) { (titleLine * 3 + 4 + summaryLine * baseSummaryLines).sp.toDp() }
        when {
            // An article without an image in a row where its neighbours have one uses that area for more text.
            showImages && !hasImage && metrics.rowHasImage -> base + metrics.imageHeight
            // A row with no image at all stays compact instead of leaving a blank picture area.
            !metrics.rowHasImage -> base + with(density) { (summaryLine * 2).sp.toDp() }
            else -> base
        }
    } else null
    val summaryLines = if (multi) {
        val area = fixedTextHeight ?: 0.dp
        val titleHeight = with(density) { (titleLine * 3 + 4).sp.toDp() }
        val lineDp = with(density) { summaryLine.sp.toDp() }
        ((area - titleHeight) / lineDp).toInt().coerceAtLeast(baseSummaryLines)
    } else if (metrics.horizontal) 5 else baseSummaryLines

    ElevatedCard(modifier.fillMaxWidth()) {
        val textBlock: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                ArticleTitle(row, fontSize = titleSize, lineHeight = titleLine, maxLines = 3)
                if (row.summaryText.isNotBlank()) {
                    Spacer(Modifier.padding(top = 4.dp))
                    Text(
                        text = row.summaryText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = summarySize.sp, lineHeight = summaryLine.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = summaryLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (metrics.horizontal) {
            // Wide card: image on the left (about 40 % of the width, at most 280 dp), headline and summary on the right.
            val imageWidth = minOf(metrics.widthDp * 0.4f, 280f).dp
            Row(clickModifier.height(IntrinsicSize.Min)) {
                if (hasImage) {
                    CardImage(row.imageUrl, imageLoader, Modifier.width(imageWidth).heightIn(min = imageWidth * 9 / 16).fillMaxHeight())
                }
                textBlock(Modifier.weight(1f).padding(start = 14.dp, end = 14.dp, top = 12.dp))
            }
        } else {
            Column(clickModifier) {
                if (hasImage) CardImage(row.imageUrl, imageLoader, Modifier.fillMaxWidth().height(metrics.imageHeight))
                textBlock(
                    Modifier
                        .padding(start = textPadding, end = textPadding, top = if (multi) 8.dp else 12.dp)
                        .then(if (fixedTextHeight != null) Modifier.height(fixedTextHeight) else Modifier),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = textPadding), verticalAlignment = Alignment.CenterVertically) {
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
