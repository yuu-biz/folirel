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
import java.util.concurrent.atomic.AtomicInteger

data class RefreshState(
    val running: Boolean = false,
    val progress: RefreshProgress? = null,
    val lastReport: RefreshReport? = null,
    val lastFinishedAt: Long? = null,
    /** True when the last automatic refresh was skipped by the network constraint. */
    val lastAutoBlocked: Boolean = false,
)

/**
 * Single entry point for every refresh (manual, import, startup, periodic). Identical requests are joined;
 * a running "All" refresh absorbs narrower ones. Per-feed de-duplication happens in the backend.
 * Jobs run in the application [scope], so leaving a screen does not cancel a refresh.
 */
class RefreshCoordinator(
    private val backend: BackendSynchronizer,
    private val accountId: suspend () -> String,
    /** For automatic reasons only: whether the current network satisfies the auto-update constraint. */
    private val automaticAllowed: suspend () -> Boolean,
    private val onAutomaticAttempt: suspend (Long) -> Unit,
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private val _state = MutableStateFlow(RefreshState())
    val state: StateFlow<RefreshState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val running = HashMap<RefreshScope, Deferred<RefreshReport>>()
    private val active = AtomicInteger()

    suspend fun refresh(target: RefreshScope, reason: RefreshReason): RefreshReport {
        if (reason.isAutomatic) {
            if (!automaticAllowed()) {
                _state.update { it.copy(lastAutoBlocked = true) }
                return RefreshReport(total = 0, deferredConstraint = 1)
            }
            onAutomaticAttempt(clock.millis())
        }
        val job = mutex.withLock {
            running[RefreshScope.All] ?: running[target] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    run(target, reason)
                } finally {
                    withContext(NonCancellable) { mutex.withLock { running.remove(target) } }
                }
            }.also { running[target] = it }
        }
        job.start() // outside the lock; no-op when joining an already running job
        return job.await()
    }

    private suspend fun run(target: RefreshScope, reason: RefreshReason): RefreshReport {
        active.incrementAndGet()
        _state.update { it.copy(running = true, lastAutoBlocked = if (reason.isAutomatic) false else it.lastAutoBlocked) }
        var report: RefreshReport? = null
        try {
            report = backend.refresh(accountId(), target, reason) { p -> _state.update { it.copy(progress = p) } }
            return report
        } finally {
            val stillRunning = active.decrementAndGet() > 0
            val finished = report
            _state.update {
                it.copy(
                    running = stillRunning,
                    progress = if (stillRunning) it.progress else null,
                    lastReport = finished ?: it.lastReport,
                    lastFinishedAt = clock.millis(),
                )
            }
        }
    }
}
