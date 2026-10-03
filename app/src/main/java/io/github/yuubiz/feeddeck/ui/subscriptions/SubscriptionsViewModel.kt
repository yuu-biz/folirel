package io.github.yuubiz.feeddeck.ui.subscriptions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.feeddeck.app.DocumentIo
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlWriter
import io.github.yuubiz.feeddeck.data.repository.AddFeedResult
import io.github.yuubiz.feeddeck.data.repository.SubscriptionRepository
import io.github.yuubiz.feeddeck.model.DiscoveryResult
import io.github.yuubiz.feeddeck.model.FeedDiagnostics
import io.github.yuubiz.feeddeck.model.FeedListFilter
import io.github.yuubiz.feeddeck.model.FeedProblem
import io.github.yuubiz.feeddeck.model.FeedFetchInfo
import io.github.yuubiz.feeddeck.model.Structure
import io.github.yuubiz.feeddeck.sync.FeedDiscoveryService
import io.github.yuubiz.feeddeck.sync.HttpsUpgradeCoordinator
import io.github.yuubiz.feeddeck.sync.HttpsUpgradeState
import io.github.yuubiz.feeddeck.sync.RefreshState
import io.github.yuubiz.feeddeck.sync.RefreshCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed interface SubscriptionEvent {
    data class FeedAdded(val result: AddFeedResult, val httpPending: Boolean) : SubscriptionEvent
    data object ExportDone : SubscriptionEvent
    data object ExportFailed : SubscriptionEvent
    data object CategoryConflict : SubscriptionEvent
    data object CategoryInvalid : SubscriptionEvent
    data object OperationFailed : SubscriptionEvent
    /** [ids] are the feeds really unsubscribed (the undo restores exactly those). */
    data class BulkUnsubscribed(val ids: List<String>, val skippedRecovered: Int) : SubscriptionEvent
    data object MoveApplied : SubscriptionEvent
    data class MoveConflict(val name: String) : SubscriptionEvent
    data object MoveStale : SubscriptionEvent
}

/** One feed of a bulk unsubscribe confirmation, as it was when it was selected. */
data class BulkItem(
    val id: String,
    val name: String,
    val problem: FeedProblem?,
    val lastSuccessAt: Long?,
    val failureCount: Int,
    val httpStatus: Int?,
)

data class BulkSnapshot(val items: List<BulkItem>)

sealed interface DiscoveryState {
    data object Idle : DiscoveryState
    data class Running(val feedId: String) : DiscoveryState
    data class Done(val feedId: String, val result: DiscoveryResult?, val enteredUrl: String?) : DiscoveryState
}


