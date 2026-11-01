package io.github.yuubiz.folirel.ui.subscriptions

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.model.CategoryNode
import kotlinx.coroutines.launch

/**
 * The order of the category tabs (P1-ORG): pinned categories first, then the others, each group in the reader's order. This is
 * the order of the tabs only; the hierarchy (paths, which feed is in which category) is not changed here.
 *
 * Dragging starts at the handle only and moves a row within its own group (the pin button is the way between groups). The order
 * being dragged is kept on the screen and saved once, when the finger is lifted: a cancelled drag or a failed write shows the
 * stored order again. Up and down are in the row's menu (and offered as accessibility actions) for readers who do not drag.
 */
@Composable
internal fun TabOrderList(
    vm: SubscriptionsViewModel,
    onDisplaySettings: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val structure by vm.structure.collectAsStateWithLifecycle()
    val nodes = structure.tabOrdered
    val savedPinned = nodes.filter { structure.preferences[it.id]?.isPinned == true }.map { it.id }
    val savedOthers = nodes.filter { structure.preferences[it.id]?.isPinned != true }.map { it.id }
    val byId = nodes.associateBy { it.id }
    var dragging by remember { mutableStateOf<String?>(null) }
    // The edit buffer: the stored order, rebuilt whenever it changes (or a drag is undone). While a row is being dragged a newer stored
    // order (the result of an earlier press) waits until the finger is lifted: the row under the finger is never pulled away.
    var applied by remember { mutableStateOf(savedPinned to savedOthers) }
    if (dragging == null && applied != (savedPinned to savedOthers)) applied = savedPinned to savedOthers
    var undo by remember { mutableIntStateOf(0) }
    val pinned = remember(applied.first, undo) { mutableStateListOf<String>().apply { addAll(applied.first) } }
    val others = remember(applied.second, undo) { mutableStateListOf<String>().apply { addAll(applied.second) } }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    fun groupOf(id: String) = if (id in pinned) pinned else others

    /** Swaps the dragged row with the neighbour it has passed over half of; the offset keeps the row under the finger. */
    fun swapIfPassed(id: String) {
        val group = groupOf(id)
        val index = group.indexOf(id)
        val info = listState.layoutInfo.visibleItemsInfo
        if (dragOffset > 0 && index < group.lastIndex) {
            val neighbour = info.firstOrNull { it.key == rowKey(group[index + 1]) } ?: return
            if (dragOffset > neighbour.size / 2f) {
                group.add(index + 1, group.removeAt(index))
                dragOffset -= neighbour.size
            }
        } else if (dragOffset < 0 && index > 0) {
            val neighbour = info.firstOrNull { it.key == rowKey(group[index - 1]) } ?: return
            if (-dragOffset > neighbour.size / 2f) {
                group.add(index - 1, group.removeAt(index))
                dragOffset += neighbour.size
            }
        }
    }

    fun finishDrag(id: String, save: Boolean) {
        val group = groupOf(id)
        // Where it was dropped, as "before this neighbour": applied to whatever order is stored when the write runs.
        val before = group.getOrNull(group.indexOf(id) + 1)
        dragging = null
        dragOffset = 0f
        if (!save) {
            undo++
            return
        }
        scope.launch {
            if (!vm.placeCategory(id, before)) {
                undo++
                vm.reportFailure()
            }
        }
    }

    @Composable
    fun RowFor(id: String, canMoveUp: Boolean, canMoveDown: Boolean, modifier: Modifier) {
        val node = byId[id] ?: return
        val isPinned = structure.preferences[id]?.isPinned == true
        TabOrderRow(
            node = node,
            pinned = isPinned,
            dragged = dragging == id,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onPin = { vm.setCategoryPinned(id, !isPinned) },
            onMove = { delta -> vm.moveCategory(id, delta) },
            onDisplaySettings = { onDisplaySettings(id) },
            onDragStart = {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                dragging = id
                dragOffset = 0f
            },
            onDrag = { dy -> dragOffset += dy; swapIfPassed(id) },
            onDragEnd = {
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                finishDrag(id, save = true)
            },
            onDragCancel = { finishDrag(id, save = false) },
            modifier = modifier.graphicsLayer { translationY = if (dragging == id) dragOffset else 0f }.zIndex(if (dragging == id) 1f else 0f),
        )
    }

    // The row under the finger follows the finger; only the others slide into their new places.
    fun LazyItemScope.rowMotion(id: String) = if (dragging == id) Modifier else Modifier.animateItem()

    LazyColumn(modifier.fillMaxWidth().testTag("tab_order_list"), state = listState) {
        item(key = "intro") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.tab_order_title), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = vm::resetCategoryTabOrder, modifier = Modifier.testTag("tab_order_reset")) {
                    Text(stringResource(R.string.tab_order_reset))
                }
                Text(stringResource(R.string.tab_order_reset_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (pinned.isNotEmpty()) {
            item(key = "header-pinned") { GroupHeader(stringResource(R.string.tab_order_pinned), Modifier.animateItem()) }
            groupRows(pinned) { id, index -> RowFor(id, index > 0, index < pinned.lastIndex, rowMotion(id)) }
        }
        item(key = "header-others") { GroupHeader(stringResource(R.string.tab_order_others), Modifier.animateItem()) }
        groupRows(others) { id, index -> RowFor(id, index > 0, index < others.lastIndex, rowMotion(id)) }
    }
}

