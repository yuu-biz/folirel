package io.github.yuubiz.feeddeck.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
import io.github.yuubiz.feeddeck.model.CardLayoutStyle
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
    // One pager state for the tabs and the pages: the tabs follow the finger instead of waiting for the page to settle.
    // Created once the selected scope is among the pages, so a saved category opens on its own page (not by sliding there).
    val pagerState = key(pages.contains(selection.scope)) {
        rememberPagerState(initialPage = pages.indexOf(selection.scope).coerceAtLeast(0)) { pages.size }
    }
    // Bumped to send the list on screen back to its top (tapping the selected tab); lists compare it with the value they started with.
    var scrollToTopTick by remember { mutableIntStateOf(0) }
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
            is BrowserLauncher.LinkCheck.Ok -> if (!check.isHttps && settings.confirmHttpOpen) {
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
                            IconButton(onClick = { overflowOpen = true }, modifier = Modifier.testTag("home_more")) {
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
                if (pagerMode) {
                    ScopeTabs(structure, pages, selection.scope, pagerState, vm::selectScope) { scrollToTopTick++ }
                }
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
                    val page: @Composable (ArticleScope, Boolean) -> Unit = { pageScope, isCurrent ->
                        ScopePage(
                            isCurrent = isCurrent,
                            vm = vm,
                            pageScope = pageScope,
                            selection = selection,
                            settings = settings,
                            imageLoader = imageLoader,
                            actions = actions,
                            newIds = newIds,
                            scrollToTopTick = scrollToTopTick,
                            refreshing = refresh.runningFor(HomeViewModel.scopeOf(pageScope)) != null,
                            offline = !network.connected,
                            onRefresh = { requestRefresh() },
                        )
                    }
                    when {
                        !structure.loaded -> Unit
                        structure.feeds.isEmpty() -> NoSubscriptions(nav.importOpml, nav.addFeed)
                        pagerMode -> CategoryPager(vm, pages, selection.scope, pagerState, page)
                        else -> page(selection.scope, true)
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
        HttpOpenDialog(
            onOpen = { dontAskAgain ->
                httpConfirmUrl = null
                if (dontAskAgain) vm.stopAskingBeforeHttpOpen()
                if (browser.open(context, url, httpConfirmMode) == BrowserLauncher.OpenResult.NO_BROWSER) {
                    scope.launch { snackbar.showSnackbar(messages.noBrowser) }
                }
            },
            onDismiss = { httpConfirmUrl = null },
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
 * Confirmation before opening an http:// article. The "don't ask again" box starts unticked every time; it is
 * saved only through [onOpen], never by cancelling. It concerns this dialog only: feed/image HTTP downloads and the
 * browser's own warnings are unaffected.
 */
@Composable
private fun HttpOpenDialog(onOpen: (dontAskAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontAskAgain by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.http_open_title)) },
        text = {
            Column {
                Text(stringResource(R.string.http_open_message))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = dontAskAgain, role = Role.Checkbox, onValueChange = { dontAskAgain = it })
                        .testTag("http_open_dont_ask"),
                ) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = null)
                    Text(stringResource(R.string.http_open_dont_ask), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpen(dontAskAgain) }, modifier = Modifier.testTag("http_open_confirm")) { Text(stringResource(R.string.http_open_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("http_open_cancel")) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * All / Uncategorized / categories as horizontally swipeable pages (UX-01). One selection state: tab, drawer and
 * swipe all end in [HomeViewModel.selectScope]; a page change caused by the selection does not feed back.
 * Not circular. Only the visible page (and its neighbour while swiping) is composed and queries the DB.
 */
@Composable
private fun CategoryPager(
    vm: HomeViewModel,
    pages: List<ArticleScope>,
    selected: ArticleScope,
    pagerState: PagerState,
    page: @Composable (ArticleScope, Boolean) -> Unit,
) {
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
        // Only the settled page counts as "shown"; a pre-composed neighbour must not consume new-article markers.
        page(pages.getOrNull(index) ?: ArticleScope.All, index == pagerState.settledPage)
    }
}

@Composable
private fun ScopePage(
    isCurrent: Boolean,
    vm: HomeViewModel,
    pageScope: ArticleScope,
    selection: Selection,
    settings: AppSettings,
    imageLoader: ImageLoader,
    actions: ArticleActions,
    newIds: Set<String>,
    scrollToTopTick: Int,
    refreshing: Boolean,
    offline: Boolean,
    onRefresh: () -> Unit,
) {
    val flow = remember(pageScope) { vm.pageList(pageScope) }
    val list by flow.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().testTag("page_${pageScope.encode()}")) {
        when {
            !list.loaded -> Unit
            list.items.isEmpty() -> EmptyList(selection, refreshing, offline, onRefresh)
            else -> ArticleList(
                host = vm,
                state = list,
                mode = settings.listMode,
                showImages = settings.showImages,
                cardColumns = settings.cardColumns,
                cardStyle = settings.cardStyle,
                imageLoader = imageLoader,
                actions = actions,
                newIds = newIds,
                newestFirst = selection.sort == SortOrder.NEWEST,
                isCurrent = isCurrent,
                scrollToTopTick = scrollToTopTick,
            )
        }
    }
}

/** Scroll position of the article list, the same operations for the column list and the card grid. */
/** Items scrolled past before the "back to top" button appears. */
private const val SCROLL_TOP_BUTTON_AFTER = 6

private interface ScrollHandle {
    /** The underlying Compose state object; effects restart when it changes. */
    val owner: Any
    val firstIndex: Int
    val firstOffset: Int
    val visibleKeys: List<Any>
    suspend fun scrollTo(index: Int)

    /** Back to the first article, animated. */
    suspend fun animateToTop()
}

private class ListScroll(private val s: LazyListState) : ScrollHandle {
    override val owner: Any get() = s
    override val firstIndex: Int get() = s.firstVisibleItemIndex
    override val firstOffset: Int get() = s.firstVisibleItemScrollOffset
    override val visibleKeys: List<Any> get() = s.layoutInfo.visibleItemsInfo.map { it.key }
    override suspend fun scrollTo(index: Int) = s.scrollToItem(index)
    override suspend fun animateToTop() = s.animateScrollToItem(0)
}

private class GridScroll(private val s: LazyGridState) : ScrollHandle {
    override val owner: Any get() = s
    override val firstIndex: Int get() = s.firstVisibleItemIndex
    override val firstOffset: Int get() = s.firstVisibleItemScrollOffset
    override val visibleKeys: List<Any> get() = s.layoutInfo.visibleItemsInfo.map { it.key }
    override suspend fun scrollTo(index: Int) = s.scrollToItem(index)
    override suspend fun animateToTop() = s.animateScrollToItem(0)
}

@Composable
internal fun ArticleList(
    host: ListHost,
    state: ArticlesState,
    mode: ListMode,
    showImages: Boolean,
    cardColumns: Int,
    cardStyle: CardLayoutStyle,
    imageLoader: ImageLoader,
    actions: ArticleActions,
    newIds: Set<String>,
    newestFirst: Boolean,
    isCurrent: Boolean,
    scrollToTopTick: Int,
) {
    val items = state.items
    val useGrid = mode == ListMode.CARD
    // Restore from this list's own anchor (article id + offset); if it is gone, use the nearest remaining article.
    // Switching list style, rotating or resizing the window keeps the same article near the top.
    val start = remember(state.key, useGrid) {
        val anchor = host.anchorFor(state.key)
        val index = anchor?.let { a ->
            items.indexOfFirst { it.id == a.articleId }.takeIf { it >= 0 }
                ?: items.indexOfFirst { if (newestFirst) it.sortAt <= a.sortAt else it.sortAt >= a.sortAt }.takeIf { it >= 0 }
                ?: (items.size - 1)
        } ?: 0
        index.coerceAtLeast(0) to (if (anchor != null && items.getOrNull(index)?.id == anchor.articleId) anchor.offset else 0)
    }
    val scroll: ScrollHandle
    val gridState: LazyGridState?
    val listState: LazyListState?
    if (useGrid) {
        gridState = remember(state.key) { LazyGridState(start.first, start.second) }
        listState = null
        scroll = GridScroll(gridState)
    } else {
        listState = remember(state.key) { LazyListState(start.first, start.second) }
        gridState = null
        scroll = ListScroll(listState)
    }
    val currentItems by rememberUpdatedState(items)
    val currentNew by rememberUpdatedState(newIds)
    val currentScroll by rememberUpdatedState(scroll)
    LaunchedEffect(scroll.owner, state.key) {
        snapshotFlow { currentScroll.firstIndex to currentScroll.firstOffset }.collect { (index, offset) ->
            currentItems.getOrNull(index)?.let { host.saveAnchor(ListAnchor(state.key, it.id, it.sortAt, offset)) }
        }
    }
    // New articles count as seen once they are on screen, judged on this list's own state (REV-02 / N06).
    LaunchedEffect(scroll.owner, isCurrent) {
        if (!isCurrent) return@LaunchedEffect
        // Both sides are observed: a marker added later for an article that is already shown is consumed too (V3-R03).
        NewArticleSeen.visibleNew({ currentScroll.visibleKeys }, { currentNew }).collect { host.markNewSeen(it) }
    }
    // "Back to top": the selected tab was tapped again (only the list on screen follows, and only a tap made after this list appeared).
    val firstTick = remember { scrollToTopTick }
    LaunchedEffect(scrollToTopTick) {
        if (isCurrent && scrollToTopTick != firstTick) currentScroll.animateToTop()
    }
    // Far enough down that the way back is long; the button is for that case only.
    val farDown by remember(scroll.owner) { derivedStateOf { currentScroll.firstIndex > SCROLL_TOP_BUTTON_AFTER } }
    // The banner counts only the new articles this list shows and jumps to the first of them in the current order.
    val firstNewIndex by remember(scroll.owner) { derivedStateOf { currentItems.indexOfFirst { it.id in currentNew } } }
    val newCount = remember(items, newIds) { items.count { it.id in newIds } }
    val scope = rememberCoroutineScope()
    val now = remember(items) { System.currentTimeMillis() }
    Box(Modifier.fillMaxSize()) {
        if (gridState != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthDp = maxWidth.value
                val columns = CardGridLayout.columns(widthDp, LocalDensity.current.fontScale, cardColumns)
                // Every article asks for a card size by its id; cards are packed into gap-free rows (magazine style).
                val even = cardStyle == CardLayoutStyle.EVEN
                val spans = remember(items, columns.count, cardStyle) {
                    MagazinePlan.spans(items.map { it.id }, columns.count, cardStyle)
                }
                val tileImageHeight = CardGridLayout.tileImageHeightDp(widthDp, columns.count)
                val metricsBySpan = remember(widthDp, columns.count, cardStyle) {
                    (1..MagazinePlan.GRID_SPANS).associateWith { span ->
                        CardMetrics(
                            span, CardGridLayout.spanWidthDp(widthDp, span),
                            wide = span == MagazinePlan.GRID_SPANS && columns.count > 1 && !even,
                            imageHeightDp = tileImageHeight,
                        )
                    }
                }
                val metricsNoImage = remember(metricsBySpan) {
                    metricsBySpan.mapValues { (_, m) -> CardMetrics(m.span, m.widthDp, m.wide, rowHasImage = false, imageHeightDp = m.imageHeightDp) }
                }
                // Per article: does any card of its row have an image? (Rows without one do not reserve a picture area.)
                val rowHasImage = remember(items, spans, showImages) {
                    val starts = MagazinePlan.rowStarts(spans)
                    val rowFlags = HashMap<Int, Boolean>()
                    if (showImages) items.forEachIndexed { i, row -> if (row.imageUrl != null) rowFlags[starts[i]] = true }
                    BooleanArray(items.size) { i -> rowFlags[starts[i]] == true }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(MagazinePlan.GRID_SPANS),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().testTag("article_list"),
                    contentPadding = PaddingValues(start = CardGridLayout.EDGE_DP.dp, end = CardGridLayout.EDGE_DP.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(CardGridLayout.GAP_DP.dp),
                    horizontalArrangement = Arrangement.spacedBy(CardGridLayout.GAP_DP.dp),
                ) {
                    itemsIndexed(
                        items,
                        key = { _, row -> row.id },
                        span = { index, _ -> GridItemSpan(spans.getOrElse(index) { MagazinePlan.GRID_SPANS }) },
                        contentType = { index, _ -> spans.getOrElse(index) { MagazinePlan.GRID_SPANS } },
                    ) { index, row ->
                        ArticleItem(
                            row, mode, showImages, imageLoader, now, actions, Modifier.testTag("article_${row.id}"),
                            (if (rowHasImage.getOrElse(index) { true }) metricsBySpan else metricsNoImage).getValue(spans.getOrElse(index) { MagazinePlan.GRID_SPANS }),
                        )
                    }
                }
            }
        } else if (listState != null) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("article_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(items, key = { it.id }, contentType = { mode }) { row ->
                    ArticleItem(row, mode, showImages, imageLoader, now, actions, Modifier.testTag("article_${row.id}"))
                }
            }
        }
        if (newCount > 0 && firstNewIndex >= 0) {
            // New arrivals never move the list on their own; the user jumps explicitly.
            FilledTonalButton(
                onClick = {
                    val jumpTo = firstNewIndex
                    scope.launch { if (jumpTo >= 0) currentScroll.scrollTo(jumpTo) }
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).testTag("new_articles_banner"),
            ) { Text(pluralStringResource(R.plurals.new_articles_banner, newCount, newCount)) }
        }
        if (farDown) {
            SmallFloatingActionButton(
                onClick = { scope.launch { currentScroll.animateToTop() } },
                // Bottom centre: the per-article buttons (save, menu) sit at the right edge and must stay reachable.
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).testTag("scroll_to_top"),
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
            ) {
                Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = stringResource(R.string.scroll_to_top))
            }
        }
    }
}

