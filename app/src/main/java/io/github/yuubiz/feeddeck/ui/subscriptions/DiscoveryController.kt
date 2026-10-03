package io.github.yuubiz.feeddeck.ui.subscriptions

import io.github.yuubiz.feeddeck.model.DiscoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The state of "find where this feed moved" on the feed detail screen (V3-R04). Only the latest request may write it:
 * starting a run or clearing the results invalidates every earlier run, whether it notices its cancellation or not,
 * so a late or cancelled run can neither replace the newer run's state nor bring back results that were closed.
 * Used from one thread (the view model's main scope).
 */
class DiscoveryController(
    private val scope: CoroutineScope,
    private val run: suspend (feedId: String, enteredUrl: String?) -> DiscoveryResult?,
) {
    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun start(feedId: String, enteredUrl: String? = null) {
        job?.cancel()
        val mine = ++generation
        _state.value = DiscoveryState.Running(feedId)
        job = scope.launch {
            val result = try {
                run(feedId, enteredUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (mine == generation) _state.value = DiscoveryState.Done(feedId, result, enteredUrl)
        }
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        _state.value = DiscoveryState.Idle
    }
}
