package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.ui.unit.sp
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInParent
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
import kotlin.math.abs
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.backend.FeedResult
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.browser.BrowserLauncher
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.BrowserMode
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.CategoryDisplay
import io.github.yuubiz.folirel.model.ResolvedLayout
import io.github.yuubiz.folirel.ui.common.LayoutPicker
import io.github.yuubiz.folirel.ui.common.SectionTitle
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import io.github.yuubiz.folirel.model.Structure
import io.github.yuubiz.folirel.sync.HttpsUpgradeState
import io.github.yuubiz.folirel.sync.RefreshState
import io.github.yuubiz.folirel.sync.RequestStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeNavigation(
    val importOpml: () -> Unit,
    val addFeed: () -> Unit,
    val subscriptions: () -> Unit,
    val settings: () -> Unit,
    /** "Organize categories and tabs": the order and the pins of the tabs. */
    val organizeTabs: () -> Unit = {},
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
    val settingsLoaded by vm.settingsLoaded.collectAsStateWithLifecycle()
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
    val notice by vm.refreshNotice.collectAsStateWithLifecycle()
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
    var displayOpen by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

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
        if (network.metered && settings.unmeteredOnly && network.connected && settings.confirmMeteredRefresh) meteredConfirm = true else vm.refreshCurrentScope()
    }

    val scopeTitle = scopeTitle(selection.scope, structure)
    val unreadInList = list.items.count { !it.isRead }
    val target = HomeViewModel.refreshTargetFor(selection)
    val attention by vm.feedsNeedingAttention.collectAsStateWithLifecycle()

    ModalNavigationDrawer(
        drawerState = drawerState,
        // A closed drawer opens from the menu button only, so horizontal swipes belong to the category pager.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            HomeDrawer(
                structure = structure,
                selection = selection,
                attention = attention,
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
      BoxWithConstraints(Modifier.fillMaxSize()) {
        // Narrow windows give the search to the overflow menu: the title (the way to the categories) and the display panel keep their room.
        val narrow = maxWidth < NARROW_BAR_DP.dp
        val bigText = LocalDensity.current.fontScale > 1.3f
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    expandedHeight = if (bigText) 80.dp else 56.dp,
                    navigationIcon = {
                        if (searchActive) {
                            IconButton(onClick = { vm.setSearchActive(false) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close_search))
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }, modifier = Modifier.testTag("drawer_open")) {
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
                            ScopeTitle(scopeTitle, "${filterLabel(selection.filter)} · ${sortLabel(selection.sort)}") { pickerOpen = true }
                        }
                    },
                    actions = {
                        // What stays when the notices are gone, as small marks that take no room from the list: no connection,
                        // and feeds whose articles could not be saved (their reasons are in subscription management).
                        if (!network.connected) {
                            Icon(
                                painterResource(R.drawable.ic_cloud_off),
                                contentDescription = stringResource(R.string.status_offline),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp).size(20.dp).testTag("offline_indicator"),
                            )
                        }
                        if (structure.feedsFailingToSave > 0) {
                            IconButton(onClick = nav.subscriptions, modifier = Modifier.testTag("storage_warning")) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = pluralStringResource(R.plurals.status_storage_failed, structure.feedsFailingToSave, structure.feedsFailingToSave),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        IconButton(onClick = { displayOpen = true }, modifier = Modifier.testTag("display_open")) {
                            Icon(painterResource(R.drawable.ic_tune), stringResource(R.string.display_open))
                        }
                        if (!searchActive && !narrow) {
                            IconButton(onClick = { vm.setSearchActive(true) }, modifier = Modifier.testTag("search_open")) {
                                Icon(Icons.Filled.Search, stringResource(R.string.action_search))
                            }
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }, modifier = Modifier.testTag("home_more")) {
                                BadgedBox(badge = { if (attention > 0) Badge(Modifier.testTag("attention_badge")) }) {
                                    Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more))
                                }
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                if (!searchActive && narrow) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_search)) },
                                        onClick = { overflowOpen = false; vm.setSearchActive(true) },
                                        modifier = Modifier.testTag("menu_search"),
                                    )
                                }
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
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.organize_tabs)) },
                                    onClick = { overflowOpen = false; nav.organizeTabs() },
                                    modifier = Modifier.testTag("menu_organize_tabs"),
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_import_opml)) }, onClick = { overflowOpen = false; nav.importOpml() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_add_feed)) }, onClick = { overflowOpen = false; nav.addFeed() })
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.nav_subscriptions)) },
                                    trailingIcon = if (attention > 0) ({ AttentionCount(attention) }) else null,
                                    onClick = { overflowOpen = false; nav.subscriptions() },
                                    modifier = Modifier.testTag("menu_subscriptions"),
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.nav_settings)) }, onClick = { overflowOpen = false; nav.settings() })
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                // A scope that is not a page (a single feed, or a category with no subscribed feed) is shown on its own without tabs:
                // it is never silently replaced by another category.
                val pagerMode = pages.contains(selection.scope) && structure.subscribedFeeds.isNotEmpty()
                if (pagerMode) {
                    ScopeTabs(tabItems(structure, pages), selection.scope, pagerState, vm::selectScope) { scrollToTopTick++ }
                }
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
                            // Each page resolves its own layout: a page does not take the layout of the one shown beside it.
                            layout = CategoryDisplay.resolve(pageScope, ResolvedLayout(settings.listMode, settings.cardStyle, settings.cardColumns), structure.preferences),
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
                        !structure.loaded || !settingsLoaded -> Unit
                        structure.feeds.isEmpty() -> NoSubscriptions(nav.importOpml, nav.addFeed)
                        pagerMode -> CategoryPager(vm, pages, selection.scope, pagerState, page)
                        else -> page(selection.scope, true)
                    }
                    HomeNotices(
                        refresh = refresh,
                        target = target,
                        structure = structure,
                        checkingHttps = checkingHttps,
                        notice = notice,
                        onDismissNotice = vm::dismissNotice,
                        onOpenSubscriptions = nav.subscriptions,
                        onRetry = vm::retryPending,
                    )
                }
            }
        }
      }
    }

    if (pickerOpen) {
        CategoryPicker(
            structure = structure,
            selected = selection.scope,
            // The one already shown: the sheet only closes (no scroll to top, no notice answered).
            onChoose = { chosen -> pickerOpen = false; if (chosen != selection.scope) vm.selectScope(chosen) },
            onTogglePin = vm::togglePinned,
            onOrganize = { pickerOpen = false; nav.organizeTabs() },
            onDismiss = { pickerOpen = false },
        )
    }

    var layoutDetails by rememberSaveable { mutableStateOf(false) }
    val globalLayout = ResolvedLayout(settings.listMode, settings.cardStyle, settings.cardColumns)
    val shownCategory = (selection.scope as? ArticleScope.Category)?.categoryId?.let { id -> structure.categories.firstOrNull { it.id == id } }
    if (displayOpen) {
        DisplayPanel(
            filter = selection.filter,
            sort = selection.sort,
            onFilter = vm::selectFilter,
            onSort = vm::selectSort,
            onDismiss = { displayOpen = false },
        ) {
            if (shownCategory != null) {
                // A category: the choice is this category's own, and "use the default" is a choice of its own.
                val own = structure.preferences[shownCategory.id]
                SectionTitle(stringResource(R.string.display_layout_category))
                LayoutPicker(
                    selected = own?.listLayout,
                    onSelect = { vm.setLayout(selection.scope, it) },
                    inheritLabel = stringResource(R.string.layout_inherit, listModeLabel(settings.listMode)),
                    tagPrefix = "layout_",
                )
                Text(
                    stringResource(R.string.display_layout_category_note, shownCategory.display),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { layoutDetails = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("layout_details")) {
                    Text(stringResource(R.string.display_layout_details))
                }
            } else {
                SectionTitle(stringResource(R.string.display_layout))
                LayoutPicker(
                    selected = settings.listMode,
                    onSelect = { it?.let { mode -> vm.setLayout(selection.scope, mode) } },
                    tagPrefix = "layout_",
                )
                Text(stringResource(R.string.display_layout_global_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (layoutDetails && shownCategory != null) {
        CategoryLayoutSheet(
            categoryName = shownCategory.display,
            preference = structure.preferences[shownCategory.id],
            global = globalLayout,
            onList = { vm.setCategoryListLayout(shownCategory.id, it) },
            onStyle = { vm.setCategoryCardStyle(shownCategory.id, it) },
            onColumns = { vm.setCategoryCardColumns(shownCategory.id, it) },
            onReset = { vm.resetCategoryLayout(shownCategory.id) },
            onDismiss = { layoutDetails = false },
        )
    }

    markAllRequest?.let { request ->
        MarkAllReadDialog(request, onConfirm = { vm.confirmMarkAllRead(request); markAllRequest = null }, onDismiss = { markAllRequest = null })
    }
    if (meteredConfirm) {
        MeteredRefreshDialog(
            onUpdate = { dontAskAgain ->
                meteredConfirm = false
                if (dontAskAgain) vm.stopAskingBeforeMeteredRefresh()
                vm.refreshCurrentScope()
            },
            onDismiss = { meteredConfirm = false },
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
 * Confirmation before a manual update on a metered network (automatic updates wait for an unmetered one). The "don't ask
 * again" box starts unticked; it is saved only through [onUpdate], never by cancelling, and Settings can turn the question
 * back on.
 */
@Composable
private fun MeteredRefreshDialog(onUpdate: (dontAskAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontAskAgain by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.metered_title)) },
        text = {
            Column {
                Text(stringResource(R.string.metered_message))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = dontAskAgain, role = Role.Checkbox, onValueChange = { dontAskAgain = it })
                        .testTag("metered_dont_ask"),
                ) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = null)
                    Text(stringResource(R.string.metered_dont_ask), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onUpdate(dontAskAgain) }, modifier = Modifier.testTag("metered_confirm")) { Text(stringResource(R.string.metered_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("metered_cancel")) { Text(stringResource(R.string.cancel)) } },
    )
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
 *
 * Three things are kept apart: the position the pager is drawn at (offset, followed by the tabs), the category the reader chose
 * (the selection, an id, set at once by a tap) and the page the pager settled on (what a swipe commits, see [SettleGuard]).
 * The pages are ids: a reorder or a pin changes the index of a category, never which category is selected.
 */
@Composable
private fun CategoryPager(
    vm: HomeViewModel,
    pages: List<ArticleScope>,
    selected: ArticleScope,
    pagerState: PagerState,
    page: @Composable (ArticleScope, Boolean) -> Unit,
) {
    val currentPages by rememberUpdatedState(pages)
    var drags by remember { mutableIntStateOf(0) }
    val guard = remember { SettleGuard(selected) }
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { if (it is DragInteraction.Start) drags++ }
    }
    // The chosen category is the page to show. A choice (tab, drawer, picker) moves the pager; the same category at another index
    // (the pages were reordered) puts the pager back on it at once. A far move lands directly instead of sweeping the pages between.
    var lastChoice by remember { mutableStateOf(selected) }
    LaunchedEffect(selected, pages) {
        val index = pages.indexOf(selected)
        val chosen = lastChoice != selected
        lastChoice = selected
        if (index < 0) return@LaunchedEffect
        if (pagerState.currentPage == index && pagerState.targetPage == index) return@LaunchedEffect
        if (!chosen || abs(index - pagerState.currentPage) > 1) pagerState.scrollToPage(index) else pagerState.animateScrollToPage(index)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            val scopeAtPage = currentPages.getOrNull(index) ?: return@collect
            guard.onSettled(scopeAtPage, vm.selection.value.scope, drags)?.let { vm.selectScope(it) }
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
    layout: ResolvedLayout,
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
                mode = layout.listMode,
                showImages = settings.showImages,
                cardColumns = layout.cardColumns,
                cardStyle = layout.cardStyle,
                imageLoader = imageLoader,
                actions = actions,
                newIds = newIds,
                newestFirst = selection.sort == SortOrder.NEWEST,
                isCurrent = isCurrent,
                isSelected = pageScope == selection.scope,
                scrollToTopTick = scrollToTopTick,
            )
        }
    }
}

/** Items scrolled past before the "back to top" button appears. */
private const val SCROLL_TOP_BUTTON_AFTER = 6

/**
 * After "back to top" the list keeps following articles that arrive at its head for this long (a refresh delivers feed
 * after feed), so the newest article is not left a few rows above the screen. Any touch of the list (a finger put down,
 * a drag, a long press, a tap on a card or its menu) or a wheel scroll ends it at once.
 */
private const val FOLLOW_HEAD_AFTER_BACK_TO_TOP_MS = 4_000L

private const val MOVE_TO_TOP = "top"

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
    /** The category the reader has chosen is this list's: false from the moment another one is chosen, while the pager may still show this page. */
    isSelected: Boolean = true,
) {
    // The width decides the columns of the tile layouts (Masonry, Bento), whose positions depend on them.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        ArticleListBody(
            maxWidth.value, host, state, mode, showImages, cardColumns, cardStyle, imageLoader, actions, newIds, newestFirst, isCurrent,
            scrollToTopTick, isSelected,
        )
    }
}