class SubscriptionsViewModel(
    private val subscriptions: SubscriptionRepository,
    private val coordinator: RefreshCoordinator,
    private val httpsUpgrade: HttpsUpgradeCoordinator,
    private val discovery: FeedDiscoveryService,
    private val documents: DocumentIo,
    private val allowHttp: suspend () -> Boolean,
    private val clock: Clock,
) : ViewModel() {
    private val accountId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val structure: StateFlow<Structure> = accountId.filterNotNull()
        .flatMapLatest { subscriptions.observeStructure(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Structure())

    /** Fetch diagnostics per feed id (DATA-01). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val diagnostics: StateFlow<Map<String, FeedDiagnostics>> = accountId.filterNotNull()
        .flatMapLatest { subscriptions.observeDiagnostics(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val refreshState: StateFlow<RefreshState> = coordinator.state

    val httpsState: StateFlow<HttpsUpgradeState> = httpsUpgrade.state

    fun feedInfo(feedId: String): Flow<FeedFetchInfo?> = subscriptions.observeFetchInfo(feedId)

    fun feedDiagnostics(feedId: String): Flow<FeedDiagnostics?> = subscriptions.observeFeedDiagnostics(feedId)

    suspend fun httpCandidateCount(): Int = httpsUpgrade.candidateCount()

    /** The user agreed: check all http:// feeds over HTTPS and switch the verified ones. */
    fun startHttpsUpgrade() {
        httpsUpgrade.start()
    }

    fun cancelHttpsUpgrade() = httpsUpgrade.cancel()

    fun dismissHttpsUpgrade() = httpsUpgrade.dismiss()

    /** Feeds whose last request did not finish or failed. A valid Retry-After is still respected. */
    fun retryPending() = viewModelScope.launch { coordinator.refresh(RefreshScope.Pending, RefreshReason.MANUAL) }

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val events = Channel<SubscriptionEvent>(Channel.BUFFERED)
    val eventFlow: Flow<SubscriptionEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch { accountId.value = subscriptions.ensureLocalAccount() }
    }

    fun setSearch(text: String) {
        _search.value = text
    }


    fun addFeed(url: String, onDone: (AddFeedResult) -> Unit = {}) = viewModelScope.launch {
        val account = subscriptions.ensureLocalAccount()
        val result = runCatching { subscriptions.addFeed(account, url) }.getOrNull()
        if (result == null) {
            events.send(SubscriptionEvent.OperationFailed)
            return@launch
        }
        val feedId = when (result) {
            is AddFeedResult.Added -> result.feedId
            is AddFeedResult.Resubscribed -> result.feedId
            else -> null
        }
        val httpPending = feedId != null && url.trim().startsWith("http://", ignoreCase = true) && !allowHttp()
        events.send(SubscriptionEvent.FeedAdded(result, httpPending))
        onDone(result)
        if (feedId != null) coordinator.refresh(RefreshScope.Feeds(listOf(feedId)), RefreshReason.MANUAL)
    }

    fun unsubscribe(feedId: String) = guarded { subscriptions.unsubscribe(feedId) }

    fun resubscribe(feedId: String) = guarded {
        subscriptions.resubscribe(feedId)
        coordinator.refresh(RefreshScope.Feeds(listOf(feedId)), RefreshReason.MANUAL)
    }

    fun setPaused(feedId: String, paused: Boolean) = guarded { subscriptions.setPaused(feedId, paused) }

    fun rename(feedId: String, title: String) = guarded { subscriptions.renameFeed(feedId, title) }

    fun setCategories(feedId: String, categoryIds: Set<String>) = guarded { subscriptions.setFeedCategories(feedId, categoryIds) }

    /**
     * This feed only, whatever its articles' dates. [force]: revalidate without stored validators
     * ("refetch bypassing the cache"); articles, read and saved state are kept.
     */
    fun refreshFeed(feedId: String, force: Boolean = false) =
        viewModelScope.launch { coordinator.refresh(RefreshScope.Feeds(listOf(feedId)), RefreshReason.MANUAL, force) }

    fun createCategory(parentId: String?, name: String) = viewModelScope.launch {
        val account = subscriptions.ensureLocalAccount()
        when (subscriptions.createCategory(account, parentId, name)) {
            is SubscriptionRepository.CategoryResult.Ok -> Unit
            SubscriptionRepository.CategoryResult.Conflict -> events.send(SubscriptionEvent.CategoryConflict)
            SubscriptionRepository.CategoryResult.InvalidName -> events.send(SubscriptionEvent.CategoryInvalid)
        }
    }

    fun renameCategory(categoryId: String, name: String) = viewModelScope.launch {
        when (subscriptions.renameCategory(categoryId, name)) {
            is SubscriptionRepository.CategoryResult.Ok -> Unit
            SubscriptionRepository.CategoryResult.Conflict -> events.send(SubscriptionEvent.CategoryConflict)
            SubscriptionRepository.CategoryResult.InvalidName -> events.send(SubscriptionEvent.CategoryInvalid)
        }
    }

    fun deleteCategory(categoryId: String) = guarded { subscriptions.deleteCategory(categoryId) }

    /** Writes subscribed feeds and categories only (no reading state). Reports failure explicitly. */
    fun export(uri: Uri) = viewModelScope.launch {
        val ok = runCatching {
            val (feeds, paths) = subscriptions.exportData(subscriptions.ensureLocalAccount())
            val exportFeeds = feeds.map { f ->
                OpmlWriter.ExportFeed(Structure.displayName(f), f.subscriptionUrl, f.siteUrl, paths[f.id].orEmpty())
            }
            val created = DateTimeFormatter.RFC_1123_DATE_TIME.format(clock.instant().atOffset(ZoneOffset.UTC))
            documents.write(uri, OpmlWriter.write("FeedDeck subscriptions", exportFeeds, created))
        }.isSuccess
        events.send(if (ok) SubscriptionEvent.ExportDone else SubscriptionEvent.ExportFailed)
    }

    // Selection and filter of the management list (SUB-BULK)

    private val _filter = MutableStateFlow(FeedListFilter.ALL)
    val filter: StateFlow<FeedListFilter> = _filter.asStateFlow()

    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    fun setFilter(filter: FeedListFilter) {
        _filter.value = filter
    }

    fun startSelecting(first: String? = null) {
        _selecting.value = true
        if (first != null) _selected.value = _selected.value + first
    }

    fun stopSelecting() {
        _selecting.value = false
        _selected.value = emptySet()
    }

    fun toggleSelected(feedId: String) {
        _selected.update { if (feedId in it) it - feedId else it + feedId }
    }

    /** "Select shown": exactly the ids the list shows now (after filter and search), added to the selection. */
    fun selectAll(ids: Collection<String>) {
        _selecting.value = true
        _selected.update { it + ids }
    }

    fun clearSelected() {
        _selected.value = emptySet()
    }

    /**
     * The feeds to unsubscribe, frozen when the confirmation opens: later list changes (a refresh, a filter, a
     * search) never change which feeds the dialog is about (SUB-BULK).
     */
    fun snapshotOf(ids: Collection<String>): BulkSnapshot {
        val s = structure.value
        val d = diagnostics.value
        val items = ids.mapNotNull { id ->
            val node = s.feeds[id] ?: return@mapNotNull null
            if (!node.subscribed) return@mapNotNull null
            BulkItem(id, node.name, node.problem, node.lastSuccessAt, node.failureCount, d[id]?.httpStatus)
        }.sortedBy { it.name.lowercase() }
        return BulkSnapshot(items)
    }

    /**
     * Unsubscribes the snapshot (soft: articles, saved and read state, categories stay). A feed that had a problem
     * when it was selected but works again now is left alone and reported, so nothing that recovered is dropped.
     */
    fun confirmBulkUnsubscribe(snapshot: BulkSnapshot) = viewModelScope.launch {
        val current = structure.value
        val (recovered, targets) = snapshot.items.partition { it.problem != null && current.feeds[it.id]?.problem == null }
        val changed = runCatching { subscriptions.unsubscribeAll(targets.map { it.id }) }.getOrNull()
        if (changed == null) {
            events.send(SubscriptionEvent.OperationFailed)
            return@launch
        }
        stopSelecting()
        events.send(SubscriptionEvent.BulkUnsubscribed(changed, recovered.size))
    }

    /** Undo of a bulk unsubscribe: exactly the feeds it changed, fetched again right away. */
    fun undoBulkUnsubscribe(ids: List<String>) = viewModelScope.launch {
        val changed = runCatching { subscriptions.resubscribeAll(ids) }.getOrNull()
        if (changed == null) {
            events.send(SubscriptionEvent.OperationFailed)
            return@launch
        }
        if (changed.isNotEmpty()) coordinator.refresh(RefreshScope.Feeds(changed), RefreshReason.MANUAL)
    }

    // Finding where a feed moved (SUB-DISCOVERY)

    private val _discovery = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discovery.asStateFlow()
    private var discoveryJob: Job? = null

    /** Reads the feed site (or the address the user entered) for feed links. Changes nothing. */
    fun startDiscovery(feedId: String, enteredUrl: String? = null) {
        discoveryJob?.cancel()
        _discovery.value = DiscoveryState.Running(feedId)
        discoveryJob = viewModelScope.launch {
            val result = runCatching { discovery.run(feedId, enteredUrl) }.getOrNull()
            _discovery.value = DiscoveryState.Done(feedId, result, enteredUrl)
        }
    }

    fun clearDiscovery() {
        discoveryJob?.cancel()
        _discovery.value = DiscoveryState.Idle
    }

    suspend fun searchName(feedId: String): String? = discovery.searchName(feedId)

    /** The user approved this candidate for [feedId]. The refresh that follows fetches the new address. */
    fun applyMove(feedId: String, fromUrl: String, toUrl: String) = viewModelScope.launch {
        when (val result = runCatching { subscriptions.applyUrlMove(feedId, fromUrl, toUrl) }.getOrNull()) {
            SubscriptionRepository.MoveResult.Applied -> {
                clearDiscovery()
                events.send(SubscriptionEvent.MoveApplied)
                coordinator.refresh(RefreshScope.Feeds(listOf(feedId)), RefreshReason.MANUAL)
            }
            is SubscriptionRepository.MoveResult.Conflict -> events.send(SubscriptionEvent.MoveConflict(result.name))
            SubscriptionRepository.MoveResult.Stale, SubscriptionRepository.MoveResult.Invalid -> events.send(SubscriptionEvent.MoveStale)
            null -> events.send(SubscriptionEvent.OperationFailed)
        }
    }

    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        if (runCatching { block() }.isFailure) events.send(SubscriptionEvent.OperationFailed)
    }
}