/** Narrowest tab underline: a one-letter title still gets a visible bar. */
private val TAB_INDICATOR_MIN_WIDTH = 24.dp
private val TAB_INDICATOR_HEIGHT = 3.dp

@Composable
private fun ScopeTabs(
    structure: Structure,
    pages: List<ArticleScope>,
    current: ArticleScope,
    pagerState: PagerState,
    onSelect: (ArticleScope) -> Unit,
    onReselect: () -> Unit,
) {
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
    // The highlighted tab and the underline follow the pager while it is dragged or animated; the selection (which
    // is saved) only follows once the page has settled, so it is not what the tabs wait for.
    val selected = pagerState.currentPage.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    // Where each title really is (screen coordinates), and where this row is. The underline is placed from these:
    // centred on the title, as wide as it with a minimum, and sliding between two titles with the pager's position.
    val titleBounds = remember { mutableStateMapOf<Int, Rect>() }
    var rowOrigin by remember { mutableStateOf(Offset.Zero) }
    val barColor = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().clipToBounds().onGloballyPositioned { rowOrigin = it.positionInRoot() }) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selected,
            edgePadding = 8.dp,
            minTabWidth = 48.dp,
            indicator = {},
        ) {
            tabs.forEachIndexed { index, (tabScope, label) ->
                Tab(
                    selected = index == selected,
                    onClick = {
                        if (index == selected) {
                            onReselect()
                        } else {
                            // Start moving the page at once; the selection catches up when the page has settled.
                            scope.launch { pagerState.animateScrollToPage(index) }
                            onSelect(tabScope)
                        }
                    },
                    text = {
                        Text(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.onGloballyPositioned { titleBounds[index] = it.boundsInRoot() },
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("tab_${tabScope.encode()}"),
                )
            }
        }
        // Left edge and width (px) of the underline relative to this row, or null until the titles are measured.
        fun Density.bar(): Pair<Int, Int>? {
            val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val from = position.toInt().coerceIn(0, tabs.lastIndex.coerceAtLeast(0))
            val to = (from + 1).coerceAtMost(tabs.lastIndex.coerceAtLeast(0))
            val a = titleBounds[from] ?: return null
            val b = titleBounds[to] ?: a
            val f = (position - from).coerceIn(0f, 1f)
            val centre = a.center.x + (b.center.x - a.center.x) * f
            val width = maxOf(a.width + (b.width - a.width) * f, TAB_INDICATOR_MIN_WIDTH.toPx())
            return (centre - width / 2f - rowOrigin.x).roundToInt() to width.roundToInt()
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .layout { measurable, constraints ->
                    val bar = bar()
                    val p = measurable.measure(constraints.copy(minWidth = bar?.second ?: 0, maxWidth = bar?.second ?: 0))
                    layout(p.width, p.height) { p.place(0, 0) }
                }
                .offset { IntOffset(bar()?.first ?: 0, 0) }
                .height(TAB_INDICATOR_HEIGHT)
                .background(barColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .testTag("tab_indicator"),
        )
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
            // An all-clear result shows nothing (no permanent "9 feeds: OK 9 · failed 0 ..." line); only what needs
            // attention is listed, with its count.
            val parts = buildList {
                if (report.failed.isNotEmpty() || waiting > 0 || report.notAttempted > 0 || report.httpPending > 0) {
                    if (ok > 0) add(stringResource(R.string.status_part_ok, ok))
                    if (report.failed.isNotEmpty()) add(stringResource(R.string.status_part_failed, report.failed.size))
                    if (waiting > 0) add(stringResource(R.string.status_part_waiting, waiting))
                    if (report.notAttempted > 0) add(stringResource(R.string.status_part_not_reached, report.notAttempted))
                    if (report.httpPending > 0) add(stringResource(R.string.status_part_http, report.httpPending))
                }
            }
            if (parts.isNotEmpty()) add(stringResource(R.string.status_result_compact, report.total, parts.joinToString(stringResource(R.string.status_part_separator))))
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
