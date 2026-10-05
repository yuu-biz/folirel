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
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.CategoryPreference
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.ResolvedLayout

/** A choice that is either "inherit" or a value: the first row of each group of the sheet. */
internal sealed interface Pick<out T> {
    data object Inherit : Pick<Nothing>
    data class Value<T>(val value: T) : Pick<T>
}

/** What an inherited setting would be right now, in words ("Use the default (List)"). */
@Composable
private fun inheritLabel(current: String) = stringResource(R.string.layout_inherit, current)

@Composable
internal fun cardStyleLabel(style: CardLayoutStyle): String =
    stringResource(if (style == CardLayoutStyle.MIXED) R.string.card_style_mixed else R.string.card_style_even)

@Composable
internal fun cardColumnsLabel(columns: Int): String =
    if (columns == 0) stringResource(R.string.card_columns_auto) else pluralStringResource(R.plurals.card_columns_n, columns, columns)

/**
 * The display of one category (P1-LAYOUT): the three things it can override, each with "use the default" as the first choice. The
 * arrangement and the columns count only in the Magazine layout; for another layout they are shown as not in use and are kept (not
 * erased) for when the layout is Magazine again. "Reset" puts these three back to the default and touches nothing else.
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
    val listPick: Pick<ListMode> = preference?.listLayout?.let { Pick.Value(it) } ?: Pick.Inherit
    val stylePick: Pick<CardLayoutStyle> = preference?.cardStyle?.let { Pick.Value(it) } ?: Pick.Inherit
    val columnsPick: Pick<Int> = preference?.cardColumns?.let { Pick.Value(it) } ?: Pick.Inherit
    val effectiveMode = preference?.listLayout ?: global.listMode
    val magazine = effectiveMode == ListMode.MAGAZINE
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("category_layout_sheet"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.category_display_of, categoryName), style = MaterialTheme.typography.titleMedium)
            SectionTitle(stringResource(R.string.settings_list_mode))
            Choice(
                options = listOf<Pick<ListMode>>(Pick.Inherit) + ListMode.entries.map { Pick.Value(it) },
                selected = listPick,
                label = { if (it is Pick.Value) listModeLabel(it.value) else inheritLabel(listModeLabel(global.listMode)) },
                tag = { if (it is Pick.Value) "cl_list_${it.value.name}" else "cl_list_INHERIT" },
                onSelect = { onList((it as? Pick.Value)?.value) },
                forceRows = true,
            )
            SectionTitle(stringResource(R.string.settings_card_style))
            if (!magazine) Note(stringResource(R.string.layout_magazine_only))
            Choice(
                options = listOf<Pick<CardLayoutStyle>>(Pick.Inherit) + CardLayoutStyle.entries.map { Pick.Value(it) },
                selected = stylePick,
                label = { if (it is Pick.Value) cardStyleLabel(it.value) else inheritLabel(cardStyleLabel(global.cardStyle)) },
                tag = { if (it is Pick.Value) "cl_style_${it.value.name}" else "cl_style_INHERIT" },
                onSelect = { onStyle((it as? Pick.Value)?.value) },
                enabled = magazine,
                forceRows = true,
            )
            SectionTitle(stringResource(R.string.settings_card_columns))
            if (!magazine) Note(stringResource(R.string.layout_magazine_only))
            Choice(
                options = listOf<Pick<Int>>(Pick.Inherit) + listOf(0, 1, 2, 3).map { Pick.Value(it) },
                selected = columnsPick,
                label = { if (it is Pick.Value) cardColumnsLabel(it.value) else inheritLabel(cardColumnsLabel(global.cardColumns)) },
                tag = { if (it is Pick.Value) "cl_cols_${it.value}" else "cl_cols_INHERIT" },
                onSelect = { onColumns((it as? Pick.Value)?.value) },
                enabled = magazine,
                forceRows = true,
            )
            Note(stringResource(R.string.layout_columns_note))
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
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
}
