package io.github.yuubiz.folirel.ui.share

import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.model.ShareCandidate
import io.github.yuubiz.folirel.model.ShareDiscovery
import io.github.yuubiz.folirel.model.ShareProblem
import io.github.yuubiz.folirel.network.SharedUrlExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Share to Subscribe overlay shows. Every state of one request carries that request's [id]. */
sealed interface ShareState {
    data object Idle : ShareState

    /** The shared text had several addresses; none is taken until the user picks one. */
    data class ChoosingUrl(val id: Long, val urls: List<String>) : ShareState

    data class Discovering(val id: Long, val url: String) : ShareState

    /** Verified feeds, nothing registered yet. One candidate is shown for confirmation like several are. */
    data class Candidates(val id: Long, val url: String, val candidates: List<ShareCandidate>) : ShareState

    data class Failed(val id: Long, val problem: ShareProblem) : ShareState

    data class Subscribing(val id: Long, val url: String) : ShareState

    data class Done(val id: Long, val result: AddFeedResult) : ShareState
}

/**
 * The flow "shared text -> address -> feed candidates -> explicit subscribe" (Share to Subscribe). Receiving a share only
 * starts looking; nothing is registered until [subscribe] is called for a candidate the user tapped.
 *
 * Like `DiscoveryController`, only the latest request may write the state: a new share, [cancel] or a new choice
 * invalidates every earlier run, whether it notices its cancellation or not, so a late result can neither replace a newer request nor
 * bring back one that was closed. A share that arrives before the app is [setReady] (first-run setup not finished) is kept and
 * handled when it is. Used from one thread (the view model's main scope).
 */
class ShareSubscriptionController(
    private val scope: CoroutineScope,
    private val discover: suspend (url: String) -> ShareDiscovery,
    /** Registers [url] through the one add-feed path; exactly one of the callbacks is called. */
    private val register: (url: String, onDone: (AddFeedResult) -> Unit, onFailed: () -> Unit) -> Unit,
) {
    private val _state = MutableStateFlow<ShareState>(ShareState.Idle)
    val state: StateFlow<ShareState> = _state.asStateFlow()

    private var job: Job? = null
    private var generation = 0L
    private var ready = false
    private var waiting: Pair<Long, String?>? = null

    /** The text another app shared. Every call is a new request, even for the same text as before. */
    fun receive(text: String?) {
        invalidate()
        waiting = generation to text
        _state.value = ShareState.Idle
        drain()
    }

    /** False until first-run setup is done: the shared request waits and does not skip it. */
    fun setReady(value: Boolean) {
        ready = value
        drain()
    }

    private fun drain() {
        val (id, text) = waiting ?: return
        if (!ready) return
        waiting = null
        when (val urls = SharedUrlExtractor.extract(text)) {
            SharedUrlExtractor.Result.None -> _state.value = ShareState.Failed(id, ShareProblem.NO_URL)
            SharedUrlExtractor.Result.TooLarge -> _state.value = ShareState.Failed(id, ShareProblem.TEXT_TOO_LARGE)
            is SharedUrlExtractor.Result.Found ->
                if (urls.urls.size == 1) look(id, urls.urls.first()) else _state.value = ShareState.ChoosingUrl(id, urls.urls)
        }
    }

    /** The user picked which of several shared addresses to look at. */
    fun choose(url: String) {
        val current = _state.value as? ShareState.ChoosingUrl ?: return
        if (url !in current.urls) return
        invalidate()
        look(generation, url)
    }

    private fun look(id: Long, url: String) {
        _state.value = ShareState.Discovering(id, url)
        job = scope.launch {
            val result = try {
                discover(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ShareDiscovery.Failed(ShareProblem.FAILED)
            }
            if (id != generation) return@launch
            _state.value = when (result) {
                is ShareDiscovery.Found -> ShareState.Candidates(id, url, result.candidates)
                is ShareDiscovery.Failed -> ShareState.Failed(id, result.problem)
            }
        }
    }

    /** The user confirmed [candidate] (new, or an unsubscribed feed to take again). A feed that is already subscribed is not offered. */
    fun subscribe(candidate: ShareCandidate) {
        val current = _state.value as? ShareState.Candidates ?: return
        if (candidate !in current.candidates || candidate.existing?.subscribed == true) return
        val id = current.id
        _state.value = ShareState.Subscribing(id, candidate.url)
        register(
            candidate.subscribeUrl,
            { result -> if (id == generation) _state.value = ShareState.Done(id, result) },
            { if (id == generation) _state.value = ShareState.Failed(id, ShareProblem.FAILED) },
        )
    }

    /**
     * Closes the flow without a change: the run is cancelled and invalidated and the overlay goes away. A registration that is
     * already being written is not interrupted (and the overlay stays until it is done).
     */
    fun cancel() {
        if (_state.value is ShareState.Subscribing) return
        invalidate()
        waiting = null
        _state.value = ShareState.Idle
    }

    /** The overlay has shown the end of a request. */
    fun acknowledge() {
        if (_state.value is ShareState.Done) _state.value = ShareState.Idle
    }

    private fun invalidate() {
        generation++
        job?.cancel()
        job = null
    }
}