@Composable
private fun ArticleListBody(
    widthDp: Float,
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
    isSelected: Boolean,
) {
    // While the list moves, it keeps the articles it is moving over: a refresh delivers feed after feed, and articles
    // inserted above the moving list would change its distance on every batch. They are applied when the movement ends.
    var held by remember(state.key) { mutableStateOf<List<ArticleRow>?>(null) }
    val items = held ?: state.items
    // Masonry and Bento: their own columns (two on a phone), and Bento's blocks of tiles (a position is an article; the column
    // shows blocks).
    val tileColumns = CardGridLayout.columns(widthDp, LocalDensity.current.fontScale, cardColumns, CardGridLayout.TILE_AUTO_MIN_CARD_DP).count
    val bentoBlocks = if (mode == ListMode.BENTO) remember(items, tileColumns, newestFirst) { BentoPlan.blocks(items.map { it.id }, tileColumns, fromEnd = newestFirst) } else null
    val blockOf = remember(bentoBlocks) { bentoBlocks?.let { BentoPlan.blockOfArticle(it, items.size) } ?: IntArray(0) }
    val firstArticleOf = remember(bentoBlocks) { bentoBlocks?.let { b -> IntArray(b.size) { b[it].first } } ?: IntArray(0) }
    // Restore from this list's own anchor (article id + offset); if it is gone, use the nearest remaining article.
    // Switching list style, rotating or resizing the window keeps the same article near the top.
    val start = remember(state.key, mode) {
        val anchor = host.anchorFor(state.key)
        val index = anchor?.let { a ->
            items.indexOfFirst { it.id == a.articleId }.takeIf { it >= 0 }
                ?: items.indexOfFirst { if (newestFirst) it.sortAt <= a.sortAt else it.sortAt >= a.sortAt }.takeIf { it >= 0 }
                ?: (items.size - 1)
        } ?: 0
        index.coerceAtLeast(0) to (if (anchor != null && items.getOrNull(index)?.id == anchor.articleId) anchor.offset else 0)
    }
    var gridState: LazyGridState? = null
    var listState: LazyListState? = null
    var staggeredState: LazyStaggeredGridState? = null
    var blockState: LazyListState? = null
    val scroll: ScrollHandle = when (mode) {
        ListMode.MAGAZINE -> GridScroll(remember(state.key) { LazyGridState(start.first, start.second) }.also { gridState = it })
        ListMode.MASONRY -> StaggeredScroll(remember(state.key) { LazyStaggeredGridState(start.first, start.second) }.also { staggeredState = it })
        ListMode.BENTO -> BlockScroll(
            remember(state.key) { LazyListState(blockOf.getOrElse(start.first) { 0 }, start.second) }.also { blockState = it },
            blockOf,
            firstArticleOf,
        )
        else -> ListScroll(remember(state.key) { LazyListState(start.first, start.second) }.also { listState = it })
    }
    val currentItems by rememberUpdatedState(items)
    val currentNew by rememberUpdatedState(newIds)
    val currentScroll by rememberUpdatedState(scroll)
    LaunchedEffect(scroll.owner, state.key) {
        snapshotFlow { currentScroll.firstIndex to currentScroll.firstOffset }.collect { (index, offset) ->
            currentItems.getOrNull(index)?.let { host.saveAnchor(ListAnchor(state.key, it.id, it.sortAt, offset)) }
        }
    }
    var followHeadUntil by remember(scroll.owner) { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val mover = remember(scroll.owner) { ListMover(scope) }
    DisposableEffect(mover) { onDispose { mover.cancel() } }

    /**
     * The user answers the new-article notice: every new article this list shows is acknowledged at once, at the moment of
     * the request. A movement that is cancelled afterwards does not bring the notice back, and nothing here marks an
     * article read.
     */
    fun answerNotice() {
        host.acknowledgeNew(currentItems.filter { it.id in currentNew }.map { it.id })
    }

    /** One movement back to the start of the list. A second request while it runs joins it instead of starting it over. */
    fun goToTop() {
        mover.launch(MOVE_TO_TOP) {
            held = currentItems
            var arrived = false
            try {
                currentScroll.glideToStart()
                arrived = true
            } finally {
                // Armed before the held articles are released, so what arrived meanwhile is followed (and never after a touch).
                if (arrived) followHeadUntil = SystemClock.uptimeMillis() + FOLLOW_HEAD_AFTER_BACK_TO_TOP_MS
                held = null
            }
        }
    }

    fun backToTop() {
        answerNotice()
        goToTop()
    }

    /** The banner of an oldest-first list: to the first new article in that order, or to the start when it is gone. */
    fun goToArticle(articleId: String) {
        val index = currentItems.indexOfFirst { it.id == articleId }
        if (index < 0) goToTop() else mover.launch(articleId) { currentScroll.scrollTo(index) }
    }

    // "Back to top": the selected tab was tapped again (only the list on screen follows, and only a tap made after this list appeared).
    val firstTick = remember { scrollToTopTick }
    LaunchedEffect(scrollToTopTick) {
        if (isCurrent && scrollToTopTick != firstTick) backToTop()
    }
    // Shortly after "back to top" an article that arrives at the head is followed (otherwise the list stays on the article it
    // ended on and the newest ones sit above the screen); the user touching the list ends this (see the pointerInput below).
    val headId = items.firstOrNull()?.id
    LaunchedEffect(headId) {
        if (isSelected && SystemClock.uptimeMillis() < followHeadUntil) currentScroll.scrollTo(0)
    }
    // Another category was chosen (tab, drawer): what this list moves and follows belongs to the category left, so both end now.
    // The pager may keep this page composed until the slide has settled; ending must not wait for the page to go.
    LaunchedEffect(isSelected) {
        if (!isSelected) {
            followHeadUntil = 0L
            mover.cancel()
        }
    }
    // Far enough down that the way back is long; the button is for that case only.
    val farDown by remember(scroll.owner) { derivedStateOf { currentScroll.firstIndex > SCROLL_TOP_BUTTON_AFTER } }
    // The banner counts only the new articles this list shows.
    val newCount = remember(items, newIds) { items.count { it.id in newIds } }
    val now = remember(items) { System.currentTimeMillis() }
    // Where the buttons that ask for a movement are (in this box): a press on them is the request itself, not a touch of the list.
    val controls = remember { ControlBounds() }
    Box(
        Modifier.fillMaxSize().pointerInput(mover) {
            // Watches only: the Initial pass sees every press and wheel scroll first and consumes nothing, so taps, swipes and
            // long presses reach the cards as before. A touch ends the movement and the following (the user comes first), except a
            // press on the arrow or the notice: that press is the request for a movement, which then joins the one that runs.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressOnAControl = event.type == PointerEventType.Press && event.changes.firstOrNull()?.position?.let { controls.contains(it) } == true
                    if ((event.type == PointerEventType.Press && !pressOnAControl) || event.type == PointerEventType.Scroll) {
                        followHeadUntil = 0L
                        mover.cancel()
                    }
                }
            }
        },
    ) {
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
                val starts = remember(spans) { MagazinePlan.rowStarts(spans) }
                val rowHasImage = remember(items, starts, showImages) {
                    val rowFlags = HashMap<Int, Boolean>()
                    if (showImages) items.forEachIndexed { i, row -> if (row.imageUrl != null) rowFlags[starts[i]] = true }
                    BooleanArray(items.size) { i -> rowFlags[starts[i]] == true }
                }
                // The height of each row of tiles from the real text of its own cards, measured the first time the row is shown
                // and kept while the articles, widths and text size stay the same (no full-list measuring, nothing per frame).
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer(cacheSize = 96)
                val tileStyles = cardTextStyles(multi = true)
                val untitled = stringResource(R.string.untitled)
                val rowPlans = remember(items, spans, widthDp, columns.count, showImages, density, tileStyles, tileImageHeight) { HashMap<Int, RowPlan?>() }
                fun planOf(index: Int): RowPlan? = rowPlans.getOrPut(starts[index]) {
                    val first = starts[index]
                    var last = first
                    while (last + 1 < items.size && starts[last + 1] == first) last++
                    if ((first..last).any { spans[it] >= MagazinePlan.GRID_SPANS }) {
                        null
                    } else {
                        MagazineRowPlanner.plan(
                            cards = (first..last).map { i ->
                                MagazineRowPlanner.Card(items[i], CardGridLayout.spanWidthDp(widthDp, spans[i]), showImages && items[i].imageUrl != null)
                            },
                            imageHeightDp = tileImageHeight,
                            styles = tileStyles,
                            untitled = untitled,
                            measurer = measurer,
                            density = density,
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(MagazinePlan.GRID_SPANS),
                    state = gridState!!,
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
                        val plan = planOf(index)
                        ArticleItem(
                            row, mode, showImages, imageLoader, now, actions, Modifier.testTag("article_${row.id}"),
                            (if (rowHasImage.getOrElse(index) { true }) metricsBySpan else metricsNoImage).getValue(spans.getOrElse(index) { MagazinePlan.GRID_SPANS }),
                            rowHeight = plan?.let { with(density) { it.heightPx.toDp() } },
                            summaryLines = plan?.summaryLines?.getOrNull(index - starts[index]) ?: MagazineCardStyle.SUMMARY_MAX_LINES,
                        )
                    }
                }
            }
        } else if (staggeredState != null) {
            // Masonry: lanes of cards; each picture has the shape its article asks for (known before it loads), text decides the rest.
            val cardWidth = CardGridLayout.cardWidthDp(widthDp, tileColumns)
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(tileColumns),
                state = staggeredState!!,
                modifier = Modifier.fillMaxSize().testTag("article_list"),
                contentPadding = PaddingValues(start = CardGridLayout.EDGE_DP.dp, end = CardGridLayout.EDGE_DP.dp, top = 4.dp, bottom = 24.dp),
                verticalItemSpacing = CardGridLayout.GAP_DP.dp,
                horizontalArrangement = Arrangement.spacedBy(CardGridLayout.GAP_DP.dp),
            ) {
                items(items, key = { it.id }) { row ->
                    ArticleItem(
                        row, mode, showImages, imageLoader, now, actions, Modifier.testTag("article_${row.id}"),
                        CardMetrics(
                            span = if (tileColumns > 1) MagazinePlan.GRID_SPANS / tileColumns else MagazinePlan.GRID_SPANS,
                            widthDp = cardWidth,
                            imageHeightDp = MasonryPlan.pictureHeightDp(row.id, cardWidth),
                        ),
                        summaryLines = MasonryPlan.SUMMARY_MAX_LINES,
                    )
                }
            }
        } else if (blockState != null && bentoBlocks != null) {
            // Bento: a column of blocks; every block fills all the columns with tiles of fixed cell sizes.
            val gap = CardGridLayout.GAP_DP
            val cellW = BentoPlan.cellWidthDp(widthDp, tileColumns)
            val titleLineDp = with(LocalDensity.current) { MagazineCardStyle.TILE_TITLE_LINE_SP.sp.toDp().value }
            val cellH = BentoPlan.cellHeightDp(cellW, titleLineDp)
            LazyColumn(
                state = blockState!!,
                modifier = Modifier.fillMaxSize().testTag("article_list"),
                contentPadding = PaddingValues(start = CardGridLayout.EDGE_DP.dp, end = CardGridLayout.EDGE_DP.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(gap.dp),
            ) {
                items(bentoBlocks, key = { "block-" + (items.getOrNull(it.first)?.id ?: it.first) }) { block ->
                    Box(Modifier.fillMaxWidth().height((block.rows * cellH + (block.rows - 1) * gap).dp)) {
                        for (tile in block.tiles) {
                            val row = items.getOrNull(tile.article) ?: continue
                            val w = tile.shape.colSpan * cellW + (tile.shape.colSpan - 1) * gap
                            val h = tile.shape.rowSpan * cellH + (tile.shape.rowSpan - 1) * gap
                            key(row.id) {
                                ArticleItem(
                                    row, mode, showImages, imageLoader, now, actions,
                                    Modifier.offset(x = (tile.col * (cellW + gap)).dp, y = (tile.row * (cellH + gap)).dp).testTag("article_${row.id}"),
                                    bento = BentoCell(tile.shape, w, h),
                                )
                            }
                        }
                    }
                }
            }
        } else if (listState != null) {
            LazyColumn(state = listState!!, modifier = Modifier.fillMaxSize().testTag("article_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(items, key = { it.id }, contentType = { mode }) { row ->
                    ArticleItem(row, mode, showImages, imageLoader, now, actions, Modifier.testTag("article_${row.id}"))
                }
            }
        }
        if (newCount > 0) {
            DisposableEffect(Unit) { onDispose { controls.banner = null } }
            // New arrivals never move the list on their own; one tap answers the notice: it goes away and the list moves.
            NewArticlesBanner(
                count = newCount,
                toTop = newestFirst,
                // Newest first the new articles are not necessarily at the head (a feed can deliver old articles), so the
                // banner says "to top" and goes there. Oldest first it goes to the first new article in that order.
                onClick = {
                    val firstNew = currentItems.firstOrNull { it.id in currentNew }?.id
                    answerNotice()
                    if (newestFirst || firstNew == null) goToTop() else goToArticle(firstNew)
                },
                onDismiss = { answerNotice() },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).onGloballyPositioned { controls.banner = it.boundsInParent() },
            )
        }
        if (farDown) {
            DisposableEffect(Unit) { onDispose { controls.arrow = null } }
            SmallFloatingActionButton(
                onClick = { backToTop() },
                // Bottom centre: the per-article buttons (save, menu) sit at the right edge and must stay reachable.
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).onGloballyPositioned { controls.arrow = it.boundsInParent() }.testTag("scroll_to_top"),
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
            ) {
                Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = stringResource(R.string.scroll_to_top))
            }
        }
    }
}

/** The notice for new articles: the text moves the list and closes the notice, the cross only closes it. */
@Composable
private fun NewArticlesBanner(count: Int, toTop: Boolean, onClick: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onClick,
                modifier = Modifier.testTag("new_articles_banner"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
            ) {
                Text(pluralStringResource(if (toTop) R.plurals.new_articles_banner_top else R.plurals.new_articles_banner_position, count, count))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp).testTag("new_articles_dismiss")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.new_articles_dismiss), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Narrowest window (dp) that keeps search on the bar itself. */
private const val NARROW_BAR_DP = 360

/** The category name with a small line under it for what the list shows (range and order); a press opens the way to the categories. */
@Composable
private fun ScopeTitle(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_choose_category), onClick = onClick)
            .testTag("scope_title"),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("scope_subtitle"),
        )
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

/** How many feeds need a look (failed, held for HTTP, unfinished): the small warning that outlives the notices (H02), next to "Subscriptions". */
@Composable
internal fun AttentionCount(count: Int) {
    val description = pluralStringResource(R.plurals.nav_subscriptions_attention, count, count)
    Badge(Modifier.semantics { contentDescription = description }.testTag("attention_count")) { Text(count.toString()) }
}
