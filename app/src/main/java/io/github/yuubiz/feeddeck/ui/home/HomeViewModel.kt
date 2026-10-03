package io.github.yuubiz.feeddeck.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.data.preferences.AppSettings
import io.github.yuubiz.feeddeck.data.preferences.SettingsRepository
import io.github.yuubiz.feeddeck.data.repository.ArticleRepository
import io.github.yuubiz.feeddeck.data.repository.SubscriptionRepository
import io.github.yuubiz.feeddeck.model.ArticleQuerySpec
import io.github.yuubiz.feeddeck.model.ArticleRow
import io.github.yuubiz.feeddeck.model.ArticleScope
import io.github.yuubiz.feeddeck.model.ListMode
import io.github.yuubiz.feeddeck.model.ReadFilter
import io.github.yuubiz.feeddeck.model.SortOrder
import io.github.yuubiz.feeddeck.model.Structure
import io.github.yuubiz.feeddeck.network.NetworkState
import io.github.yuubiz.feeddeck.sync.RefreshCoordinator
import io.github.yuubiz.feeddeck.sync.RefreshState
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
) : ViewModel() {

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

    val network: StateFlow<NetworkState> = network.stateIn(viewModelScope, SharingStarted.Eagerly, NetworkState(true, false))

    /** Number of new articles from the latest refresh, shown as a banner until dismissed. */
    private val _newArticlesBanner = MutableStateFlow(0)
    val newArticlesBanner: StateFlow<Int> = _newArticlesBanner.asStateFlow()

    private val events = Channel<HomeEvent>(Channel.BUFFERED)
    val eventFlow: Flow<HomeEvent> = events.receiveAsFlow()

    private val _selectionReady = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val articleList: StateFlow<ArticlesState> = combine(
        accountId.filterNotNull(),
        _selection,
        structure,
        _selectionReady,
    ) { account, selection, structure, ready ->
        if (!ready || !structure.loaded) null else specFor(account, selection, structure)
    }
        .distinctUntilChanged()
        .flatMapLatest { spec ->
            if (spec == null) {
                emptyFlow()
            } else {
                val key = listKey(spec)
                articles.observe(spec).map { ArticlesState(key, it, loaded = true) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ArticlesState("", emptyList(), loaded = false))

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
            var lastSeen = coordinator.state.value.lastFinishedAt
            coordinator.state.collect { state ->
                val finished = state.lastFinishedAt
                if (finished != null && finished != lastSeen) {
                    lastSeen = finished
                    val added = state.lastReport?.newArticles ?: 0
                    if (added > 0) _newArticlesBanner.value = added
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
     * Position key. List mode is deliberately not part of it (docs/08 D13): switching Magazine/List keeps the
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

    /** Scope change keeps the current filter (docs/02 S10). */
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

    fun toggleSort() {
        val next = if (_selection.value.sort == SortOrder.NEWEST) SortOrder.OLDEST else SortOrder.NEWEST
        updateSelection { it.copy(sort = next) }
        viewModelScope.launch { settingsRepository.setSortOrder(next) }
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

    fun refreshCurrentScope() {
        val target = when (val scope = _selection.value.scope) {
            ArticleScope.All -> RefreshScope.All
            ArticleScope.Uncategorized -> RefreshScope.Uncategorized
            is ArticleScope.Category -> RefreshScope.Category(scope.categoryId)
            is ArticleScope.Feed -> RefreshScope.Feeds(listOf(scope.feedId))
        }
        viewModelScope.launch { coordinator.refresh(target, RefreshReason.MANUAL) }
    }

    fun dismissNewArticlesBanner() {
        _newArticlesBanner.update { 0 }
    }

    // Position restore (anchor article id + pixel offset), survives rotation and process death.

    fun saveAnchor(anchor: ListAnchor) {
        savedState[KEY_ANCHOR] = arrayOf(anchor.listKey, anchor.articleId, anchor.sortAt.toString(), anchor.offset.toString())
    }

    fun anchorFor(listKey: String): ListAnchor? {
        val raw = savedState.get<Array<String>>(KEY_ANCHOR) ?: return null
        if (raw.size != 4 || raw[0] != listKey) return null
        return ListAnchor(raw[0], raw[1], raw[2].toLongOrNull() ?: return null, raw[3].toIntOrNull() ?: 0)
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        private const val KEY_SCOPE = "scope"
        private const val KEY_FILTER = "filter"
        private const val KEY_SORT = "sort"
        private const val KEY_SEARCH = "search"
        private const val KEY_SEARCH_ACTIVE = "search_active"
        private const val KEY_ANCHOR = "anchor"
    }
}
