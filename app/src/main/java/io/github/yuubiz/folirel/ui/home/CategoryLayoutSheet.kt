package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.CategoryPreference
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.ResolvedLayout
import io.github.yuubiz.folirel.ui.common.Choice
import io.github.yuubiz.folirel.ui.common.LayoutPicker
import io.github.yuubiz.folirel.ui.common.Pick
import io.github.yuubiz.folirel.ui.common.SectionTitle
import io.github.yuubiz.folirel.ui.common.SelectionRow
import io.github.yuubiz.folirel.ui.common.listModeName

/** What an inherited setting would be right now, in words ("Use the default (List)"). */
@Composable
internal fun inheritLabel(current: String) = stringResource(R.string.layout_inherit, current)

@Composable
internal fun cardStyleLabel(style: CardLayoutStyle): String =
    stringResource(if (style == CardLayoutStyle.MIXED) R.string.card_style_mixed else R.string.card_style_even)

@Composable
internal fun cardColumnsLabel(columns: Int): String =
    if (columns == 0) stringResource(R.string.card_columns_auto) else pluralStringResource(R.plurals.card_columns_n, columns, columns)

/**
 * The display of one category (P1-LAYOUT, P2-C): the three things it can override, each with "use the default" as the first choice.
 * It uses the same layout picker and the same names as Settings > Layout and navigation. Only the settings the layout has are shown: the
 * arrangement (Mixed or Even) is Magazine's alone, the columns belong to Magazine, Masonry and Bento, and a list has neither. What
 * is hidden is kept (not erased) for when the layout changes. "Reset" puts these three back to the default and touches nothing else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryLayoutSheet(
    categoryName: String,
    preference: CategoryPreference?,
    global: ResolvedLayout,
    onList: (ListMode?) -> Unit,
    onStyle: (CardLayoutStyle?) -> Unit,
    onColumns: (Int?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stylePick: Pick<CardLayoutStyle> = preference?.cardStyle?.let { Pick.Value(it) } ?: Pick.Inherit
    val columnsPick: Pick<Int> = preference?.cardColumns?.let { Pick.Value(it) } ?: Pick.Inherit
    val effectiveMode = preference?.listLayout ?: global.listMode
    val magazine = effectiveMode == ListMode.MAGAZINE
    val grid = magazine || effectiveMode == ListMode.MASONRY || effectiveMode == ListMode.BENTO
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("category_layout_sheet"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.category_display_of, categoryName), style = MaterialTheme.typography.titleMedium)
            SectionTitle(stringResource(R.string.display_layout))
            LayoutPicker(
                selected = preference?.listLayout,
                onSelect = onList,
                inheritLabel = inheritLabel(listModeName(global.listMode)),
                tagPrefix = "cl_list_",
            )
            if (magazine) {
                SectionTitle(stringResource(R.string.settings_card_style))
                Choice(
                    options = listOf<Pick<CardLayoutStyle>>(Pick.Inherit) + CardLayoutStyle.entries.map { Pick.Value(it) },
                    selected = stylePick,
                    label = { if (it is Pick.Value) cardStyleLabel(it.value) else inheritLabel(cardStyleLabel(global.cardStyle)) },
                    tag = { if (it is Pick.Value) "cl_style_${it.value.name}" else "cl_style_INHERIT" },
                    onSelect = { onStyle((it as? Pick.Value)?.value) },
                    forceRows = true,
                )
            } else {
                Note(stringResource(if (grid) R.string.layout_style_magazine_only else R.string.layout_options_none), Modifier.padding(top = 8.dp).testTag("cl_style_not_used"))
            }
            if (grid) {
                SelectionRow(
                    title = stringResource(R.string.settings_card_columns),
                    valueLabel = (columnsPick as? Pick.Value)?.let { cardColumnsLabel(it.value) } ?: inheritLabel(cardColumnsLabel(global.cardColumns)),
                    options = listOf<Pick<Int>>(Pick.Inherit) + AppSettings.CARD_COLUMNS.map { Pick.Value(it) },
                    selected = columnsPick,
                    optionLabel = { if (it is Pick.Value) cardColumnsLabel(it.value) else inheritLabel(cardColumnsLabel(global.cardColumns)) },
                    onSelect = { onColumns((it as? Pick.Value)?.value) },
                    modifier = Modifier.testTag("cl_cols_row"),
                    optionTag = { if (it is Pick.Value) "cl_cols_${it.value}" else "cl_cols_INHERIT" },
                    dialogNote = stringResource(R.string.layout_columns_note),
                    sidePadding = 0.dp,
                )
            }
            Note(stringResource(R.string.layout_hidden_kept_note))
            OutlinedButton(
                onClick = onReset,
                enabled = preference?.overridesLayout == true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 16.dp).testTag("cl_reset"),
            ) { Text(stringResource(R.string.category_display_reset)) }
            Note(stringResource(R.string.category_display_reset_note))
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(vertical = 2.dp))
}
