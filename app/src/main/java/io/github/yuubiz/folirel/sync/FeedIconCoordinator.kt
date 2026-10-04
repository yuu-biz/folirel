package io.github.yuubiz.folirel.sync

import io.github.yuubiz.folirel.backend.local.icon.FeedIconResolver
import io.github.yuubiz.folirel.data.repository.FeedIconRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the due icon lookups in the background (UX-ICON). Requests coalesce: while a run is going, another
 * [request] only makes the run look once more. Lookups use a few parallel slots and never block or fail a feed
 * refresh. Nothing runs while [enabled] is false (images off, offline, metered-only).
 */
class FeedIconCoordinator(
    private val resolver: FeedIconResolver,
    private val repository: FeedIconRepository,
    private val accountId: suspend () -> String,
    private val enabled: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val parallelism: Int = 4,
) {
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private var job: Job? = null

    /** Starts (or extends) a background run. Cheap to call often. */
    fun request() {
        again.set(true)
        if (!running.compareAndSet(false, true)) return
        job = scope.launch(Dispatchers.IO) {
            try {
                while (again.getAndSet(false)) runPasses()
            } finally {
                running.set(false)
            }
            // A request that arrived between the last check and the flag reset.
            if (again.get()) request()
        }
    }

    /** All due lookups now, in this coroutine. Returns how many were attempted. Used by the background run and tests. */
    suspend fun runPasses(): Int {
        val attempted = java.util.concurrent.atomic.AtomicInteger()
        repeat(MAX_PASSES) {
            if (!enabled()) return attempted.get()
            val due = repository.due(accountId(), PASS_SIZE)
            if (due.isEmpty()) return attempted.get()
            val slots = Semaphore(parallelism)
            kotlinx.coroutines.coroutineScope {
                due.map { lookup ->
                    async {
                        slots.withPermit {
                            if (enabled()) {
                                val result = try {
                                    resolver.resolve(lookup.target)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    FeedIconResolver.Result.Retry
                                }
                                repository.record(lookup.feedId, result)
                                attempted.incrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return attempted.get()
    }

    private companion object {
        const val PASS_SIZE = 100
        const val MAX_PASSES = 50
    }
}
