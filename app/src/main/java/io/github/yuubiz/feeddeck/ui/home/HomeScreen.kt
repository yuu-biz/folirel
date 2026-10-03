package io.github.yuubiz.feeddeck.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import io.github.yuubiz.feeddeck.R
import io.github.yuubiz.feeddeck.backend.FeedResult
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.browser.BrowserLauncher
import io.github.yuubiz.feeddeck.data.preferences.AppSettings
import io.github.yuubiz.feeddeck.data.preferences.BrowserMode
import io.github.yuubiz.feeddeck.model.ArticleRow
import io.github.yuubiz.feeddeck.model.ArticleScope
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.model.ListMode
import io.github.yuubiz.feeddeck.model.ReadFilter
import io.github.yuubiz.feeddeck.model.SortOrder
import io.github.yuubiz.feeddeck.model.Structure
import io.github.yuubiz.feeddeck.sync.HttpsUpgradeState
import io.github.yuubiz.feeddeck.sync.RefreshState
import io.github.yuubiz.feeddeck.sync.RequestStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeNavigation(
    val importOpml: () -> Unit,
    val addFeed: () -> Unit,
    val subscriptions: () -> Unit,
    val settings: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    imageLoader: ImageLoader,
    browser: BrowserLauncher,
    nav: HomeNavigation,
    httpsState: StateFlow<HttpsUpgradeState>,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val structure by vm.structure.collectAsStateWithLifecycle()
    val pages by vm.pageScopes.collectAsStateWithLifecycle()
    val currentListFlow = remember(selection.scope) { vm.pageList(selection.scope) }
    val list by currentListFlow.collectAsStateWithLifecycle()
    val refresh by vm.refreshState.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val searchActive by vm.searchActive.collectAsStateWithLifecycle()
    val searchText by vm.searchText.collectAsStateWithLifecycle()
    val newIds by vm.newArticleIds.collectAsStateWithLifecycle()
    val https by httpsState.collectAsStateWithLifecycle()
    val checkingHttps = https.running

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }

    var markAllRequest by remember { mutableStateOf<MarkAllReadRequest?>(null) }
    var meteredConfirm by remember { mutableStateOf(false) }
    var httpConfirmUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var httpConfirmMode by rememberSaveable { mutableStateOf(BrowserMode.CUSTOM_TABS) }
    var summaryFor by remember { mutableStateOf<ArticleRow?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }

    val messages = HomeMessages.load()
    LaunchedEffect(vm) {
        vm.eventFlow.collect { event ->
            val text = when (event) {
                is HomeEvent.BulkMarked -> messages.bulkMarked(event.count)
                is HomeEvent.Message -> messages.of(event.kind)
            }
            snackbar.showSnackbar(text)
        }
    }

    fun openUrl(row: ArticleRow, mode: BrowserMode) {
        when (val check = browser.check(row.linkUrl)) {
            BrowserLauncher.LinkCheck.Missing -> scope.launch { snackbar.showSnackbar(messages.noUrl) }
            BrowserLauncher.LinkCheck.Unsafe -> scope.launch { snackbar.showSnackbar(messages.unsafeUrl) }
            is BrowserLauncher.LinkCheck.Ok -> if (!check.isHttps) {
                httpConfirmUrl = check.url
                httpConfirmMode = mode
            } else if (browser.open(context, check.url, mode) == BrowserLauncher.OpenResult.NO_BROWSER) {
                scope.launch { snackbar.showSnackbar(messages.noBrowser) }
            }
        }
    }

    val actions = ArticleActions(
        open = { openUrl(it, settings.browserMode) },
        openExternal = { openUrl(it, BrowserMode.EXTERNAL) },
        toggleSaved = { vm.toggleSaved(it) },
        setRead = { row, read -> vm.setRead(row, read) },
        share = { row -> context.startActivity(browser.shareIntent(row.title, row.linkUrl)) },
        showSummary = { summaryFor = it },
    )

    fun requestRefresh() {
        if (network.metered && settings.unmeteredOnly && network.connected) meteredConfirm = true else vm.refreshCurrentScope()
    }

    val scopeTitle = scopeTitle(selection.scope, structure)
    val unreadInList = list.items.count { !it.isRead }
    val target = HomeViewModel.refreshTargetFor(selection)

    ModalNavigationDrawer(
        drawerState = drawerState,
        // A closed drawer opens from the menu button only, so horizontal swipes belong to the category pager.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            HomeDrawer(
                structure = structure,
                selection = selection,
                onSelect = { s ->
                    vm.selectScope(s)
                    scope.launch { drawerState.close() }
                },
                onSaved = {
                    vm.showSaved()
                    scope.launch { drawerState.close() }
                },
                onSubscriptions = {
                    scope.launch { drawerState.close() }
                    nav.subscriptions()
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    nav.settings()
                },
            )
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    expandedHeight = 56.dp,
                    navigationIcon = {
                        if (searchActive) {
                            IconButton(onClick = { vm.setSearchActive(false) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close_search))
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, stringResource(R.string.action_open_drawer))
                            }
                        }
                    },
                    title = {
                        if (searchActive) {
                            TextField(
                                value = searchText,
                                onValueChange = vm::setSearchText,
                                placeholder = { Text(stringResource(R.string.search_hint, scopeTitle), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                trailingIcon = {
                                    if (searchText.isNotEmpty()) {
                                        IconButton(onClick = { vm.setSearchText("") }) {
                                            Icon(Icons.Filled.Close, stringResource(R.string.action_clear_search))
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("search_field"),
                            )
                        } else {
                            Text(scopeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        if (!searchActive) {
                            IconButton(onClick = { vm.setSearchActive(true) }) {
                                Icon(Icons.Filled.Search, stringResource(R.string.action_search))
                            }
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                listOf(
                                    ListMode.MAGAZINE to R.string.list_mode_magazine,
                                    ListMode.LIST to R.string.list_mode_list,
                                    ListMode.CARD to R.string.list_mode_card,
                                ).forEach { (mode, label) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        leadingIcon = {
                                            if (settings.listMode == mode) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp))
                                        },
                                        onClick = { overflowOpen = false; vm.setListMode(mode) },
                                        modifier = Modifier.testTag("menu_mode_${mode.name}"),
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (selection.sort == SortOrder.NEWEST) R.string.sort_oldest else R.string.sort_newest)) },
                                    onClick = { overflowOpen = false; vm.toggleSort() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_mark_all_read)) },
                                    enabled = unreadInList > 0,
                                    onClick = {
                                        overflowOpen = false
                                        scope.launch { markAllRequest = vm.prepareMarkAllRead(scopeTitle) }
                                    },
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_refresh)) }, onClick = { overflowOpen = false; requestRefresh() })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_import_opml)) }, onClick = { overflowOpen = false; nav.importOpml() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_add_feed)) }, onClick = { overflowOpen = false; nav.addFeed() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.nav_subscriptions)) }, onClick = { overflowOpen = false; nav.subscriptions() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.nav_settings)) }, onClick = { overflowOpen = false; nav.settings() })
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                val pagerMode = selection.scope !is ArticleScope.Feed && structure.subscribedFeeds.isNotEmpty()
                if (pagerMode) ScopeTabs(structure, pages, selection.scope, vm::selectScope)
                FilterBar(selection, vm::selectFilter, vm::toggleSort)
                StatusBar(
                    refresh = refresh,
                    target = target,
                    offline = !network.connected,
                    structure = structure,
                    checkingHttps = checkingHttps,
                    onOpenSubscriptions = nav.subscriptions,
                    onRetry = vm::retryPending,
                )
                PullToRefreshBox(
                    isRefreshing = refresh.runningFor(target) != null,
                    onRefresh = { requestRefresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val page: @Composable (ArticleScope) -> Unit = { pageScope ->
                        ScopePage(
                            vm = vm,
                            pageScope = pageScope,
                            selection = selection,
                            settings = settings,
                            imageLoader = imageLoader,
                            actions = actions,
                            newIds = newIds,
                            refreshing = refresh.runningFor(HomeViewModel.scopeOf(pageScope)) != null,
                            offline = !network.connected,
                            onRefresh = { requestRefresh() },
                        )
                    }
                    when {
                        !structure.loaded -> Unit
                        structure.feeds.isEmpty() -> NoSubscriptions(nav.importOpml, nav.addFeed)
                        pagerMode -> CategoryPager(vm, pages, selection.scope, page)
                        else -> page(selection.scope)
                    }
                }
            }
        }
    }

    markAllRequest?.let { request ->
        MarkAllReadDialog(request, onConfirm = { vm.confirmMarkAllRead(request); markAllRequest = null }, onDismiss = { markAllRequest = null })
    }
    if (meteredConfirm) {
        AlertDialog(
            onDismissRequest = { meteredConfirm = false },
            title = { Text(stringResource(R.string.metered_title)) },
            text = { Text(stringResource(R.string.metered_message)) },
            confirmButton = { TextButton(onClick = { meteredConfirm = false; vm.refreshCurrentScope() }) { Text(stringResource(R.string.metered_confirm)) } },
            dismissButton = { TextButton(onClick = { meteredConfirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    httpConfirmUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { httpConfirmUrl = null },
            title = { Text(stringResource(R.string.http_open_title)) },
            text = { Text(stringResource(R.string.http_open_message)) },
            confirmButton = {
                TextButton(onClick = {
                    httpConfirmUrl = null
                    if (browser.open(context, url, httpConfirmMode) == BrowserLauncher.OpenResult.NO_BROWSER) {
                        scope.launch { snackbar.showSnackbar(messages.noBrowser) }
                    }
                }) { Text(stringResource(R.string.http_open_confirm)) }
            },
            dismissButton = { TextButton(onClick = { httpConfirmUrl = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    summaryFor?.let { row ->
        ModalBottomSheet(onDismissRequest = { summaryFor = null }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                Text(row.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(row.summaryText.ifBlank { stringResource(R.string.summary_empty) }, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.summary_no_url_note), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * All / Uncategorized / categories as horizontally swipeable pages (UX-01). One selection state: tab, drawer and
 * swipe all end in [HomeViewModel.selectScope]; a page change caused by the selection does not feed back.
 * Not circular. Only the visible page (and its neighbour while swiping) is composed and queries the DB.
 */
@Composable
private fun CategoryPager(vm: HomeViewModel, pages: List<ArticleScope>, selected: ArticleScope, page: @Composable (ArticleScope) -> Unit) {
    val pagerState = rememberPagerState(initialPage = pages.indexOf(selected).coerceAtLeast(0)) { pages.size }
    val selectedIndex = pages.indexOf(selected)
    LaunchedEffect(selectedIndex, pages) {
        if (selectedIndex >= 0 && pagerState.currentPage != selectedIndex && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(selectedIndex)
        }
    }
    val currentPages by rememberUpdatedState(pages)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            val scopeAtPage = currentPages.getOrNull(index) ?: return@collect
            if (scopeAtPage != vm.selection.value.scope) vm.selectScope(scopeAtPage)
        }
    }
    HorizontalPager(
        state = pagerState,
        key = { index -> pages.getOrNull(index)?.encode() ?: "page-$index" },
        modifier = Modifier.fillMaxSize().testTag("category_pager"),
    ) { index ->
        page(pages.getOrNull(index) ?: ArticleScope.All)
    }
}

@Composable
private fun ScopePage(
    vm: HomeViewModel,
    pageScope: ArticleScope,
    selection: Selection,
    settings: AppSettings,
    imageLoader: ImageLoader,
    actions: ArticleActions,
    newIds: Set<String>,
    refreshing: Boolean,
    offline: Boolean,
    onRefresh: () -> Unit,
) {
    val flow = remember(pageScope) { vm.pageList(pageScope) }
    val list by flow.collectAsStateWithLifecycle()
    when {
        !list.loaded -> Box(Modifier.fillMaxSize())
        list.items.isEmpty() -> EmptyList(selection, refreshing, offline, onRefresh)
        else -> ArticleList(
            vm = vm,
            state = list,
            mode = settings.listMode,
            showImages = settings.showImages,
            imageLoader = imageLoader,
            actions = actions,
            newIds = newIds,
            newestFirst = selection.sort == SortOrder.NEWEST,
        )
    }
}

@Composable
private fun ArticleList(
    vm: HomeViewModel,
    state: ArticlesState,
    mode: ListMode,
    showImages: Boolean,
    imageLoader: ImageLoader,
    actions: ArticleActions,
    newIds: Set<String>,
    newestFirst: Boolean,
) {
    val items = state.items
    // Restore from this list's own anchor (article id + offset); if it is gone, use the nearest remaining article.
    val listState = remember(state.key) {
        val anchor = vm.anchorFor(state.key)
        val index = anchor?.let { a ->
            items.indexOfFirst { it.id == a.articleId }.takeIf { it >= 0 }
                ?: items.indexOfFirst { if (newestFirst) it.sortAt <= a.sortAt else it.sortAt >= a.sortAt }.takeIf { it >= 0 }
                ?: (items.size - 1)
        } ?: 0
        LazyListState(index.coerceAtLeast(0), if (anchor != null && items.getOrNull(index)?.id == anchor.articleId) anchor.offset else 0)
    }
    val currentItems by rememberUpdatedState(items)
    val currentNew by rememberUpdatedState(newIds)
    LaunchedEffect(listState, state.key) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            currentItems.getOrNull(index)?.let { vm.saveAnchor(ListAnchor(state.key, it.id, it.sortAt, offset)) }
        }
    }
    // New articles count as seen once they are on screen, judged on this list's own state (REV-02 / N06).
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }.collect { visible ->
            vm.markNewSeen(visible.filter { it in currentNew })
        }
    }
    // The banner counts only the new articles this list shows and jumps to the first of them in the current order.
    val firstNewIndex by remember(listState) { derivedStateOf { currentItems.indexOfFirst { it.id in currentNew } } }
    val newCount = remember(items, newIds) { items.count { it.id in newIds } }
    val scope = rememberCoroutineScope()
    val now = remember(items) { System.currentTimeMillis() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("article_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(items, key = { it.id }, contentType = { mode }) { row ->
                ArticleItem(row, mode, showImages, imageLoader, now, actions, Modifier.testTag("article_${row.id}"))
            }
        }
        if (newCount > 0 && firstNewIndex >= 0) {
            // New arrivals never move the list on their own; the user jumps explicitly.
            FilledTonalButton(
                onClick = {
                    val jumpTo = firstNewIndex
                    scope.launch { if (jumpTo >= 0) listState.scrollToItem(jumpTo) }
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).testTag("new_articles_banner"),
            ) { Text(pluralStringResource(R.plurals.new_articles_banner, newCount, newCount)) }
        }
    }
}

@Composable
private fun ScopeTabs(structure: Structure, pages: List<ArticleScope>, current: ArticleScope, onSelect: (ArticleScope) -> Unit) {
    val all = stringResource(R.string.tab_all)
    val uncategorized = stringResource(R.string.scope_uncategorized)
    val tabs = pages.map { scope ->
        scope to when (scope) {
            ArticleScope.All -> all
            ArticleScope.Uncategorized -> uncategorized
            is ArticleScope.Category -> structure.categories.firstOrNull { it.id == scope.categoryId }?.display.orEmpty()
            is ArticleScope.Feed -> ""
        }
    }
    val selected = tabs.indexOfFirst { it.first == current }
    PrimaryScrollableTabRow(selectedTabIndex = selected.coerceAtLeast(0), edgePadding = 8.dp, minTabWidth = 48.dp) {
        tabs.forEachIndexed { index, (scope, label) ->
            Tab(
                selected = index == selected,
                onClick = { onSelect(scope) },
                text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("tab_${scope.encode()}"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(selection: Selection, onFilter: (ReadFilter) -> Unit, onToggleSort: () -> Unit) {
    // Wraps instead of clipping when the text is large (200% system font).
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            ReadFilter.ALL to R.string.filter_all,
            ReadFilter.UNREAD to R.string.filter_unread,
            ReadFilter.SAVED to R.string.filter_saved,
        ).forEach { (filter, label) ->
            FilterChip(selected = selection.filter == filter, onClick = { onFilter(filter) }, label = { Text(stringResource(label), maxLines = 1) })
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToggleSort, modifier = Modifier.testTag("sort_toggle")) {
            Icon(painterResource(R.drawable.ic_swap_vert), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(if (selection.sort == SortOrder.NEWEST) R.string.sort_newest else R.string.sort_oldest), maxLines = 1)
        }
    }
}

/**
 * Progress and result of the request for the shown scope (not of some other refresh), plus a short line when a
 * different refresh is running. Partial results are never shown as complete (SYNC-02/04).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBar(
    refresh: RefreshState,
    target: RefreshScope,
    offline: Boolean,
    structure: Structure,
    checkingHttps: Boolean,
    onOpenSubscriptions: () -> Unit,
    onRetry: () -> Unit,
) {
    val own: RequestStatus? = refresh.latestFor(target)
    val other = refresh.requests.lastOrNull { it.running && it.scope != target }
    val report = own?.report?.takeIf { !own.running }
    var showRetry = false
    val lines = buildList {
        if (own?.running == true) {
            val p = own.progress
            val firstFetch = structure.subscribedFeeds.isNotEmpty() && structure.subscribedFeeds.none { it.initialized }
            add(
                when {
                    firstFetch -> stringResource(R.string.status_first_fetch)
                    p != null && p.total > 0 -> stringResource(R.string.status_refreshing_progress, p.completed, p.total)
                    else -> stringResource(R.string.status_refreshing)
                },
            )
        }
        if (other != null) {
            val p = other.progress
            add(
                if (p != null && p.total > 0) stringResource(R.string.status_other_refresh_progress, p.completed, p.total)
                else stringResource(R.string.status_other_refresh),
            )
        }
        if (checkingHttps) add(stringResource(R.string.status_https_checking))
        if (offline) add(stringResource(R.string.status_offline))
        if (own != null && !own.running && own.report == null) add(stringResource(R.string.status_interrupted))
        if (report != null && report.total > 0) {
            val results = report.perFeed.values
            val ok = results.count { it.result == FeedResult.UPDATED || it.result == FeedResult.NOT_MODIFIED }
            val waiting = results.count { it.result == FeedResult.BACKOFF || it.result == FeedResult.OFFLINE }
            add(stringResource(R.string.status_result, report.total, ok, report.failed.size, waiting, report.notAttempted, report.httpPending))
            val storage = report.failed.values.count { it == FetchError.STORAGE }
            if (storage > 0) add(pluralStringResource(R.plurals.status_storage_failed, storage, storage))
            if (report.deferredConstraint > 0 && report.offline && !offline) add(stringResource(R.string.status_deferred_offline))
            showRetry = (report.notAttempted > 0 || report.failed.isNotEmpty()) && !refresh.running
        }
    }
    if (lines.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, onClick = onOpenSubscriptions, modifier = Modifier.fillMaxWidth().testTag("status_bar")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (own?.running == true) {
                val p = own.progress
                if (p != null && p.total > 0) {
                    LinearProgressIndicator(progress = { p.completed.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                Text(
                    lines.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showRetry) {
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("retry_unfinished")) { Text(stringResource(R.string.action_retry_unfinished)) }
                }
            }
        }
    }
}

@Composable
private fun NoSubscriptions(onImport: () -> Unit, onAdd: () -> Unit) {
    CenteredMessage(stringResource(R.string.empty_no_subscriptions)) {
        Button(onClick = onImport) { Text(stringResource(R.string.action_import_opml)) }
        OutlinedButton(onClick = onAdd) { Text(stringResource(R.string.action_add_feed)) }
    }
}

@Composable
private fun EmptyList(selection: Selection, refreshing: Boolean, offline: Boolean, onRefresh: () -> Unit) {
    val message = when {
        selection.search.isNotEmpty() -> stringResource(R.string.empty_search, selection.search)
        selection.filter == ReadFilter.UNREAD -> stringResource(R.string.empty_unread)
        selection.filter == ReadFilter.SAVED -> stringResource(R.string.empty_saved)
        refreshing -> stringResource(R.string.empty_fetching)
        offline -> stringResource(R.string.empty_offline)
        else -> stringResource(R.string.empty_articles)
    }
    CenteredMessage(message) {
        // A refresh covers the scope's feeds; filter and search never narrow it, so it is offered on every empty list.
        if (!refreshing) OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("empty_refresh")) { Text(stringResource(R.string.menu_refresh)) }
    }
}

@Composable
private fun CenteredMessage(message: String, actions: @Composable () -> Unit) {
    // Scrollable so pull-to-refresh also works on empty states and nothing is clipped at 200% text.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.testTag("empty_message"))
        actions()
    }
}

@Composable
private fun MarkAllReadDialog(request: MarkAllReadRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val filterLabel = stringResource(
        when (request.filter) {
            ReadFilter.ALL -> R.string.filter_all
            ReadFilter.UNREAD -> R.string.filter_unread
            ReadFilter.SAVED -> R.string.filter_saved
        },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mark_all_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.mark_all_scope, request.scopeTitle))
                Text(stringResource(R.string.mark_all_filter, filterLabel))
                if (request.search.isNotEmpty()) Text(stringResource(R.string.mark_all_search, request.search))
                Text(
                    if (request.ids.isEmpty()) stringResource(R.string.mark_all_none)
                    else pluralStringResource(R.plurals.mark_all_count, request.ids.size, request.ids.size),
                    modifier = Modifier.testTag("mark_all_count"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = request.ids.isNotEmpty()) { Text(stringResource(R.string.mark_all_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun scopeTitle(scope: ArticleScope, structure: Structure): String = when (scope) {
    ArticleScope.All -> stringResource(R.string.scope_all)
    ArticleScope.Uncategorized -> stringResource(R.string.scope_uncategorized)
    is ArticleScope.Category -> structure.categories.firstOrNull { it.id == scope.categoryId }?.display ?: stringResource(R.string.scope_all)
    is ArticleScope.Feed -> structure.feeds[scope.feedId]?.name ?: stringResource(R.string.scope_all)
}

/** Snackbar texts resolved in composition. */
private class HomeMessages(
    private val texts: Map<HomeEvent.Message.Kind, String>,
    private val bulk: (Int) -> String,
    val noUrl: String,
    val unsafeUrl: String,
    val noBrowser: String,
) {
    fun of(kind: HomeEvent.Message.Kind) = texts.getValue(kind)
    fun bulkMarked(count: Int) = bulk(count)

    companion object {
        @Composable
        fun load(): HomeMessages {
            val resources = LocalResources.current
            return HomeMessages(
                texts = mapOf(
                    HomeEvent.Message.Kind.SAVED to stringResource(R.string.msg_saved),
                    HomeEvent.Message.Kind.UNSAVED to stringResource(R.string.msg_unsaved),
                    HomeEvent.Message.Kind.MARKED_READ to stringResource(R.string.msg_marked_read),
                    HomeEvent.Message.Kind.MARKED_UNREAD to stringResource(R.string.msg_marked_unread),
                    HomeEvent.Message.Kind.BULK_MARKED to stringResource(R.string.msg_marked_read),
                    HomeEvent.Message.Kind.UPDATE_FAILED to stringResource(R.string.msg_update_failed),
                ),
                bulk = { n -> resources.getQuantityString(R.plurals.msg_bulk_marked, n, n) },
                noUrl = stringResource(R.string.msg_no_url),
                unsafeUrl = stringResource(R.string.msg_unsafe_url),
                noBrowser = stringResource(R.string.msg_no_browser),
            )
        }
    }
}
