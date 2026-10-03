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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    val magazine = mode == ListMode.MAGAZINE
    val titleText = row.title.ifBlank { stringResource(R.string.untitled) }
    val saveLabel = stringResource(if (row.isSaved) R.string.action_unsave else R.string.action_save)
    val readLabel = stringResource(if (row.isRead) R.string.action_mark_unread else R.string.action_mark_read)
    val shareLabel = stringResource(R.string.action_share)
    val openLabel = stringResource(R.string.action_open_article)
    val menuLabel = stringResource(R.string.action_more)

    Column(modifier) {
        Row(
            modifier = Modifier
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
                .padding(start = 12.dp, end = 4.dp, top = 8.dp)
                .heightIn(min = if (magazine) 72.dp else 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = if (magazine) 16.sp else 15.sp,
                        lineHeight = if (magazine) 22.sp else 20.sp,
                    ),
                    fontWeight = if (row.isRead) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (row.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (magazine) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (magazine && row.summaryText.isNotBlank()) {
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
            if (showImages) {
                Thumbnail(row.imageUrl, if (magazine) 96.dp else 56.dp, imageLoader)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = metaLine(row, nowMillis),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun Thumbnail(url: String?, size: Dp, imageLoader: ImageLoader) {
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    Box(
        Modifier
            .padding(bottom = 4.dp)
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
