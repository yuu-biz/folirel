package io.github.yuubiz.feeddeck.sync

import io.github.yuubiz.feeddeck.backend.BackendSynchronizer
import io.github.yuubiz.feeddeck.backend.RefreshProgress
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshReport
import io.github.yuubiz.feeddeck.backend.RefreshScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
}

/**
 * Single entry point for every refresh (manual, import, startup, periodic).
 * - Each request acts on its own target: a running "All" no longer absorbs a narrower request. Overlap is
 *   resolved per feed in the backend (a feed queued/running for one request is shared, never fetched twice).
 * - Only an identical request (same target, same manual/automatic kind, same force) joins a running one.
 * - Manual, import and startup requests run in the application [scope], so leaving a screen does not cancel them.
 *   Periodic requests run in the calling worker's coroutine: stopping the worker releases its feeds, and fetches
 *   that only it wanted are cancelled (finished feeds stay applied).
 */
class RefreshCoordinator(
    private val backend: BackendSynchronizer,
    private val accountId: suspend () -> String,
    /** For automatic reasons only: whether the current network satisfies the auto-update constraint. */
    private val automaticAllowed: suspend () -> Boolean,
    /** Called when a refresh request is accepted (manual or automatic); the startup 30-minute rule uses it. */
    private val onAttempt: suspend (Long) -> Unit,
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private val _state = MutableStateFlow(RefreshState())
    val state: StateFlow<RefreshState> = _state.asStateFlow()

    private data class Key(val scope: RefreshScope, val automatic: Boolean, val force: Boolean)

    private val mutex = Mutex()
    private val joinable = HashMap<Key, Deferred<RefreshReport>>()
    private val ids = AtomicLong()

    suspend fun refresh(target: RefreshScope, reason: RefreshReason, force: Boolean = false): RefreshReport {
        if (reason.isAutomatic && !automaticAllowed()) {
            _state.update { it.copy(lastAutoBlocked = true) }
            return RefreshReport(total = 0, deferredConstraint = 1)
        }
        onAttempt(clock.millis())
        if (reason == RefreshReason.PERIODIC) return run(target, reason, force)
        val key = Key(target, reason.isAutomatic, force)
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
            report = backend.refresh(accountId(), target, reason, force) { p -> updateRequest(id) { r -> r.copy(progress = p) } }
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
