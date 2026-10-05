package io.github.yuubiz.folirel.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.repository.ArticleRepository
import io.github.yuubiz.folirel.data.repository.SubscriptionRepository
import io.github.yuubiz.folirel.model.ArticleQuerySpec
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.FeedListFilter
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import io.github.yuubiz.folirel.model.Structure
import io.github.yuubiz.folirel.network.NetworkState
import io.github.yuubiz.folirel.sync.RefreshCoordinator
import io.github.yuubiz.folirel.sync.RefreshState
import java.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Article list for one resolved query. [key] identifies the list for position restore. */
data class ArticlesState(val key: String, val items: List<ArticleRow>, val loaded: Boolean)

sealed interface HomeEvent {
    data class Message(val kind: Kind) : HomeEvent {
        enum class Kind { SAVED, UNSAVED, MARKED_READ, MARKED_UNREAD, BULK_MARKED, UPDATE_FAILED }
    }
    data class BulkMarked(val count: Int) : HomeEvent
}

class HomeViewModel(
    private val articles: ArticleRepository,
    private val subscriptions: SubscriptionRepository,
    private val settingsRepository: SettingsRepository,
    private val coordinator: RefreshCoordinator,
    network: Flow<NetworkState>,
    private val savedState: SavedStateHandle,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel(), ListHost {

    private val accountId = MutableStateFlow<String?>(null)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _selection = MutableStateFlow(Selection())
    val selection: StateFlow<Selection> = _selection.asStateFlow()

    /** Raw text of the search field (updates immediately); the query applies after a 250 ms debounce. */
    private val _searchText = MutableStateFlow(savedState.get<String>(KEY_SEARCH).orEmpty())
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _searchActive = MutableStateFlow(savedState.get<Boolean>(KEY_SEARCH_ACTIVE) ?: false)
    val searchActive: StateFlow<Boolean> = _searchActive.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val structure: StateFlow<Structure> = accountId.filterNotNull()
        .flatMapLatest { subscriptions.observeStructure(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Structure())

    val refreshState: StateFlow<RefreshState> = coordinator.state

    /**
     * Feeds to look at (failed, held for HTTP, or not finished): the small warning on the subscriptions entry. It follows the
     * stored state, so it stays when the notice of an update is gone and ends when the feeds are fetched again.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val feedsNeedingAttention: StateFlow<Int> = accountId.filterNotNull()
        .flatMapLatest { account ->
            combine(structure, subscriptions.observeDiagnostics(account), coordinator.state.map { !it.running }.distinctUntilChanged()) { s, d, idle ->
                FeedListFilter.needingAttention(s, d, countUnfinished = idle)
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val network: StateFlow<NetworkState> = network.stateIn(viewModelScope, SharingStarted.Eagerly, NetworkState(true, false))

    /**
     * Articles inserted by finished refreshes that the user has not answered yet. Each list counts only the ones it
     * contains, so a banner never announces arrivals that the current category, filter or search does not show (REV-02).
     */
    private val notices = NewArticleNotices()
    val newArticleIds: StateFlow<Set<String>> = notices.pending

    /**
     * The notice a finished update left (H02), kept for a few seconds. A request that has been seen once never makes a second
     * notice (see the collector below), and the board drops a notice by its own request id.
     */
    private val noticeBoard = RefreshNoticeBoard(clock, viewModelScope, NOTICE_KEEP_MS)
    internal val refreshNotice: StateFlow<RefreshNotice?> = noticeBoard.current

    /** Pages of the category pager, in tab order: All, Uncategorized (when present), categories with feeds. */
    val pageScopes: StateFlow<List<ArticleScope>> = structure
        .map { s ->
            buildList {
                add(ArticleScope.All)
                if (s.hasUncategorized) add(ArticleScope.Uncategorized)
                s.tabCategories.forEach { add(ArticleScope.Category(it.id)) }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(ArticleScope.All))

    private val pageLists = HashMap<ArticleScope, StateFlow<ArticlesState>>()

    private val events = Channel<HomeEvent>(Channel.BUFFERED)
    val eventFlow: Flow<HomeEvent> = events.receiveAsFlow()

    private val _selectionReady = MutableStateFlow(false)

    /**
     * Articles of one pager page (or of the single-feed view) with the shared filter, sort and search. Only
     * pages that are composed collect their flow, so hidden categories do not keep a DB query open (REV-05).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pageList(scope: ArticleScope): StateFlow<ArticlesState> = pageLists.getOrPut(scope) {
        combine(accountId.filterNotNull(), _selection, structure, _selectionReady) { account, selection, structure, ready ->
            if (!ready || !structure.loaded) null else specFor(account, selection.copy(scope = scope), structure)
        }
            .distinctUntilChanged()
            .flatMapLatest { spec ->
                if (spec == null) emptyFlow() else articles.observe(spec).map { ArticlesState(listKey(spec), it, loaded = true) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArticlesState("", emptyList(), loaded = false))
    }

    init {
        viewModelScope.launch {
            accountId.value = subscriptions.ensureLocalAccount()
        }
        viewModelScope.launch {
            // Process restore: SavedStateHandle wins; otherwise the persisted last selection.
            val restoredScope = savedState.get<String>(KEY_SCOPE)
            val initial = if (restoredScope != null) {
                Selection(
                    scope = ArticleScope.decode(restoredScope),
                    filter = savedState.get<String>(KEY_FILTER)?.let { ReadFilter.valueOf(it) } ?: ReadFilter.ALL,
                    sort = savedState.get<String>(KEY_SORT)?.let { SortOrder.valueOf(it) } ?: SortOrder.NEWEST,
                    search = savedState.get<String>(KEY_SEARCH).orEmpty(),
                )
            } else {
                val s = settingsRepository.settings.first()
                Selection(scope = s.lastScope, filter = s.lastFilter, sort = s.sortOrder)
            }
            _selection.value = initial
            _selectionReady.value = true
        }
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _selectionReady.first { it }
            _searchText.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged().collect { text ->
                updateSelection { it.copy(search = text.trim()) }
            }
        }
        viewModelScope.launch {
            val seen = coordinator.state.value.requests.filter { !it.running }.map { it.id }.toMutableSet()
            coordinator.state.collect { state ->
                for (request in state.requests) {
                    if (request.running || !seen.add(request.id)) continue
                    val added = request.report?.newArticleIds.orEmpty()
                    notices.announce(added)
                    RefreshNotice.of(request, state)?.let { noticeBoard.post(it) }
                }
            }
        }
        viewModelScope.launch {
            // A scope pointing to a deleted category falls back to "All".
            structure.collect { s ->
                val scope = _selection.value.scope
                if (s.loaded && scope is ArticleScope.Category && s.categories.none { it.id == scope.categoryId }) {
                    selectScope(ArticleScope.All)
                }
            }
        }
    }

    private fun specFor(account: String, selection: Selection, structure: Structure): ArticleQuerySpec {
        val categoryIds = (selection.scope as? ArticleScope.Category)?.let { structure.categoryIdsFor(it.categoryId) }.orEmpty()
        return ArticleQuerySpec(account, selection.scope, selection.filter, selection.sort, selection.search, categoryIds)
    }

    /**
     * Position key. List mode is deliberately not part of it: switching Magazine/List keeps the
     * same first visible article instead of starting a separate position.
     */
    private fun listKey(spec: ArticleQuerySpec): String =
        listOf(spec.accountId, spec.scope.encode(), spec.filter.name, spec.sort.name, spec.search).joinToString("|")

    private fun updateSelection(transform: (Selection) -> Selection) {
        val updated = transform(_selection.value)
        _selection.value = updated
        savedState[KEY_SCOPE] = updated.scope.encode()
        savedState[KEY_FILTER] = updated.filter.name
        savedState[KEY_SORT] = updated.sort.name
        savedState[KEY_SEARCH] = updated.search
    }

    /** Scope change keeps the current filter (docs/product-and-ux.md S10). */
    fun selectScope(scope: ArticleScope) {
        updateSelection { it.copy(scope = scope) }
        viewModelScope.launch { settingsRepository.setLastScope(scope) }
    }

    fun selectFilter(filter: ReadFilter) {
        updateSelection { it.copy(filter = filter) }
        viewModelScope.launch { settingsRepository.setLastFilter(filter) }
    }

    /** Drawer "Saved articles": whole scope + saved filter. */
    fun showSaved() {
        updateSelection { it.copy(scope = ArticleScope.All, filter = ReadFilter.SAVED) }
        viewModelScope.launch {
            settingsRepository.setLastScope(ArticleScope.All)
            settingsRepository.setLastFilter(ReadFilter.SAVED)
        }
    }

    fun selectSort(order: SortOrder) {
        updateSelection { it.copy(sort = order) }
        viewModelScope.launch { settingsRepository.setSortOrder(order) }
    }

    /** "Open" in the http:// dialog with the box ticked. Cancelling never calls this, so nothing is saved then. */
    fun stopAskingBeforeHttpOpen() {
        viewModelScope.launch { settingsRepository.setConfirmHttpOpen(false) }
    }

    /** Pins the category to the front of the tabs, or unpins it. Changes nothing else: no article, no subscription, no selection. */
    fun togglePinned(categoryId: String) {
        val pinned = structure.value.preferences[categoryId]?.isPinned == true
        viewModelScope.launch {
            val account = accountId.value ?: return@launch
            val ok = runCatching { subscriptions.setCategoryPinned(account, categoryId, !pinned) }.getOrDefault(false)
            if (!ok) events.send(HomeEvent.Message(HomeEvent.Message.Kind.UPDATE_FAILED))
        }
    }

    fun setListMode(mode: ListMode) {
        viewModelScope.launch { settingsRepository.setListMode(mode) }
    }

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    fun setSearchActive(active: Boolean) {
        _searchActive.value = active
        savedState[KEY_SEARCH_ACTIVE] = active
        if (!active) {
            _searchText.value = ""
            updateSelection { it.copy(search = "") }
        }
    }

    // Manual state changes: confirmed only after the DB write succeeded.

    fun toggleSaved(row: ArticleRow) = viewModelScope.launch {
        val ok = runCatching { articles.setSaved(row.id, !row.isSaved) }.getOrDefault(false)
        events.send(
            if (!ok) HomeEvent.Message(HomeEvent.Message.Kind.UPDATE_FAILED)
            else HomeEvent.Message(if (row.isSaved) HomeEvent.Message.Kind.UNSAVED else HomeEvent.Message.Kind.SAVED),
        )
    }

    fun setRead(row: ArticleRow, read: Boolean) = viewModelScope.launch {
        val ok = runCatching { articles.setRead(row.id, read) }.getOrDefault(false)
        events.send(
            if (!ok) HomeEvent.Message(HomeEvent.Message.Kind.UPDATE_FAILED)
            else HomeEvent.Message(if (read) HomeEvent.Message.Kind.MARKED_READ else HomeEvent.Message.Kind.MARKED_UNREAD),
        )
    }

    /** Captures the full matching unread set (not just loaded rows) when the confirmation opens. */
    suspend fun prepareMarkAllRead(scopeTitle: String): MarkAllReadRequest? {
        val account = accountId.value ?: return null
        val selection = _selection.value
        val ids = articles.unreadIdsSnapshot(specFor(account, selection, structure.value))
        return MarkAllReadRequest(ids, scopeTitle, selection.filter, selection.search)
    }

    fun confirmMarkAllRead(request: MarkAllReadRequest) = viewModelScope.launch {
        val changed = runCatching { articles.markRead(request.ids) }.getOrNull()
        events.send(if (changed == null) HomeEvent.Message(HomeEvent.Message.Kind.UPDATE_FAILED) else HomeEvent.BulkMarked(changed))
    }

    /** Manual refresh of the shown scope. Filter, sort and search are display conditions only (SYNC-02). */
    fun refreshCurrentScope() {
        val target = refreshTargetFor(_selection.value)
        viewModelScope.launch { coordinator.refresh(target, RefreshReason.MANUAL) }
    }

    /** Feeds whose last request did not finish or failed; a valid Retry-After is still respected. */
    fun retryPending() {
        viewModelScope.launch { coordinator.refresh(RefreshScope.Pending, RefreshReason.MANUAL) }
    }

    /** The notice ran out or was closed. Only the notice of that request: a newer one stays. */
    internal fun dismissNotice(requestId: Long) = noticeBoard.dismiss(requestId)

    /** The banner was tapped, the list went to its top, or the banner was dismissed: these are no longer announced. */
    override fun acknowledgeNew(ids: Collection<String>) = notices.acknowledge(ids)

    // Position restore per list (scope + filter + sort + search): article id + pixel offset, bounded history.
    // Survives rotation and process death; switching category and coming back restores each list (REV-01).

    private val anchors = LinkedHashMap<String, ListAnchor>().apply {
        savedState.get<Array<String>>(KEY_ANCHORS)?.forEach { line ->
            val parts = line.split(ANCHOR_SEPARATOR)
            if (parts.size == 4) {
                val sortAt = parts[2].toLongOrNull() ?: return@forEach
                put(parts[0], ListAnchor(parts[0], parts[1], sortAt, parts[3].toIntOrNull() ?: 0))
            }
        }
    }

    override fun saveAnchor(anchor: ListAnchor) {
        if (anchors[anchor.listKey] == anchor) return
        anchors.remove(anchor.listKey)
        anchors[anchor.listKey] = anchor
        while (anchors.size > MAX_ANCHORS) anchors.remove(anchors.keys.first())
        savedState[KEY_ANCHORS] = anchors.values
            .map { listOf(it.listKey, it.articleId, it.sortAt.toString(), it.offset.toString()).joinToString(ANCHOR_SEPARATOR) }
            .toTypedArray()
    }

    override fun anchorFor(listKey: String): ListAnchor? = anchors[listKey]

    companion object {
        const val SEARCH_DEBOUNCE_MS = 250L

        /** How long the vm keeps a notice at most; the screen hides it earlier (see HomeNotices). */
        const val NOTICE_KEEP_MS = 30_000L
        private const val KEY_SCOPE = "scope"
        private const val KEY_FILTER = "filter"
        private const val KEY_SORT = "sort"
        private const val KEY_SEARCH = "search"
        private const val KEY_SEARCH_ACTIVE = "search_active"
        private const val KEY_ANCHORS = "anchors"
        private const val ANCHOR_SEPARATOR = "\u0001"
        private const val MAX_ANCHORS = 40

        fun refreshTargetFor(selection: Selection): RefreshScope = when (val scope = selection.scope) {
            ArticleScope.All -> RefreshScope.All
            ArticleScope.Uncategorized -> RefreshScope.Uncategorized
            is ArticleScope.Category -> RefreshScope.Category(scope.categoryId)
            is ArticleScope.Feed -> RefreshScope.Feeds(listOf(scope.feedId))
        }

        fun scopeOf(scope: ArticleScope): RefreshScope = refreshTargetFor(Selection(scope = scope))
    }
}
