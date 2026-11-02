package io.github.yuubiz.folirel.ui.home

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.data.preferences.SwipeAction
import io.github.yuubiz.folirel.data.preferences.SwipeActions
import io.github.yuubiz.folirel.model.ArticleRow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A row of the List and Compact list layouts that a horizontal swipe toggles read or saved ([SwipeActions]). The row follows the
 * finger and always snaps back; it leaves the list only when the change takes it out of the filter on screen, and then the list
 * animates it out and the snackbar offers Undo, exactly as for the same change from the menu. Past the threshold a haptic tick says
 * that letting go will act; a swipe released before it changes nothing. While swipe actions are on, a horizontal drag that starts on
 * a row belongs to the row, not to the category pager. The TalkBack custom actions of the row are untouched.
 */
@Composable
fun SwipeableArticle(
    row: ArticleRow,
    swipe: SwipeActions,
    actions: ArticleActions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!swipe.enabled) {
        Box(modifier) { content() }
        return
    }
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(row)
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val threshold = with(LocalDensity.current) { minOf(width * THRESHOLD_FRACTION, THRESHOLD_MAX.toPx()) }
    val dragState = rememberDraggableState { delta ->
        val before = offset
        offset = (before + delta).coerceIn(-width.toFloat(), width.toFloat())
        if (abs(before) < threshold && abs(offset) >= threshold) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }
    Box(
        modifier
            .onSizeChanged { width = it.width }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                // Positive offsets point toward the end of the line, so "right" in the setting is the reading direction's end.
                reverseDirection = LocalLayoutDirection.current == LayoutDirection.Rtl,
                onDragStopped = {
                    when (swipe.actionAt(offset, threshold)) {
                        SwipeAction.READ -> actions.setRead(current, !current.isRead)
                        SwipeAction.SAVED -> actions.toggleSaved(current)
                        null -> Unit
                    }
                    animate(offset, 0f) { value, _ -> offset = value }
                },
            ),
    ) {
        if (offset != 0f) {
            swipe.action(towardEnd = offset > 0f)?.let { action ->
                SwipeBackground(action, current, towardEnd = offset > 0f, armed = abs(offset) >= threshold, Modifier.matchParentSize())
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .then(if (offset != 0f) Modifier.background(MaterialTheme.colorScheme.background) else Modifier),
        ) { content() }
    }
}

/** What letting go will do, on the side the row uncovers. Drawn only, never announced: the custom actions say the same. */
@Composable
private fun SwipeBackground(action: SwipeAction, row: ArticleRow, towardEnd: Boolean, armed: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val container = if (action == SwipeAction.READ) colors.secondaryContainer else colors.primaryContainer
    val content = if (action == SwipeAction.READ) colors.onSecondaryContainer else colors.onPrimaryContainer
    val label = stringResource(
        when (action) {
            SwipeAction.READ -> if (row.isRead) R.string.action_mark_unread else R.string.action_mark_read
            SwipeAction.SAVED -> if (row.isSaved) R.string.action_unsave else R.string.action_save
        },
    )
    Row(
        modifier.background(if (armed) container else colors.surfaceVariant).padding(horizontal = 20.dp).clearAndSetSemantics {},
        horizontalArrangement = if (towardEnd) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (armed) content else colors.onSurfaceVariant
        val iconModifier = Modifier.size(22.dp)
        when {
            action == SwipeAction.SAVED ->
                Icon(painterResource(if (row.isSaved) R.drawable.ic_bookmark_border else R.drawable.ic_bookmark), null, iconModifier, tint)
            row.isRead -> Icon(Icons.Filled.MailOutline, null, iconModifier, tint)
            else -> Icon(Icons.Filled.Done, null, iconModifier, tint)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A swipe acts past this share of the row's width, and at most this far on a wide window. */
private const val THRESHOLD_FRACTION = 0.3f
private val THRESHOLD_MAX = 160.dp
