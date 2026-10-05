package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.Structure

@Composable
fun HomeDrawer(
    structure: Structure,
    selection: Selection,
    attention: Int,
    onSelect: (ArticleScope) -> Unit,
    onSaved: () -> Unit,
    onSubscriptions: () -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
        LazyColumn(Modifier.fillMaxHeight().padding(horizontal = 8.dp)) {
            item {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            item {
                DrawerItem(stringResource(R.string.scope_all), selection.scope == ArticleScope.All && selection.filter != ReadFilter.SAVED) {
                    onSelect(ArticleScope.All)
                }
            }
            item {
                DrawerItem(stringResource(R.string.drawer_saved), selection.scope == ArticleScope.All && selection.filter == ReadFilter.SAVED, onClick = onSaved)
            }
            if (structure.hasUncategorized) {
                item {
                    DrawerItem(stringResource(R.string.scope_uncategorized), selection.scope == ArticleScope.Uncategorized) {
                        onSelect(ArticleScope.Uncategorized)
                    }
                }
            }
            if (structure.categories.isNotEmpty()) {
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                item {
                    Text(
                        stringResource(R.string.drawer_categories),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            for (category in structure.categories) {
                val isOpen = category.id in expanded
                item(key = "c-${category.id}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DrawerItem(
                            label = category.path.last(),
                            selected = selection.scope == ArticleScope.Category(category.id),
                            modifier = Modifier.weight(1f).padding(start = (category.depth * 16).dp),
                        ) { onSelect(ArticleScope.Category(category.id)) }
                        if (category.directFeedIds.isNotEmpty()) {
                            IconButton(onClick = { expanded = if (isOpen) expanded - category.id else expanded + category.id }) {
                                Icon(
                                    painterResource(if (isOpen) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                                    contentDescription = stringResource(if (isOpen) R.string.action_collapse else R.string.action_expand, category.display),
                                )
                            }
                        }
                    }
                }
                if (isOpen) {
                    items(category.directFeedIds.mapNotNull { structure.feeds[it] }.filter { it.subscribed }, key = { "f-${category.id}-${it.id}" }) { feed ->
                        DrawerItem(
                            label = feed.name,
                            selected = selection.scope == ArticleScope.Feed(feed.id),
                            modifier = Modifier.padding(start = (category.depth * 16 + 24).dp),
                        ) { onSelect(ArticleScope.Feed(feed.id)) }
                    }
                }
            }
            val uncategorized = structure.subscribedFeeds.filter { it.categoryIds.isEmpty() }.sortedBy { it.name.lowercase() }
            if (uncategorized.isNotEmpty()) {
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                items(uncategorized, key = { "u-${it.id}" }) { feed ->
                    DrawerItem(feed.name, selection.scope == ArticleScope.Feed(feed.id)) { onSelect(ArticleScope.Feed(feed.id)) }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item { DrawerItem(subscriptionsLabel(attention), false, onClick = onSubscriptions) }
            item { DrawerItem(stringResource(R.string.nav_settings), false, onClick = onSettings) }
        }
    }
}

@Composable
private fun DrawerItem(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    )
}
