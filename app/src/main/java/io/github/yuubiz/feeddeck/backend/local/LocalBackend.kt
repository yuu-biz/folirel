package io.github.yuubiz.feeddeck.backend.local

import io.github.yuubiz.feeddeck.backend.BackendCapabilities
import io.github.yuubiz.feeddeck.backend.BackendSynchronizer
import io.github.yuubiz.feeddeck.backend.RefreshProgress
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshReport
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.backend.local.parser.FeedParseException
import io.github.yuubiz.feeddeck.backend.local.parser.FeedParser
import io.github.yuubiz.feeddeck.backend.local.parser.XmlCharset
import io.github.yuubiz.feeddeck.data.db.FeedEntity
import io.github.yuubiz.feeddeck.data.repository.ApplyResult
import io.github.yuubiz.feeddeck.data.repository.FeedStore
import io.github.yuubiz.feeddeck.data.repository.FetchMetadata
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.network.ConditionalValidators
import io.github.yuubiz.feeddeck.network.NetworkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Polls each subscribed RSS/Atom URL directly from the device.
 * - at most [maxParallel] feeds overall and [maxPerHost] per host at a time
 * - a feed already being fetched (by any refresh) is joined, not fetched twice
 * - oldest attempt first, so a budget cut-off does not starve the same feeds every time
 * - the time budget stops waiting; unfinished feeds are reported as not attempted, never as success
 */
