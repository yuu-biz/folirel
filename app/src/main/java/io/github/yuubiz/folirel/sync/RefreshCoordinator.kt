package io.github.yuubiz.folirel.sync

import io.github.yuubiz.folirel.backend.BackendSynchronizer
import io.github.yuubiz.folirel.backend.FeedResult
import io.github.yuubiz.folirel.backend.RefreshProgress
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshReport
import io.github.yuubiz.folirel.backend.RefreshScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/** One refresh request as the UI sees it: its own target, progress and result. */
data class RequestStatus(
    val id: Long,
    val scope: RefreshScope,
    val reason: RefreshReason,
    val force: Boolean,
    val startedAt: Long,
    val progress: RefreshProgress? = null,
    val report: RefreshReport? = null,
    val finishedAt: Long? = null,
) {
    val running: Boolean get() = finishedAt == null
}

data class RefreshState(
    /** Running requests and the most recent finished ones (bounded), oldest first. */
    val requests: List<RequestStatus> = emptyList(),
    /** True when the last automatic refresh was skipped by the network constraint. */
    val lastAutoBlocked: Boolean = false,
) {
    val running: Boolean get() = requests.any { it.running }

    fun runningFor(scope: RefreshScope): RequestStatus? = requests.lastOrNull { it.running && it.scope == scope }

    /** The newest request (running or finished) whose target is exactly [scope]. */
    fun latestFor(scope: RefreshScope): RequestStatus? = requests.lastOrNull { it.scope == scope }

    val latestFinished: RequestStatus? get() = requests.filter { !it.running }.maxByOrNull { it.finishedAt ?: 0 }

    /**
     * What became of the feeds [request] did not fetch because their address changed meanwhile (V31-C02). The request
     * itself stays history: it did not leave them unfinished. Their present state is what the requests made after it say:
     * the latest decisive result of each feed decides (failed / not reached / fine), a running request for that feed
     * means it is being fetched now, and when no later request covers a feed it has not been fetched since the change.
     * Only feeds this request replaced are considered, so a failure of any other feed is never reported here.
     */
    fun replacedNow(request: RequestStatus): ReplacedNow {
        val replaced = request.report?.perFeed.orEmpty().filterValues { it.result == FeedResult.REPLACED }.keys
        val later = requests.filter { it.id > request.id }
        var failed = 0
        var unreached = 0
        var inProgress = 0
        for (feedId in replaced) {
            if (later.any { it.running && (it.scope as? RefreshScope.Feeds)?.feedIds?.contains(feedId) == true }) {
                inProgress++
                continue
            }
            val result = later.asReversed().firstNotNullOfOrNull { r ->
                r.takeIf { !it.running }?.report?.perFeed?.get(feedId)?.result?.takeIf { it != FeedResult.REPLACED && it != FeedResult.INACTIVE }
            }
            when {
                result == FeedResult.FAILED -> failed++
                result == null || result.unfinished || result == FeedResult.BACKOFF || result == FeedResult.OFFLINE || result == FeedResult.HTTP_DISABLED -> unreached++
            }
        }
        return ReplacedNow(failed, unreached, inProgress)
    }
}

/** The present state of the feeds a request did not fetch because their address changed meanwhile. */
data class ReplacedNow(val failed: Int = 0, val unreached: Int = 0, val inProgress: Int = 0)

/**
 * Single entry point for every refresh (manual, import, startup, periodic).
 * - Each request acts on its own target: a running "All" no longer absorbs a narrower request. Overlap is
 *   resolved per feed in the backend (a feed queued/running for one request is shared, never fetched twice).
 * - Only an identical request (same target, same manual/automatic kind, same force, made with the same feed addresses:
 *   [feedAddressChanged] separates requests made before and after an address change) joins a running one.
 * - Manual, import and startup requests run in the application [scope], so leaving a screen does not cancel them.
 *   Periodic requests run in the calling worker's coroutine: stopping the worker releases its feeds, and fetches
 *   that only it wanted are cancelled (finished feeds stay applied).
 */
