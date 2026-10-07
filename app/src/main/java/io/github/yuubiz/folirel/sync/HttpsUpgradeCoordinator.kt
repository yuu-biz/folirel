package io.github.yuubiz.folirel.sync

import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.backend.local.HttpsUpgradeChecker
import io.github.yuubiz.folirel.data.repository.SubscriptionRepository
import io.github.yuubiz.folirel.model.HttpsCheck
import io.github.yuubiz.folirel.model.HttpsReason
import io.github.yuubiz.folirel.model.HttpsVerdict
import io.github.yuubiz.folirel.model.Structure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class HttpsUpgradeState(
    val running: Boolean = false,
    val total: Int = 0,
    val results: List<HttpsCheck> = emptyList(),
    val finished: Boolean = false,
    val cancelled: Boolean = false,
) {
    val checked: Int get() = results.size
    fun count(verdict: HttpsVerdict): Int = results.count { it.verdict == verdict }
}

/**
 * Runs the consented HTTP → HTTPS check for all subscribed http:// feeds in the background (one consent for
 * the whole batch). Each verified feed is switched immediately, so cancelling keeps what was already verified;
 * everything else keeps its HTTP URL. Feeds that were switched are then refreshed once as a manual request.
 * Whether to allow the remaining HTTP feeds is a separate setting and is never changed here.
 */
class HttpsUpgradeCoordinator(
    private val checker: HttpsUpgradeChecker,
    private val subscriptions: SubscriptionRepository,
    private val refresh: RefreshCoordinator,
    private val scope: CoroutineScope,
    private val maxParallel: Int = 4,
    private val maxPerHost: Int = 2,
    /** False while a full restore runs: no check starts then. */
    private val allowed: () -> Boolean = { true },
) {
    private val _state = MutableStateFlow(HttpsUpgradeState())
    val state: StateFlow<HttpsUpgradeState> = _state.asStateFlow()
    private var job: Job? = null

    suspend fun candidateCount(): Int = subscriptions.httpFeeds(subscriptions.ensureLocalAccount()).size

    /** A check is running (a restore waits for none). */
    val isRunning: Boolean get() = job?.isActive == true

    /** Starts the check; returns false if one is already running or a restore is under way. */
    @Synchronized
    fun start(): Boolean {
        if (job?.isActive == true || !allowed()) return false
        _state.value = HttpsUpgradeState(running = true)
        job = scope.launch { runCheck() }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /** Clears a finished result from the screen. */
    fun dismiss() {
        if (!_state.value.running) _state.value = HttpsUpgradeState()
    }

    private suspend fun runCheck() {
        val feeds = subscriptions.httpFeeds(subscriptions.ensureLocalAccount())
        _state.update { it.copy(total = feeds.size) }
        val global = Semaphore(maxParallel)
        val hosts = HashMap<String, Semaphore>()
        val changed = ArrayList<String>()
        try {
            coroutineScope {
                for (feed in feeds) {
                    val host = feed.fetchUrl.toHttpUrlOrNull()?.host.orEmpty()
                    val hostLimit = synchronized(hosts) { hosts.getOrPut(host) { Semaphore(maxPerHost) } }
                    launch {
                        val check = hostLimit.withPermit { global.withPermit { checkOne(feed) } }
                        if (check.verdict == HttpsVerdict.CHANGED) synchronized(changed) { changed += feed.id }
                        _state.update { it.copy(results = it.results + check) }
                    }
                }
            }
            _state.update { it.copy(running = false, finished = true) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                _state.update { s ->
                    val done = s.results.map { it.feedId }.toSet()
                    val rest = feeds.filter { it.id !in done }.map {
                        HttpsCheck(it.id, Structure.displayName(it), it.fetchUrl, null, HttpsVerdict.UNCHECKED, HttpsReason.CANCELLED)
                    }
                    s.copy(running = false, finished = true, cancelled = true, results = s.results + rest)
                }
            }
            throw e
        } finally {
            val ids = synchronized(changed) { changed.toList() }
            // Fetch the switched feeds once (separate job: not tied to this check's cancellation).
            if (ids.isNotEmpty()) scope.launch { refresh.refresh(RefreshScope.Feeds(ids), RefreshReason.MANUAL) }
        }
    }

    private suspend fun checkOne(feed: io.github.yuubiz.folirel.data.db.FeedEntity): HttpsCheck {
        val name = Structure.displayName(feed)
        val (title, keys) = subscriptions.knownContent(feed.id)
        val result = try {
            checker.check(feed.fetchUrl, HttpsUpgradeChecker.Known(title, keys.toSet()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            HttpsUpgradeChecker.Result(HttpsVerdict.UNCHECKED, HttpsReason.NETWORK, null)
        }
        if (result.verdict == HttpsVerdict.CHANGED && result.httpsUrl != null) {
            val applied = withContext(NonCancellable) {
                subscriptions.applyHttpsUpgrade(feed.id, feed.fetchUrl, result.httpsUrl).also { if (it) refresh.feedAddressChanged() }
            }
            if (!applied) return HttpsCheck(feed.id, name, feed.fetchUrl, result.httpsUrl, HttpsVerdict.UNCHANGED, HttpsReason.SKIPPED)
        }
        return HttpsCheck(feed.id, name, feed.fetchUrl, result.httpsUrl, result.verdict, result.reason, result.httpStatus)
    }
}