private fun rowKey(id: String) = "row-$id"

private fun androidx.compose.foundation.lazy.LazyListScope.groupRows(
    ids: List<String>,
    row: @Composable androidx.compose.foundation.lazy.LazyItemScope.(String, Int) -> Unit,
) {
    ids.forEachIndexed { index, id ->
        item(key = rowKey(id)) { row(id, index) }
    }
}

@Composable
private fun GroupHeader(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TabOrderRow(
    node: CategoryNode,
    pinned: Boolean,
    dragged: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPin: () -> Unit,
    onMove: (Int) -> Unit,
    onDisplaySettings: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier,
) {
    val up = stringResource(R.string.tab_order_move_up)
    val down = stringResource(R.string.tab_order_move_down)
    var menu by remember { mutableStateOf(false) }
    val startNow by rememberUpdatedState(onDragStart)
    val dragNow by rememberUpdatedState(onDrag)
    val endNow by rememberUpdatedState(onDragEnd)
    val cancelNow by rememberUpdatedState(onDragCancel)
    val parent = node.path.dropLast(1).joinToString(" / ")
    Surface(
        tonalElevation = if (dragged) 6.dp else 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("tab_order_item_${node.id}")
            .semantics {
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction(up) { onMove(-1); true })
                    if (canMoveDown) add(CustomAccessibilityAction(down) { onMove(1); true })
                }
            },
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp)) {
                // Dragging starts here and only here; a press elsewhere on the row is an ordinary press.
                val handleDescription = stringResource(R.string.tab_order_handle, node.display)
                Box(
                    Modifier
                        .size(48.dp)
                        .testTag("tab_order_handle_${node.id}")
                        .semantics { contentDescription = handleDescription }
                        .pointerInput(node.id) {
                            // The gesture outlives recompositions: it must call the current callbacks (the edit buffer is rebuilt).
                            detectDragGestures(
                                onDragStart = { startNow() },
                                onDrag = { change, amount -> change.consume(); dragNow(amount.y) },
                                onDragEnd = { endNow() },
                                onDragCancel = { cancelNow() },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(node.path.last(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val line = buildString {
                        if (parent.isNotEmpty()) append(parent)
                    }
                    if (line.isNotEmpty()) {
                        Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        if (node.subtreeFeedCount > 0) pluralStringResource(R.plurals.category_feed_count, node.subtreeFeedCount, node.subtreeFeedCount)
                        else stringResource(R.string.tab_order_no_feeds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onPin, modifier = Modifier.testTag("tab_order_pin_${node.id}")) {
                    Icon(
                        painterResource(R.drawable.ic_push_pin),
                        contentDescription = stringResource(if (pinned) R.string.tab_order_unpin else R.string.tab_order_pin),
                        tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("tab_order_menu_${node.id}")) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(up) },
                            enabled = canMoveUp,
                            onClick = { menu = false; onMove(-1) },
                            modifier = Modifier.testTag("tab_order_up_${node.id}"),
                        )
                        DropdownMenuItem(
                            text = { Text(down) },
                            enabled = canMoveDown,
                            onClick = { menu = false; onMove(1) },
                            modifier = Modifier.testTag("tab_order_down_${node.id}"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.category_display_settings)) },
                            onClick = { menu = false; onDisplaySettings() },
                            modifier = Modifier.testTag("tab_order_display_${node.id}"),
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
