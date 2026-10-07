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
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.FeedListFilter
import io.github.yuubiz.folirel.model.HiddenKeywords
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.PeriodFilter
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
    /** Raised after a full restore replaced the data (FullBackupService.replaced). */
    dataReplaced: Flow<Long> = emptyFlow(),
) : ViewModel(), ListHost {

    private val accountId = MutableStateFlow<String?>(null)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** The global display settings have been read: before that, a list would be drawn with defaults and then jump to the real ones. */
    val settingsLoaded: StateFlow<Boolean> = settingsRepository.settings.map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _selection = MutableStateFlow(Selection())
    val selection: StateFlow<Selection> = _selection.asStateFlow()

    /**
     * The moment the period filter counts back from. It is renewed when the period changes, when the app comes to the foreground
     * ([refreshPeriodClock]) and every [PERIOD_TICK_MS] while a period is set, so a "24 hours" list that stays open lets old
     * articles go. Seconds do not matter, hence the coarse tick. The clock is the injected one (tests move it).
     */
    private val periodNow = MutableStateFlow(clock.millis())

    /** The hidden keywords in effect (empty while the filter is off); null until the settings are read, so no list is drawn unfiltered first. */
    private val hiddenKeywords: StateFlow<List<String>?> = settingsRepository.settings
        .map<AppSettings, List<String>?> { it.activeHiddenKeywords }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
        // The time and the hidden keywords are inputs of the query like the selection: a change of either re-queries (and only then).
        combine(accountId.filterNotNull(), _selection, structure, _selectionReady, combine(periodNow, hiddenKeywords) { now, hidden -> now to hidden }) { account, selection, structure, ready, (now, hidden) ->
            if (!ready || !structure.loaded || hidden == null) null else specFor(account, selection.copy(scope = scope), structure, now, hidden)
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
                    period = restoredPeriod() ?: settingsRepository.settings.first().periodFilter,
                )
            } else {
                val s = settingsRepository.settings.first()
                Selection(scope = s.lastScope, filter = s.lastFilter, sort = s.sortOrder, period = restoredPeriod() ?: s.periodFilter)
            }
            _selection.value = initial
            periodNow.value = clock.millis()
            _selectionReady.value = true
        }
        viewModelScope.launch {
            // While a period is set the cutoff moves with the clock; with no period nothing changes, so nothing is re-queried.
            _selection.map { it.period }.distinctUntilChanged().collectLatest { period ->
                if (period == PeriodFilter.ALL) return@collectLatest
                while (true) {
                    delay(PERIOD_TICK_MS)
                    periodNow.value = clock.millis()
                }
            }
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
            // After a restore the list opens as the backup left it: its last selection (already All when it named nothing restored),
            // filter, order and period; no search, no pending arrivals, every list from its top.
            dataReplaced.drop(1).collect {
                val s = settingsRepository.settings.first()
                notices.acknowledge(notices.pending.value)
                setReturnScope(null)
                _searchText.value = ""
                _searchActive.value = false
                savedState[KEY_SEARCH_ACTIVE] = false
                anchors.clear()
                savedState[KEY_ANCHORS] = emptyArray<String>()
                periodNow.value = clock.millis()
                updateSelection { Selection(scope = s.lastScope, filter = s.lastFilter, sort = s.sortOrder, period = s.periodFilter) }
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

    /** The list, the mark-all-as-read set and the notice all come from this one spec, so they cannot disagree about what is shown. */
    private fun specFor(
        account: String,
        selection: Selection,
        structure: Structure,
        now: Long = periodNow.value,
        hidden: List<String> = hiddenKeywords.value.orEmpty(),
    ): ArticleQuerySpec {
        val categoryIds = (selection.scope as? ArticleScope.Category)?.let { structure.categoryIdsFor(it.categoryId) }.orEmpty()
        return ArticleQuerySpec(
            account, selection.scope, selection.filter, selection.sort, selection.search, categoryIds,
            period = selection.period, periodCutoff = selection.period.cutoff(now), hiddenKeywords = hidden,
        )
    }

    private fun restoredPeriod(): PeriodFilter? =
        savedState.get<String>(KEY_PERIOD)?.let { name -> PeriodFilter.entries.firstOrNull { it.name == name } }

    /**
     * Position key. List mode is deliberately not part of it: switching Magazine/List keeps the
     * same first visible article instead of starting a separate position.
     */
    private fun listKey(spec: ArticleQuerySpec): String =
        listOf(spec.accountId, spec.scope.encode(), spec.filter.name, spec.sort.name, spec.search, spec.period.name).joinToString("|")

    private fun updateSelection(transform: (Selection) -> Selection) {
        val updated = transform(_selection.value)
        _selection.value = updated
        savedState[KEY_SCOPE] = updated.scope.encode()
        savedState[KEY_FILTER] = updated.filter.name
        savedState[KEY_SORT] = updated.sort.name
        savedState[KEY_SEARCH] = updated.search
        savedState[KEY_PERIOD] = updated.period.name
    }

    /** Where "this source" was chosen from, so Back can return there (null when the single feed was reached another way). */
    private val _returnScope = MutableStateFlow(savedState.get<String>(KEY_RETURN_SCOPE)?.let { ArticleScope.decode(it) })
    val returnScope: StateFlow<ArticleScope?> = _returnScope.asStateFlow()

    private fun setReturnScope(scope: ArticleScope?) {
        _returnScope.value = scope
        savedState[KEY_RETURN_SCOPE] = scope?.encode()
    }

    /** "Show articles from this source" in an article menu: the one feed as a scope. Read, saved and the filter stay as they are. */
    fun showFeed(feedId: String) {
        val current = _selection.value.scope
        if (current == ArticleScope.Feed(feedId)) return
        if (current !is ArticleScope.Feed) setReturnScope(current)
        selectScope(ArticleScope.Feed(feedId))
    }

    /** Back from a single feed to the scope it was opened from. */
    fun leaveFeed() {
        selectScope(_returnScope.value ?: ArticleScope.All)
    }

    /**
     * Where to look: the one thing the drawer, the pane, the tabs, the picker and "show articles from this source" change. The read
     * filter, period, search and order stay exactly as they are (docs/product-and-ux.md S10), and "All" is only the scope that has no
     * restriction: it never resets a filter. What to show is changed by [selectFilter], [selectPeriod], [selectSort] and the search.
     */
    fun selectScope(scope: ArticleScope) {
        if (scope !is ArticleScope.Feed) setReturnScope(null)
        updateSelection { it.copy(scope = scope) }
        settingWrite { settingsRepository.setLastScope(scope) }
    }

    fun selectFilter(filter: ReadFilter) {
        updateSelection { it.copy(filter = filter) }
        settingWrite { settingsRepository.setLastFilter(filter) }
    }

    fun selectSort(order: SortOrder) {
        updateSelection { it.copy(sort = order) }
        settingWrite { settingsRepository.setSortOrder(order) }
    }

    /** A display condition only: no article is deleted, marked or fetched differently. The cutoff is renewed at once. */
    fun selectPeriod(period: PeriodFilter) {
        periodNow.value = clock.millis()
        updateSelection { it.copy(period = period) }
        settingWrite { settingsRepository.setPeriodFilter(period) }
    }

    /** The app came to the foreground: the period counts back from now again. */
    fun refreshPeriodClock() {
        periodNow.value = clock.millis()
    }

    fun setHiddenKeywordsEnabled(enabled: Boolean) {
        settingWrite { settingsRepository.setHiddenKeywordsEnabled(enabled) }
    }

    /** Returns what happened so the sheet can say why a keyword was not added. */
    suspend fun addHiddenKeyword(raw: String): HiddenKeywords.AddResult =
        runCatching { settingsRepository.addHiddenKeyword(raw) }.getOrElse { events.send(HomeEvent.Message(HomeEvent.Message.Kind.UPDATE_FAILED)); HiddenKeywords.AddResult.Blank }

    fun removeHiddenKeyword(keyword: String) {
        settingWrite { settingsRepository.removeHiddenKeyword(keyword) }
    }

    /** A setting the reader chose: when it cannot be stored the stored value stays and the reader is told (no retry). */
    private fun settingWrite(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                events.send(HomeEvent.Message(HomeEvent.Message.Kind.UPDATE_FAILED))
            }
        }
    }

    /** "Open" in the http:// dialog with the box ticked. Cancelling never calls this, so nothing is saved then. */
    fun stopAskingBeforeHttpOpen() {
        settingWrite { settingsRepository.setConfirmHttpOpen(false) }
    }

    /** "Update now" in the metered-network dialog with the box ticked. Cancelling never calls this. */
    fun stopAskingBeforeMeteredRefresh() {
        settingWrite { settingsRepository.setConfirmMeteredRefresh(false) }
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
        settingWrite { settingsRepository.setListMode(mode) }
    }

    /** The layout chosen in the display panel is written where the panel says: the category's own setting, or the global default. */
    fun setLayout(scope: ArticleScope, mode: ListMode?) {
        if (scope is ArticleScope.Category) setCategoryListLayout(scope.categoryId, mode) else if (mode != null) setListMode(mode)
    }

    private fun categoryWrite(write: suspend (account: String) -> Boolean) {
        viewModelScope.launch {
            val account = accountId.value ?: return@launch
            val ok = runCatching { write(account) }.getOrDefault(false)
            if (!ok) events.send(HomeEvent.Message(HomeEvent.Message.Kind.UPDATE_FAILED))
        }
    }

    fun setCategoryListLayout(categoryId: String, mode: ListMode?) = categoryWrite { subscriptions.setCategoryListLayout(it, categoryId, mode) }
    fun setCategoryCardStyle(categoryId: String, style: CardLayoutStyle?) = categoryWrite { subscriptions.setCategoryCardStyle(it, categoryId, style) }
    fun setCategoryCardColumns(categoryId: String, columns: Int?) = categoryWrite { subscriptions.setCategoryCardColumns(it, categoryId, columns) }
    fun resetCategoryLayout(categoryId: String) = categoryWrite { subscriptions.resetCategoryLayout(it, categoryId) }

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
        val spec = specFor(account, selection, structure.value, hidden = hiddenKeywords.value ?: return null)
        val ids = articles.unreadIdsSnapshot(spec)
        return MarkAllReadRequest(ids, scopeTitle, selection.filter, selection.search, selection.period, hiddenKeywordsActive = spec.hiddenKeywords.isNotEmpty())
    }

    fun confirmMarkAllRead(request: MarkAllReadRequest) = viewModelScope.launch {
        val changed = runCatching { articles.markRead(request.ids) }.getOrNull()
        events.send(if (changed == null) HomeEvent.Message(HomeEvent.Message.Kind.UPDATE_FAILED) else HomeEvent.BulkMarked(changed))
    }

    /** Manual refresh of the shown scope. Filter, sort and search are display conditions only (SYNC-02). */
    fun refreshCurrentScope() {
        val target = refreshTargetFor(_selection.value)
        viewModelScope.launch { refreshSafely(target) }
    }

    /** Feeds whose last request did not finish or failed; a valid Retry-After is still respected. */
    fun retryPending() {
        viewModelScope.launch { refreshSafely(RefreshScope.Pending) }
    }

    /**
     * A manual refresh whose bookkeeping failed (for example the storage is full) ends as an interrupted request in the refresh notice;
     * it must not end the process. Cancellation still ends the caller.
     */
    private suspend fun refreshSafely(target: RefreshScope, force: Boolean = false) {
        try {
            coordinator.refresh(target, RefreshReason.MANUAL, force)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
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

        /** How often a set period renews its cutoff. */
        const val PERIOD_TICK_MS = 60_000L

        /** How long the vm keeps a notice at most; the screen hides it earlier (see HomeNotices). */
        const val NOTICE_KEEP_MS = 30_000L
        private const val KEY_SCOPE = "scope"
        private const val KEY_FILTER = "filter"
        private const val KEY_SORT = "sort"
        private const val KEY_SEARCH = "search"
        private const val KEY_PERIOD = "period"
        private const val KEY_RETURN_SCOPE = "return_scope"
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
