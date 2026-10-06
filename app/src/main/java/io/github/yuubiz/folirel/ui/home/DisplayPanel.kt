package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder

@Composable
internal fun filterLabel(filter: ReadFilter): String = stringResource(
    when (filter) {
        ReadFilter.ALL -> R.string.filter_all
        ReadFilter.UNREAD -> R.string.filter_unread
        ReadFilter.SAVED -> R.string.filter_saved
    },
)

@Composable
internal fun sortLabel(sort: SortOrder): String =
    stringResource(if (sort == SortOrder.NEWEST) R.string.sort_newest else R.string.sort_oldest)

@Composable
internal fun listModeLabel(mode: ListMode): String = stringResource(
    when (mode) {
        ListMode.LIST -> R.string.list_mode_list
        ListMode.COMPACT_LIST -> R.string.list_mode_compact_list
        ListMode.MAGAZINE -> R.string.list_mode_magazine
        ListMode.MASONRY -> R.string.list_mode_masonry
        ListMode.BENTO -> R.string.list_mode_bento
    },
)

/**
 * The one place for what the list shows and how: the range (all / unread / saved), the order, and the layout (P1-DENSE). It
 * replaces the row of chips that was always on screen. A choice applies at once; opening and closing the panel changes nothing.
 * [layoutSection] is the part for the layout, which knows what it writes to (the global default or the category).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DisplayPanel(
    filter: ReadFilter,
    sort: SortOrder,
    onFilter: (ReadFilter) -> Unit,
    onSort: (SortOrder) -> Unit,
    onDismiss: () -> Unit,
    layoutSection: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("display_panel"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.display_title), style = MaterialTheme.typography.titleMedium)
            SectionTitle(stringResource(R.string.display_range))
            Choice(
                options = listOf(ReadFilter.ALL, ReadFilter.UNREAD, ReadFilter.SAVED),
                selected = filter,
                label = { filterLabel(it) },
                tag = { "filter_${it.name}" },
                onSelect = onFilter,
            )
            SectionTitle(stringResource(R.string.display_sort))
            Choice(
                options = listOf(SortOrder.NEWEST, SortOrder.OLDEST),
                selected = sort,
                label = { sortLabel(it) },
                tag = { "sort_${it.name}" },
                onSelect = onSort,
            )
            layoutSection()
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * A short single choice. Segmented buttons when the labels fit; selectable rows (radio buttons, 48 dp high) when the width is
 * narrow or the text is large, where a row of buttons would cut the labels (Japanese, 200 % text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Choice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    tag: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    /** Long labels (the layouts): always rows. */
    forceRows: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val roomPerOption = maxWidth / options.size
        val segmented = !forceRows && fontScale <= 1.15f && roomPerOption >= 96.dp && options.size <= 3
        if (segmented) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        enabled = enabled,
                        modifier = Modifier.testTag(tag(option)),
                        label = { Text(label(option), maxLines = 1) },
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = option == selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) })
                            .testTag(tag(option)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null, enabled = enabled)
                        Text(label(option), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}
