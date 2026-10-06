package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.ui.common.Choice
import io.github.yuubiz.folirel.ui.common.SectionTitle
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.PeriodFilter
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
internal fun periodLabel(period: PeriodFilter): String = stringResource(
    when (period) {
        PeriodFilter.ALL -> R.string.period_all
        PeriodFilter.HOURS_24 -> R.string.period_24h
        PeriodFilter.DAYS_3 -> R.string.period_3d
        PeriodFilter.DAYS_7 -> R.string.period_7d
        PeriodFilter.DAYS_30 -> R.string.period_30d
    },
)

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
 * The one place for what the list shows and how: the range (all / unread / saved), the period, the hidden keywords, the order,
 * and the layout (P1-DENSE). It replaces the row of chips that was always on screen. A choice applies at once; opening and closing
 * the panel changes nothing. [layoutSection] is the part for the layout, which knows what it writes to (the global default or the
 * category).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DisplayPanel(
    filter: ReadFilter,
    sort: SortOrder,
    period: PeriodFilter,
    hiddenCount: Int,
    hiddenEnabled: Boolean,
    onFilter: (ReadFilter) -> Unit,
    onSort: (SortOrder) -> Unit,
    onPeriod: (PeriodFilter) -> Unit,
    onHiddenEnabled: (Boolean) -> Unit,
    onManageHidden: () -> Unit,
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
            SectionTitle(stringResource(R.string.display_period))
            PeriodChoice(period, onPeriod)
            HiddenKeywordsRow(hiddenCount, hiddenEnabled, onHiddenEnabled, onManageHidden)
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

/** Five short choices as one wrapping row of chips (denser than five radio rows); a chip keeps the 48 dp touch height. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodChoice(selected: PeriodFilter, onSelect: (PeriodFilter) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PeriodFilter.entries.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(periodLabel(option), maxLines = 1) },
                modifier = Modifier.semantics { role = Role.RadioButton }.testTag("period_${option.name}"),
            )
        }
    }
}

/** The way into the hidden keywords: how many there are, the switch (only when there is something to switch) and "manage". */
@Composable
private fun HiddenKeywordsRow(count: Int, enabled: Boolean, onEnabled: (Boolean) -> Unit, onManage: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (count == 0) stringResource(R.string.hidden_keywords_none) else pluralStringResource(R.plurals.hidden_keywords_count, count, count),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).testTag("hidden_keywords_summary"),
        )
        if (count > 0) {
            Switch(checked = enabled, onCheckedChange = onEnabled, modifier = Modifier.testTag("hidden_keywords_switch"))
        }
        TextButton(onClick = onManage, modifier = Modifier.heightIn(min = 48.dp).testTag("hidden_keywords_manage")) {
            Text(stringResource(R.string.hidden_keywords_manage))
        }
    }
}