class RefreshCoordinator(
    private val backend: BackendSynchronizer,
    private val accountId: suspend () -> String,
    /** For automatic reasons only: whether settings and the current network allow this automatic refresh. */
    private val automaticAllowed: suspend (RefreshReason) -> Boolean,
    /** Called when a refresh request is accepted (manual or automatic); the startup 30-minute rule uses it. */
    private val onAttempt: suspend (Long) -> Unit,
    private val scope: CoroutineScope,
    private val clock: Clock,
    /** After every finished request (e.g. the retention cleanup). Failures there never fail the refresh. */
    private val afterRefresh: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow(RefreshState())
    val state: StateFlow<RefreshState> = _state.asStateFlow()

    /** [addresses]: the count of address changes seen when the request was made; requests of different counts never share. */
    private data class Key(val scope: RefreshScope, val automatic: Boolean, val force: Boolean, val addresses: Long)

    private val mutex = Mutex()
    private val joinable = HashMap<Key, Deferred<RefreshReport>>()
    private val ids = AtomicLong()
    private val addressChanges = AtomicLong()

    /** Paused for a restore: no request starts, and the backend calls that were running were cancelled. */
    private val pauseLock = Any()
    private var paused = false
    private val runningWork = HashSet<Job>()

    /**
     * Stops refreshing for a full restore: requests made from now on return at once without doing anything, and running ones are
     * cancelled (their finished feeds stay applied). [state] shows when they have ended. [resume] lets requests run again; nothing is
     * started by it.
     */
    fun pause() {
        val toCancel = synchronized(pauseLock) {
            paused = true
            runningWork.toList()
        }
        toCancel.forEach { it.cancel() }
    }

    fun resume() {
        synchronized(pauseLock) { paused = false }
    }

    private fun isPaused(): Boolean = synchronized(pauseLock) { paused }

    suspend fun refresh(target: RefreshScope, reason: RefreshReason, force: Boolean = false): RefreshReport {
        if (isPaused()) return RefreshReport(total = 0)
        if (reason.isAutomatic && !automaticAllowed(reason)) {
            _state.update { it.copy(lastAutoBlocked = true) }
            return RefreshReport(total = 0, deferredConstraint = 1)
        }
        onAttempt(clock.millis())
        if (reason == RefreshReason.PERIODIC) return run(target, reason, force)
        val key = Key(target, reason.isAutomatic, force, addressChanges.get())
        val job = mutex.withLock {
            joinable[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    run(target, reason, force)
                } finally {
                    withContext(NonCancellable) { mutex.withLock { joinable.remove(key) } }
                }
            }.also { joinable[key] = it }
        }
        job.start() // outside the lock; no-op when joining an already running job
        return job.await()
    }

    /** An address of a feed changed: requests made from now on are not merged into requests made before. */
    fun feedAddressChanged() {
        addressChanges.incrementAndGet()
    }

    private suspend fun run(target: RefreshScope, reason: RefreshReason, force: Boolean): RefreshReport {
        val id = ids.incrementAndGet()
        _state.update {
            it.copy(
                requests = it.requests + RequestStatus(id, target, reason, force, clock.millis()),
                lastAutoBlocked = if (reason.isAutomatic) false else it.lastAutoBlocked,
            )
        }
        var report: RefreshReport? = null
        try {
            report = coroutineScope {
                // Registered under the pause lock, so that a pause either sees this work and cancels it or this work sees the pause.
                val work = synchronized(pauseLock) {
                    if (paused) {
                        null
                    } else {
                        async(start = CoroutineStart.LAZY) {
                            backend.refresh(accountId(), target, reason, force) { p -> updateRequest(id) { r -> r.copy(progress = p) } }
                        }.also { runningWork += it }
                    }
                }
                if (work == null) {
                    RefreshReport(total = 0)
                } else {
                    try {
                        work.start()
                        work.await()
                    } catch (e: CancellationException) {
                        // Cancelled by a pause, not by our caller: this request simply ends.
                        if (work.isCancelled && currentCoroutineContext().isActive) RefreshReport(total = 0) else throw e
                    } finally {
                        synchronized(pauseLock) { runningWork -= work }
                    }
                }
            }
            // The retention cleanup writes the database; not while a restore replaces it.
            if (!isPaused()) runCatching { afterRefresh() }
            return report
        } finally {
            val finished = report
            val now = clock.millis()
            _state.update { state ->
                val updated = state.requests.map { if (it.id == id) it.copy(report = finished, finishedAt = now) else it }
                val keepFinished = updated.filter { !it.running }.sortedBy { it.finishedAt }.takeLast(MAX_FINISHED).map { it.id }.toSet()
                state.copy(requests = updated.filter { it.running || it.id in keepFinished })
            }
        }
    }

    private fun updateRequest(id: Long, transform: (RequestStatus) -> RequestStatus) {
        _state.update { state -> state.copy(requests = state.requests.map { if (it.id == id) transform(it) else it }) }
    }

    private companion object {
        const val MAX_FINISHED = 20
    }
}
