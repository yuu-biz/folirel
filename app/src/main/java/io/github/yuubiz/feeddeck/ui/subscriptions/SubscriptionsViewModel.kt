package io.github.yuubiz.feeddeck.ui.subscriptions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.feeddeck.app.DocumentIo
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlWriter
import io.github.yuubiz.feeddeck.data.db.FeedFetchStateEntity
import io.github.yuubiz.feeddeck.data.repository.AddFeedResult
import io.github.yuubiz.feeddeck.data.repository.SubscriptionRepository
import io.github.yuubiz.feeddeck.model.Structure
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
}

/** Fetch details for the feed detail screen (times are epoch millis UTC). */
data class FeedDetail(val state: FeedFetchStateEntity?, val fetchUrl: String?, val siteUrl: String?, val feedTitle: String?, val userTitle: String?)

class SubscriptionsViewModel(
    private val subscriptions: SubscriptionRepository,
    private val coordinator: RefreshCoordinator,
    private val documents: DocumentIo,
    private val allowHttp: suspend () -> Boolean,
    private val clock: Clock,
) : ViewModel() {
    private val accountId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val structure: StateFlow<Structure> = accountId.filterNotNull()
        .flatMapLatest { subscriptions.observeStructure(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Structure())

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

    suspend fun detail(feedId: String): FeedDetail {
        val feed = subscriptions.feed(feedId)
        return FeedDetail(subscriptions.fetchState(feedId), feed?.fetchUrl, feed?.siteUrl, feed?.feedTitle, feed?.userTitle)
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

    fun refreshFeed(feedId: String) = viewModelScope.launch { coordinator.refresh(RefreshScope.Feeds(listOf(feedId)), RefreshReason.MANUAL) }

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

    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        if (runCatching { block() }.isFailure) events.send(SubscriptionEvent.OperationFailed)
    }
}
