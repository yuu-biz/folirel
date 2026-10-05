package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.Structure

/** One line of the picker: where it goes, how it is called, the path above it (so two categories of one name differ), and its pin. */
private data class PickerEntry(val scope: ArticleScope, val name: String, val parentPath: String, val pinned: Boolean?)

/**
 * The jump to a category (P1-ORG): All, Uncategorized (when it applies), the pinned categories, then the others, in tab order;
 * a search over the names and the parent paths (on the device only). Choosing one ends in the same selection as a tab or the
 * drawer does; choosing the one already shown only closes the sheet. A search or a pin here changes nothing about articles.
 * The search text belongs to this sheet: it is not the article search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryPicker(
    structure: Structure,
    selected: ArticleScope,
    onChoose: (ArticleScope) -> Unit,
    onTogglePin: (String) -> Unit,
    onOrganize: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = stringResource(R.string.scope_all)
    val uncategorized = stringResource(R.string.scope_uncategorized)
    val fixed = remember(structure.hasUncategorized, all, uncategorized) {
        buildList {
            add(PickerEntry(ArticleScope.All, all, "", null))
            if (structure.hasUncategorized) add(PickerEntry(ArticleScope.Uncategorized, uncategorized, "", null))
        }
    }
    val categories = remember(structure) {
        structure.tabOrdered.map { node ->
            PickerEntry(
                ArticleScope.Category(node.id),
                node.path.last(),
                node.path.dropLast(1).joinToString(" / "),
                structure.preferences[node.id]?.isPinned == true,
            )
        }
    }
    val needle = query.trim().lowercase()
    fun matches(e: PickerEntry) = needle.isEmpty() || e.name.lowercase().contains(needle) || e.parentPath.lowercase().contains(needle)
    val shownFixed = fixed.filter(::matches)
    val shownPinned = categories.filter { it.pinned == true && matches(it) }
    val shownOthers = categories.filter { it.pinned != true && matches(it) }
    val resultCount = shownFixed.size + shownPinned.size + shownOthers.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("category_picker"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.picker_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.action_clear_search)) }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("picker_search"),
            )
            if (needle.isNotEmpty()) {
                // How many entries match (not unread articles, not feeds).
                Text(
                    if (resultCount == 0) stringResource(R.string.picker_no_match) else pluralStringResource(R.plurals.picker_results, resultCount, resultCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).testTag("picker_count"),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 8.dp).testTag("picker_list")) {
                items(shownFixed, key = { "fixed-${it.scope.encode()}" }) { PickerRow(it, selected, onChoose, onTogglePin) }
                if (shownPinned.isNotEmpty()) {
                    item(key = "header-pinned") { PickerHeader(stringResource(R.string.tab_order_pinned)) }
                    items(shownPinned, key = { "pin-${it.scope.encode()}" }) { PickerRow(it, selected, onChoose, onTogglePin) }
                }
                if (shownOthers.isNotEmpty()) {
                    item(key = "header-others") { PickerHeader(stringResource(R.string.drawer_categories)) }
                    items(shownOthers, key = { "cat-${it.scope.encode()}" }) { PickerRow(it, selected, onChoose, onTogglePin) }
                }
            }
            HorizontalDivider()
            TextButton(onClick = onOrganize, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("picker_organize")) {
                Text(stringResource(R.string.organize_tabs))
            }
        }
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickerRow(entry: PickerEntry, selected: ArticleScope, onChoose: (ArticleScope) -> Unit, onTogglePin: (String) -> Unit) {
    val isCurrent = entry.scope == selected
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        // The press target is the name; the pin is a button of its own beside it (a clickable row would swallow it for a screen reader).
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { onChoose(entry.scope) }
                .padding(vertical = 6.dp)
                .testTag("picker_item_${entry.scope.encode()}"),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (entry.parentPath.isNotEmpty()) {
                Text(entry.parentPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (isCurrent) {
            Icon(Icons.Filled.Check, stringResource(R.string.picker_current), Modifier.padding(horizontal = 8.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
        val categoryId = (entry.scope as? ArticleScope.Category)?.categoryId
        if (categoryId != null && entry.pinned != null) {
            IconButton(onClick = { onTogglePin(categoryId) }, modifier = Modifier.testTag("picker_pin_$categoryId")) {
                Icon(
                    painterResource(R.drawable.ic_push_pin),
                    contentDescription = stringResource(if (entry.pinned) R.string.tab_order_unpin else R.string.tab_order_pin),
                    tint = if (entry.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