class LocalBackend(
    private val store: FeedStore,
    private val fetcher: FeedFetcher,
    private val clock: Clock,
    private val network: NetworkStatus,
    private val allowHttp: () -> Boolean,
    private val budgetMillis: Long = DEFAULT_BUDGET_MS,
    private val maxParallel: Int = 4,
    private val maxPerHost: Int = 2,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BackendSynchronizer {
    override val backendType = "LOCAL"
    override val capabilities = BackendCapabilities(subscriptionEditing = true, remoteStateSync = false)

    private sealed interface FeedOutcome {
        data class Updated(val newArticles: Int) : FeedOutcome
        data object NotModified : FeedOutcome
        data class Failed(val error: FetchError) : FeedOutcome
        data object SkippedInactive : FeedOutcome
    }

    private val lock = Mutex()
    private val inFlight = HashMap<String, Deferred<FeedOutcome>>()
    private val hostLimits = HashMap<String, Semaphore>()
    private val global = Semaphore(maxParallel)

    override suspend fun refresh(
        accountId: String,
        scope: RefreshScope,
        reason: RefreshReason,
        onProgress: (RefreshProgress) -> Unit,
    ): RefreshReport {
        val feeds = store.activeFeeds(accountId, scope)
        val now = clock.millis()
        val states = feeds.associate { it.id to store.fetchState(it.id) }
        var backoff = 0
        var httpPending = 0
        val eligible = ArrayList<FeedEntity>()
        for (feed in feeds) {
            val state = states.getValue(feed.id)
            val waiting = state.nextEligibleAt != null && state.nextEligibleAt > now
            // Manual refresh may retry a failed feed early, but never before a valid Retry-After.
            val rateLimited = state.lastErrorCode == FetchError.RATE_LIMITED.name
            when {
                waiting && (reason.isAutomatic || rateLimited) -> backoff++
                feed.fetchUrl.startsWith("http://") && !allowHttp() -> httpPending++
                else -> eligible += feed
            }
        }
        val base = RefreshReport(total = feeds.size, deferredBackoff = backoff, httpPending = httpPending)
        if (eligible.isEmpty()) return base
        if (!network.current().connected) {
            // Offline: defer everything at once instead of timing out feed by feed.
            return base.copy(deferredConstraint = eligible.size, offline = true)
        }
        eligible.sortWith(compareBy(nullsFirst()) { states.getValue(it.id).lastAttemptAt })

        val results = ConcurrentHashMap<String, FeedOutcome>()
        val completed = AtomicInteger()
        onProgress(RefreshProgress(0, eligible.size))
        // Budget expiry or caller cancellation cancels unfinished fetches; each feed is applied in its own
        // transaction, so finished feeds stay applied and unfinished ones are reported as not attempted.
        // Runs on a real dispatcher so the budget is wall-clock time regardless of the caller's dispatcher.
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(budgetMillis) {
                coroutineScope {
                for (feed in eligible) {
                    launch {
                        val outcome = try {
                            sharedFetch(feed, this@coroutineScope).await()
                        } catch (e: CancellationException) {
                            // A joined fetch owned by another (cancelled) refresh: not attempted here.
                            currentCoroutineContext().ensureActive()
                            null
                        }
                        if (outcome != null) results[feed.id] = outcome
                        onProgress(RefreshProgress(completed.incrementAndGet(), eligible.size))
                    }
                }
                }
            }
        }
        var report = base.copy(notAttempted = eligible.size - results.size)
        val failed = LinkedHashMap<String, FetchError>()
        for ((feedId, outcome) in results) {
            report = when (outcome) {
                is FeedOutcome.Updated -> report.copy(updated = report.updated + 1, newArticles = report.newArticles + outcome.newArticles)
                FeedOutcome.NotModified -> report.copy(notModified = report.notModified + 1)
                FeedOutcome.SkippedInactive -> report.copy(skippedInactive = report.skippedInactive + 1)
                is FeedOutcome.Failed -> {
                    failed[feedId] = outcome.error
                    report
                }
            }
        }
        return report.copy(failed = failed)
    }

    private suspend fun sharedFetch(feed: FeedEntity, owner: CoroutineScope): Deferred<FeedOutcome> = lock.withLock {
        inFlight[feed.id]?.let { return@withLock it }
        val host = feed.fetchUrl.toHttpUrlOrNull()?.host.orEmpty()
        val hostLimit = hostLimits.getOrPut(host) { Semaphore(maxPerHost) }
        val job = owner.async(Dispatchers.IO) {
            try {
                hostLimit.withPermit { global.withPermit { fetchAndApply(feed) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never let one feed's unexpected error cancel the sibling feeds.
                FeedOutcome.Failed(FetchError.STORAGE)
            } finally {
                lock.withLock { inFlight.remove(feed.id) }
            }
        }
        inFlight[feed.id] = job
        job
    }

    private suspend fun fetchAndApply(feed: FeedEntity): FeedOutcome {
        val state = store.fetchState(feed.id)
        val validators = state.validatorUrl?.takeIf { state.etag != null || state.lastModified != null }
            ?.let { ConditionalValidators(it, state.etag, state.lastModified) }
        return when (val outcome = fetcher.fetch(feed.fetchUrl, validators, state.initialized)) {
            is FetchOutcome.Success -> {
                val parsed = try {
                    withContext(parseDispatcher) {
                        val decoded = XmlCharset.decode(outcome.body, outcome.charset)
                        FeedParser.parse(decoded.text, outcome.finalUrl)
                    }
                } catch (e: FeedParseException) {
                    // Previous articles stay; validators are not updated.
                    store.recordFailure(feed.id, e.error, null)
                    return FeedOutcome.Failed(e.error)
                }
                val meta = FetchMetadata(outcome.etag, outcome.lastModified, outcome.finalUrl, outcome.permanentRedirectUrl)
                try {
                    when (val applied = store.applySuccess(feed.id, parsed, meta)) {
                        is ApplyResult.Applied -> FeedOutcome.Updated(applied.newArticles)
                        ApplyResult.SkippedInactive -> FeedOutcome.SkippedInactive
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    store.recordFailure(feed.id, FetchError.STORAGE, null)
                    FeedOutcome.Failed(FetchError.STORAGE)
                }
            }
            FetchOutcome.NotModified -> {
                store.recordNotModified(feed.id)
                FeedOutcome.NotModified
            }
            is FetchOutcome.Failure -> {
                store.recordFailure(feed.id, outcome.error, outcome.retryAfterMillis)
                FeedOutcome.Failed(outcome.error)
            }
        }
    }

    companion object {
        const val DEFAULT_BUDGET_MS = 6L * 60 * 1000
    }
}
