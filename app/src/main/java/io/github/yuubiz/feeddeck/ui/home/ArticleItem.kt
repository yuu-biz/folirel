package io.github.yuubiz.feeddeck.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
    val buttons: @Composable () -> Unit = {
        IconButton(onClick = { actions.toggleSaved(row) }) {
            Icon(
                painter = painterResource(if (row.isSaved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border),
                contentDescription = saveLabel,
                tint = if (row.isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
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

    if (mode == ListMode.CARD) {
        ArticleCard(row, showImages, imageLoader, nowMillis, rowModifier, buttons, modifier)
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
 * Large card (UX-02): wide 16:9 image on top, headline (3 lines), summary (2 lines), source/time and actions.
 * Without an image URL (or with images off) it is a text card with no empty frame; with a URL the 16:9 area is
 * fixed while loading or after a failure, so the list does not jump. Images off: no image area, no request.
 */
@Composable
private fun ArticleCard(
    row: ArticleRow,
    showImages: Boolean,
    imageLoader: ImageLoader,
    nowMillis: Long,
    rowModifier: Modifier,
    buttons: @Composable () -> Unit,
    modifier: Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(rowModifier) {
            if (showImages && row.imageUrl != null) {
                val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant).testTag("card_image"),
                ) {
                    AsyncImage(
                        model = row.imageUrl,
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
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
                ArticleTitle(row, fontSize = 18, lineHeight = 24, maxLines = 3)
                if (row.summaryText.isNotBlank()) {
                    Spacer(Modifier.padding(top = 4.dp))
                    Text(
                        text = row.summaryText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MetaText(row, nowMillis, Modifier.weight(1f))
            buttons()
        }
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
    Text(
        text = metaLine(row, nowMillis),
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
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
private fun metaLine(row: ArticleRow, nowMillis: Long): String {
    val context = LocalContext.current
    val parts = mutableListOf<String>()
    parts += row.feedName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unknown_source)
    parts += row.publishedAt?.let { published ->
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
    if (!row.feedSubscribed) parts += stringResource(R.string.unsubscribed_label)
    return parts.joinToString(" · ")
}
