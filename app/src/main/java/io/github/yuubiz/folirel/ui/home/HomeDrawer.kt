package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.Structure

/** The modal drawer of a narrow window. Its content is [HomeNavigationContent], the same the persistent pane shows. */
@Composable
fun HomeDrawer(
    drawerState: DrawerState,
    structure: Structure,
    selection: Selection,
    attention: Int,
    onSelect: (ArticleScope) -> Unit,
    onSubscriptions: () -> Unit,
    onSettings: () -> Unit,
) {
    // The overload with the drawer state is the one that closes the drawer on Back (and follows the predictive back gesture);
    // without it Back would go past the open drawer and leave the app.
    ModalDrawerSheet(drawerState = drawerState, modifier = Modifier.widthIn(max = 320.dp).testTag("home_drawer")) {
        HomeNavigationContent(structure, selection, attention, onSelect, onSubscriptions, onSettings)
    }
}

/** The persistent category pane of a wide window (E): one column of the same content and the same data as the drawer, [width] wide. */
@Composable
fun HomeNavigationPane(
    width: Dp,
    structure: Structure,
    selection: Selection,
    attention: Int,
    onSelect: (ArticleScope) -> Unit,
    onSubscriptions: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.width(width).fillMaxHeight().testTag("home_pane"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // The app draws edge to edge: keep the pane's entries out of the status bar, the navigation bar and a cutout at the left.
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Start))) {
            HomeNavigationContent(structure, selection, attention, onSelect, onSubscriptions, onSettings)
        }
    }
}

/**
 * All, the pinned shortcuts, the categories, their feeds, then Subscriptions and Settings. The categories come in the
 * order of the tabs ([Structure.tabOrdered]): the one order that tabs, pager, picker, organizer, drawer and pane share, so there is
 * no sort of its own here. In that order a child can stand before its parent, so a category is named by its own name with its
 * parent path under it (as in the picker), not by an indent. Choosing something only reports it; closing a drawer is the caller's. It chooses WHERE to look (the scope) and nothing else: the read filter, period, search, hidden keywords and order belong to the display panel and are never touched from here.
 */
@Composable
internal fun HomeNavigationContent(
    structure: Structure,
    selection: Selection,
    attention: Int,
    onSelect: (ArticleScope) -> Unit,
    onSubscriptions: () -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    LazyColumn(Modifier.fillMaxHeight().padding(horizontal = 8.dp)) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        item {
            NavItem(stringResource(R.string.scope_all), selection.scope == ArticleScope.All, Modifier.testTag("drawer_item_all")) {
                onSelect(ArticleScope.All)
            }
        }
        if (structure.hasUncategorized) {
            item {
                NavItem(stringResource(R.string.scope_uncategorized), selection.scope == ArticleScope.Uncategorized, Modifier.testTag("drawer_item_uncategorized")) {
                    onSelect(ArticleScope.Uncategorized)
                }
            }
        }
        val pinned = structure.tabOrdered.filter { structure.preferences[it.id]?.isPinned == true }
        if (pinned.isNotEmpty()) {
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item {
                Text(
                    stringResource(R.string.tab_order_pinned),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            // Shortcuts only: the same categories are in the list below, in their place there.
            items(pinned, key = { "pin-${it.id}" }) { category ->
                NavItem(
                    label = category.display,
                    selected = selection.scope == ArticleScope.Category(category.id),
                    modifier = Modifier.testTag("drawer_pinned_${category.id}"),
                ) { onSelect(ArticleScope.Category(category.id)) }
            }
        }
        if (structure.tabOrdered.isNotEmpty()) {
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
        for (category in structure.tabOrdered) {
            val isOpen = category.id in expanded
            item(key = "c-${category.id}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavItem(
                        label = category.path.last(),
                        secondary = category.path.dropLast(1).joinToString(" / "),
                        selected = selection.scope == ArticleScope.Category(category.id),
                        modifier = Modifier.weight(1f).testTag("drawer_item_category:${category.id}"),
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
                // The feeds of a category stay under that category, whatever place it has in the order.
                items(category.directFeedIds.mapNotNull { structure.feeds[it] }.filter { it.subscribed }, key = { "f-${category.id}-${it.id}" }) { feed ->
                    NavItem(
                        label = feed.name,
                        selected = selection.scope == ArticleScope.Feed(feed.id),
                        modifier = Modifier.padding(start = 24.dp),
                    ) { onSelect(ArticleScope.Feed(feed.id)) }
                }
            }
        }
        val uncategorized = structure.subscribedFeeds.filter { it.categoryIds.isEmpty() }.sortedBy { it.name.lowercase() }
        if (uncategorized.isNotEmpty()) {
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            items(uncategorized, key = { "u-${it.id}" }) { feed ->
                NavItem(feed.name, selection.scope == ArticleScope.Feed(feed.id)) { onSelect(ArticleScope.Feed(feed.id)) }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
        item { NavItem(stringResource(R.string.nav_subscriptions), false, attention = attention, onClick = onSubscriptions) }
        item { NavItem(stringResource(R.string.nav_settings), false, Modifier.testTag("drawer_item_settings"), onClick = onSettings) }
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    secondary: String = "",
    attention: Int = 0,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Column {
                Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (secondary.isNotEmpty()) {
                    Text(
                        secondary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        badge = if (attention > 0) ({ AttentionCount(attention) }) else null,
    )
}
