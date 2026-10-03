package io.github.yuubiz.feeddeck.backend.local

import io.github.yuubiz.feeddeck.backend.BackendCapabilities
import io.github.yuubiz.feeddeck.backend.BackendSynchronizer
import io.github.yuubiz.feeddeck.backend.FeedRefreshResult
import io.github.yuubiz.feeddeck.backend.FeedResult
import io.github.yuubiz.feeddeck.backend.RefreshProgress
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshReport
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.backend.local.parser.FeedParseException
import io.github.yuubiz.feeddeck.backend.local.parser.FeedParser
import io.github.yuubiz.feeddeck.backend.local.parser.XmlCharset
import io.github.yuubiz.feeddeck.data.db.FeedEntity
import io.github.yuubiz.feeddeck.data.repository.ApplyResult
import io.github.yuubiz.feeddeck.data.repository.AttemptInfo
import io.github.yuubiz.feeddeck.data.repository.BodyStats
import io.github.yuubiz.feeddeck.data.repository.FeedStore
import io.github.yuubiz.feeddeck.data.repository.FetchMetadata
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.network.ConditionalValidators
import io.github.yuubiz.feeddeck.network.NetworkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Polls each subscribed RSS/Atom URL directly from the device.
 *
 * Every request (All, a category, one feed, ...) submits its target feeds to one shared per-feed job registry:
 * - a feed has at most one queued/running fetch; a request that asks for a feed already queued or running joins
 *   it (a forced revalidation never joins a running conditional fetch: it gets exactly one follow-up fetch)
 * - queued jobs start by priority (targeted manual > manual All/import > automatic), then oldest attempt first,
 *   at most [maxParallel] overall and [maxPerHost] per host; a narrow manual request therefore runs at the next
 *   free slot instead of behind every other feed of a running All
 * - each request waits for its own feeds within its own time budget; when it stops waiting (budget, caller
 *   cancelled, worker stopped) it releases its feeds, and a job is cancelled only when no request still wants it
 * - the result of a request covers only its own feeds; finished fetches stay applied (one transaction each)
 *
 * Fetches run in this backend's own scope, so their lifetime is decided by ownership, not by whichever request
 * happened to create them.
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
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) : BackendSynchronizer {
    override val backendType = "LOCAL"
    override val capabilities = BackendCapabilities(subscriptionEditing = true, remoteStateSync = false)

    private sealed interface FeedOutcome {
        /** [firstFetch]: the feed had never been fetched; its articles are the initial content, not "new arrivals". */
        data class Updated(val newArticles: Int, val updatedArticles: Int, val insertedIds: List<String>, val firstFetch: Boolean) : FeedOutcome
        data object NotModified : FeedOutcome
        data class Failed(val error: FetchError) : FeedOutcome
        data object SkippedInactive : FeedOutcome
        /** Every requester stopped waiting before it started. */
        data object NotStarted : FeedOutcome
        /** Every requester stopped waiting while it was running. Nothing applied. */
        data object Cancelled : FeedOutcome
        /** The fetch URL of the feed changed while this fetch ran; its answer belongs to the old address and was dropped. */
        data object Superseded : FeedOutcome
    }

    /** One planned fetch of one feed, shared by all requests that want it while it is queued or running. */
    private class FeedJob(
        val feed: FeedEntity,
        val host: String,
        var priority: Int,
        var force: Boolean,
        /** lastAttemptAt of the feed (never attempted first). */
        val sortKey: Long,
        val seq: Long,
        /** For a forced follow-up: the running conditional job it follows. */
        val parent: FeedJob?,
    ) {
        val owners = HashSet<Any>()
        val result = CompletableDeferred<FeedOutcome>()
        var started = false
        var running: Job? = null
        var followUp: FeedJob? = null

        /** The URL this job really fetches, once it has re-read the feed (a redirect may have changed it since planning). */
        @Volatile var fetchingUrl: String? = null

        /** A newer job for another address of the same feed took over; this one only finishes (or is cancelled) and applies nothing. */
        @Volatile var superseded = false

        val url: String get() = fetchingUrl ?: feed.fetchUrl
    }

    private val lock = Any()
    /** feedId → its queued or running job (forced follow-ups hang off their parent until it finishes). */
    private val current = HashMap<String, FeedJob>()
    private val runningPerHost = HashMap<String, Int>()
    private var runningTotal = 0
    private var nextSeq = 0L
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun refresh(
        accountId: String,
        scope: RefreshScope,
        reason: RefreshReason,
        force: Boolean,
        onProgress: (RefreshProgress) -> Unit,
    ): RefreshReport {
        val requestId = newRequestId()
        val scopeText = scope.describe()
        val feeds = store.activeFeeds(accountId, scope)
        val now = clock.millis()
        val states = feeds.associate { it.id to store.fetchState(it.id) }
        val backoff = ArrayList<String>()
        val httpOff = ArrayList<String>()
        val eligible = ArrayList<FeedEntity>()
        for (feed in feeds) {
            val state = states.getValue(feed.id)
            val waiting = state.nextEligibleAt != null && state.nextEligibleAt > now
            // A valid server Retry-After binds every request, manual and forced included. Ordinary failure
            // backoff only binds automatic refreshes: a manual request may retry early.
            val serverWait = (state.retryAfterUntil ?: 0) > now || (waiting && state.lastErrorCode == FetchError.RATE_LIMITED.name)
            when {
                serverWait || (waiting && reason.isAutomatic) -> backoff += feed.id
                feed.fetchUrl.startsWith("http://") && !allowHttp() -> httpOff += feed.id
                else -> eligible += feed
            }
        }
        store.markSkipped(backoff, FeedResult.BACKOFF, requestId, reason.name, scopeText, force)
        store.markSkipped(httpOff, FeedResult.HTTP_DISABLED, requestId, reason.name, scopeText, force)
        val perFeed = LinkedHashMap<String, FeedRefreshResult>()
        backoff.forEach { perFeed[it] = FeedRefreshResult(FeedResult.BACKOFF) }
        httpOff.forEach { perFeed[it] = FeedRefreshResult(FeedResult.HTTP_DISABLED) }
        val base = RefreshReport(
            total = feeds.size, deferredBackoff = backoff.size, httpPending = httpOff.size,
            requestId = requestId, force = force, perFeed = perFeed,
        )
        if (eligible.isEmpty()) return base
        if (!network.current().connected) {
            // Offline: defer everything at once instead of timing out feed by feed.
            store.markSkipped(eligible.map { it.id }, FeedResult.OFFLINE, requestId, reason.name, scopeText, force)
            eligible.forEach { perFeed[it.id] = FeedRefreshResult(FeedResult.OFFLINE) }
            return base.copy(deferredConstraint = eligible.size, offline = true, perFeed = perFeed)
        }
        // Never attempted / oldest attempt first, so feeds an earlier run did not reach go before the ones it did.
        eligible.sortWith(compareBy(nullsFirst()) { states.getValue(it.id).lastAttemptAt })
        store.markRequested(eligible.map { it.id }, requestId, reason.name, scopeText, force)

        val owner = Any()
        val priority = priorityOf(scope, reason, force)
        val jobs = LinkedHashMap<String, FeedJob>()
        synchronized(lock) {
            for (feed in eligible) {
                jobs[feed.id] = submitLocked(feed, owner, priority, force, states.getValue(feed.id).lastAttemptAt ?: Long.MIN_VALUE)
            }
        }
        pump()

        val outcomes = ConcurrentHashMap<String, FeedOutcome>()
        var unfinished: Map<String, FeedResult> = emptyMap()
        val completed = AtomicInteger()
        onProgress(RefreshProgress(0, jobs.size))
        try {
            // Wall-clock budget regardless of the caller's dispatcher.
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(budgetMillis) {
                    coroutineScope {
                        for ((feedId, job) in jobs) {
                            launch {
                                outcomes[feedId] = job.result.await()
                                onProgress(RefreshProgress(completed.incrementAndGet(), jobs.size))
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                unfinished = release(owner, jobs, outcomes)
                unfinished.entries.groupBy({ it.value }, { it.key }).forEach { (result, ids) -> store.markUnfinished(ids, result) }
            }
        }

        val failed = LinkedHashMap<String, FetchError>()
        var updated = 0
        var notModified = 0
        var inactive = 0
        var replaced = 0
        var newArticles = 0
        val newIds = ArrayList<String>()
        for (feedId in jobs.keys) {
            perFeed[feedId] = when (val outcome = outcomes[feedId]) {
                is FeedOutcome.Updated -> {
                    updated++
                    newArticles += outcome.newArticles
                    if (!outcome.firstFetch) newIds += outcome.insertedIds
                    FeedRefreshResult(FeedResult.UPDATED, newArticles = outcome.newArticles, updatedArticles = outcome.updatedArticles)
                }
                FeedOutcome.NotModified -> FeedRefreshResult(FeedResult.NOT_MODIFIED).also { notModified++ }
                is FeedOutcome.Failed -> FeedRefreshResult(FeedResult.FAILED, outcome.error).also { failed[feedId] = outcome.error }
                FeedOutcome.SkippedInactive -> FeedRefreshResult(FeedResult.INACTIVE).also { inactive++ }
                FeedOutcome.NotStarted -> FeedRefreshResult(FeedResult.NOT_STARTED)
                FeedOutcome.Cancelled -> FeedRefreshResult(FeedResult.CANCELLED)
                // The address changed: this request did not fetch the feed; the request that follows the change fetches the new address.
                FeedOutcome.Superseded -> FeedRefreshResult(FeedResult.REPLACED).also { replaced++ }
                null -> FeedRefreshResult(unfinished[feedId] ?: FeedResult.NOT_STARTED)
            }
        }
        val results = perFeed.values
        return base.copy(
            updated = updated,
            notModified = notModified,
            failed = failed,
            skippedInactive = inactive,
            replaced = replaced,
            notStarted = results.count { it.result == FeedResult.NOT_STARTED },
            cancelled = results.count { it.result == FeedResult.CANCELLED },
            continuing = results.count { it.result == FeedResult.CONTINUING },
            newArticles = newArticles,
            perFeed = perFeed,
            newArticleIds = newIds,
        )
    }

    private fun priorityOf(scope: RefreshScope, reason: RefreshReason, force: Boolean): Int = when {
        force -> PRIORITY_TARGETED
        reason == RefreshReason.MANUAL && scope != RefreshScope.All -> PRIORITY_TARGETED
        !reason.isAutomatic -> PRIORITY_BULK
        else -> PRIORITY_AUTOMATIC
    }

    private fun submitLocked(feed: FeedEntity, owner: Any, priority: Int, force: Boolean, sortKey: Long): FeedJob {
        val existing = current[feed.id]
        val job = when {
            existing == null -> newJob(feed, priority, force, sortKey, null).also { current[feed.id] = it }
            // The address changed (adopted move, HTTPS migration) while the old address is being fetched: the new
            // address needs its own fetch. Joining would end the request with the old address's answer.
            existing.started && existing.url != feed.fetchUrl -> {
                existing.superseded = true
                existing.followUp?.let { it.superseded = true; it.result.complete(FeedOutcome.Superseded) }
                existing.followUp = null
                existing.running?.cancel()
                newJob(feed, priority, force, sortKey, null).also { current[feed.id] = it }
            }
            !force || existing.force -> existing
            // A queued conditional fetch simply becomes the forced one.
            !existing.started -> existing.also { it.force = true }
            // Running conditional fetch: one forced fetch follows it; repeated force requests join that follow-up.
            else -> existing.followUp ?: newJob(feed, priority, true, sortKey, existing).also { existing.followUp = it }
        }
        job.owners += owner
        job.priority = maxOf(job.priority, priority)
        return job
    }

    private fun newJob(feed: FeedEntity, priority: Int, force: Boolean, sortKey: Long, parent: FeedJob?) =
        FeedJob(feed, feed.fetchUrl.toHttpUrlOrNull()?.host.orEmpty(), priority, force, sortKey, nextSeq++, parent)

    /** Starts queued jobs while slots are free: highest priority, then oldest attempt, then submission order. */
    private fun pump() {
        synchronized(lock) {
            while (runningTotal < maxParallel) {
                val next = current.values.asSequence()
                    .filter { !it.started && (runningPerHost[it.host] ?: 0) < maxPerHost }
                    .minWithOrNull(QUEUE_ORDER) ?: break
                startLocked(next)
            }
        }
    }

    private fun startLocked(job: FeedJob) {
        job.started = true
        runningTotal++
        runningPerHost[job.host] = (runningPerHost[job.host] ?: 0) + 1
        // ATOMIC: the body always runs (and so always reaches finish()), even if cancelled before dispatch.
        job.running = workScope.launch(start = CoroutineStart.ATOMIC) {
            val outcome = try {
                fetchAndApply(job)
            } catch (_: CancellationException) {
                FeedOutcome.Cancelled
            } catch (_: Exception) {
                // Never let one feed's unexpected error affect other feeds.
                FeedOutcome.Failed(FetchError.STORAGE)
            }
            // A job a newer one replaced never reports its own cancellation or answer as a result.
            finish(job, if (job.superseded && outcome == FeedOutcome.Cancelled) FeedOutcome.Superseded else outcome)
        }
    }

    private fun finish(job: FeedJob, outcome: FeedOutcome) {
        synchronized(lock) {
            runningTotal--
            runningPerHost[job.host] = (runningPerHost[job.host] ?: 1) - 1
            if (current[job.feed.id] === job) {
                current.remove(job.feed.id)
                promoteFollowUpLocked(job)
            }
        }
        job.result.complete(outcome)
        pump()
    }

    private fun promoteFollowUpLocked(job: FeedJob) {
        val followUp = job.followUp ?: return
        job.followUp = null
        if (followUp.owners.isNotEmpty()) current[job.feed.id] = followUp
    }

    /**
     * The request stops waiting. For each of its feeds without a result: still wanted by another request →
     * CONTINUING; otherwise a queued job is dropped (NOT_STARTED) and a running one cancelled (CANCELLED).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun release(owner: Any, jobs: Map<String, FeedJob>, outcomes: MutableMap<String, FeedOutcome>): Map<String, FeedResult> {
        val unfinished = HashMap<String, FeedResult>()
        val toCancel = ArrayList<Job>()
        synchronized(lock) {
            for ((feedId, job) in jobs) {
                if (outcomes.containsKey(feedId)) continue
                if (job.result.isCompleted) {
                    outcomes[feedId] = job.result.getCompleted()
                    continue
                }
                job.owners.remove(owner)
                unfinished[feedId] = when {
                    job.owners.isNotEmpty() -> FeedResult.CONTINUING
                    !job.started -> {
                        if (current[feedId] === job) current.remove(feedId) else job.parent?.let { if (it.followUp === job) it.followUp = null }
                        job.result.complete(FeedOutcome.NotStarted)
                        FeedResult.NOT_STARTED
                    }
                    else -> {
                        // Detach now so a later request starts a fresh fetch instead of joining a cancelled one.
                        if (current[feedId] === job) {
                            current.remove(feedId)
                            promoteFollowUpLocked(job)
                        }
                        job.running?.let(toCancel::add)
                        FeedResult.CANCELLED
                    }
                }
            }
        }
        toCancel.forEach { it.cancel() }
        pump()
        return unfinished
    }

    private suspend fun fetchAndApply(job: FeedJob): FeedOutcome {
        val planned = job.feed
        val force = job.force
        store.attemptStarted(planned.id)
        // Re-read: a permanent redirect or an approved move may have changed the fetch URL since planning.
        val feed = store.feed(planned.id) ?: planned
        // Everything this fetch writes is tied to the URL it started with (see FeedStore.applySuccess).
        val startedFrom = feed.fetchUrl
        job.fetchingUrl = startedFrom
        val state = store.fetchState(feed.id)
        val validators = state.validatorUrl?.takeIf { state.etag != null || state.lastModified != null }
            ?.let { ConditionalValidators(it, state.etag, state.lastModified) }
        return when (val outcome = fetcher.fetch(startedFrom, validators, state.initialized, revalidate = force)) {
            is FetchOutcome.Success -> {
                val attempt = AttemptInfo(outcome.httpStatus, outcome.validatorsSent)
                val parsed = try {
                    withContext(parseDispatcher) {
                        val decoded = XmlCharset.decode(outcome.body, outcome.charset)
                        FeedParser.parse(decoded.text, outcome.finalUrl)
                    }
                } catch (e: FeedParseException) {
                    // Previous articles stay; validators are not updated.
                    if (!store.recordFailure(feed.id, e.error, null, attempt.copy(errorDetail = e.detail), startedFrom)) return FeedOutcome.Superseded
                    return FeedOutcome.Failed(e.error)
                }
                val meta = FetchMetadata(outcome.etag, outcome.lastModified, outcome.finalUrl, outcome.permanentRedirectUrl)
                try {
                    when (val applied = store.applySuccess(feed.id, parsed, meta, BodyStats(outcome.body.size.toLong(), parsed.rawEntryCount), attempt, startedFrom)) {
                        is ApplyResult.Applied -> FeedOutcome.Updated(applied.newArticles, applied.updatedArticles, applied.insertedIds, !state.initialized)
                        ApplyResult.SkippedInactive -> FeedOutcome.SkippedInactive.also { store.recordInactive(feed.id) }
                        ApplyResult.Superseded -> FeedOutcome.Superseded
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (store.recordFailure(feed.id, FetchError.STORAGE, null, attempt, startedFrom)) FeedOutcome.Failed(FetchError.STORAGE) else FeedOutcome.Superseded
                }
            }
            FetchOutcome.NotModified -> {
                if (store.recordNotModified(feed.id, startedFrom)) FeedOutcome.NotModified else FeedOutcome.Superseded
            }
            is FetchOutcome.Failure -> {
                if (store.recordFailure(feed.id, outcome.error, outcome.retryAfterMillis, AttemptInfo(outcome.httpStatus, false, outcome.detail), startedFrom)) FeedOutcome.Failed(outcome.error) else FeedOutcome.Superseded
            }
        }
    }

    companion object {
        const val DEFAULT_BUDGET_MS = 6L * 60 * 1000
        private const val PRIORITY_AUTOMATIC = 0
        private const val PRIORITY_BULK = 1
        private const val PRIORITY_TARGETED = 2
        private val QUEUE_ORDER = compareByDescending<FeedJob> { it.priority }.thenBy { it.sortKey }.thenBy { it.seq }
    }
}
