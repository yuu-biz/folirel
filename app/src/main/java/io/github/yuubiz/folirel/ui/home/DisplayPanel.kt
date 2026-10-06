package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.ui.common.Choice
import io.github.yuubiz.folirel.ui.common.SectionTitle
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
