package io.github.yuubiz.feeddeck.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlParser
import io.github.yuubiz.feeddeck.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * S03–S06 (docs/FeedDeck_Next_Iteration_Mobile_v2.md §5.1): a narrow manual refresh issued while a refresh of
 * everything is running must act on its own target. Every assertion is on the HTTP calls MockWebServer
 * actually received and on the DB, not on a mocked report.
 */
@RunWith(AndroidJUnit4::class)
class ScopedRefreshTest {
    private val server: MockWebServer = TestEnv.startServer()
    private var env = TestEnv(maxParallel = 2, maxPerHost = 2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Paths in the order their requests arrived. */
    private val order: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val calls = ConcurrentHashMap<String, AtomicInteger>()

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
        server.close()
    }

    private fun rss(guid: String) =
        """<rss version="2.0"><channel><title>f</title><item><guid>$guid</guid><title>$guid</title></item></channel></rss>"""

    private fun ok(guid: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(rss(guid)).build()

    /** `/slow*` answers after [slowMillis]; `/err*` answers [errorResponse]; everything else immediately. */
    private fun serve(slowMillis: Long, errorResponse: (String) -> MockResponse? = { null }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                order += path
                val n = calls.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                errorResponse(path)?.let { return it }
                if (path.startsWith("/slow")) Thread.sleep(slowMillis)
                return ok("$path-$n")
            }
        }
    }

    private suspend fun subscribe(vararg paths: String): List<String> {
        val opml = buildString {
            append("<opml version=\"2.0\"><body>")
            paths.forEach { append("<outline text=\"f\" xmlUrl=\"${server.url(it)}\"/>") }
            append("</body></opml>")
        }
        return env.subscriptions.commitImport(env.subscriptions.ensureLocalAccount(), OpmlParser.parse(opml.toByteArray())).feedIdsToFetch
    }

    private suspend fun coordinator(): RefreshCoordinator {
        val account = env.subscriptions.ensureLocalAccount()
        return RefreshCoordinator(env.backend, { account }, { true }, {}, scope, env.clock)
    }

    private fun waitFor(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not reached" }
            Thread.sleep(10)
        }
    }

    private fun count(path: String) = calls[path]?.get() ?: 0

    private suspend fun articles(feedId: String) = env.db.articleDao().articlesOfFeed(feedId).size

    /** S03: the targeted feed is fetched at the next free slot and its result returns before All finishes. */
    @Test
    fun s03_targetedFeedIsPrioritizedWhileAllIsRunning() = runBlocking<Unit> {
        serve(slowMillis = 300)
        val ids = subscribe("/slow0", "/slow1", "/slow2", "/slow3", "/slow4", "/slow5", "/slow6", "/target")
        val target = ids.last()
        val c = coordinator()
        val all = scope.async { c.refresh(RefreshScope.All, RefreshReason.MANUAL).also { allDoneAt = System.nanoTime() } }
        waitFor { order.size >= 2 }
        val narrow = c.refresh(RefreshScope.Feeds(listOf(target)), RefreshReason.MANUAL)
        val narrowDoneAt = System.nanoTime()
        assertEquals("target fetched for the narrow request", 1, count("/target"))
        assertEquals(1, narrow.updated)
        assertTrue("target must not wait behind every other feed: order=$order", order.indexOf("/target") <= 3)
        all.await()
        assertTrue("narrow result returned before All finished", narrowDoneAt < allDoneAt)
        assertEquals(1, articles(target))
        assertEquals("each feed fetched once", 8, order.size)
    }

    @Volatile private var allDoneAt = Long.MAX_VALUE

    /** S04: a feed excluded from an automatic All by its failure backoff is retried by a manual narrow request. */
    @Test
    fun s04_manualNarrowRetriesFeedInAutomaticBackoff() = runBlocking<Unit> {
        var failing = true
        serve(slowMillis = 300) { path ->
            when {
                path == "/flaky" && failing -> MockResponse.Builder().code(500).build()
                path == "/limited" -> MockResponse.Builder().code(429).setHeader("Retry-After", "600").build()
                else -> null
            }
        }
        val ids = subscribe("/slow0", "/slow1", "/slow2", "/slow3", "/flaky", "/limited")
        val flaky = ids[4]
        val limited = ids[5]
        val c = coordinator()
        c.refresh(RefreshScope.Feeds(listOf(flaky, limited)), RefreshReason.MANUAL)
        assertEquals(1, count("/flaky"))
        assertEquals(1, count("/limited"))
        failing = false
        env.clock.advanceMinutes(1)

        val periodic = scope.async { c.refresh(RefreshScope.All, RefreshReason.PERIODIC) }
        waitFor { order.size >= 3 }
        val narrow = c.refresh(RefreshScope.Feeds(listOf(flaky)), RefreshReason.MANUAL)
        assertEquals("manual retries the feed the automatic run skipped", 2, count("/flaky"))
        assertEquals(1, narrow.updated)
        assertEquals(1, articles(flaky))
        // A valid Retry-After is respected even by a manual request.
        val limitedReport = c.refresh(RefreshScope.Feeds(listOf(limited)), RefreshReason.MANUAL)
        assertEquals(1, count("/limited"))
        assertEquals(1, limitedReport.deferredBackoff)
        val auto = periodic.await()
        assertEquals("automatic run skips both backoff feeds", 2, auto.deferredBackoff)
    }

    /** S05: All's budget expires right after the narrow request; the target is still fetched for the narrow request. */
    @Test
    fun s05_targetIsNotLeftUnstartedWhenAllRunsOutOfBudget() = runBlocking<Unit> {
        env.close()
        env = TestEnv(maxParallel = 2, maxPerHost = 2, budgetMillis = 1_500)
        serve(slowMillis = 500)
        val ids = subscribe(*(0 until 9).map { "/slow$it" }.toTypedArray(), "/target")
        val target = ids.last()
        val c = coordinator()
        val all = scope.async { c.refresh(RefreshScope.All, RefreshReason.MANUAL) }
        Thread.sleep(1_200)
        val narrow = c.refresh(RefreshScope.Feeds(listOf(target)), RefreshReason.MANUAL)
        assertEquals("target fetched", 1, count("/target"))
        assertEquals(1, narrow.updated)
        assertEquals(0, narrow.notAttempted)
        assertEquals(1, articles(target))
        val allReport = all.await()
        assertTrue("All itself ran out of budget", allReport.notAttempted > 0)
    }

    /** S06: a feed already finished inside the running All is fetched again for a later manual request. */
    @Test
    fun s06_pastCompletionInsideAllIsNotReused() = runBlocking<Unit> {
        serve(slowMillis = 400)
        // "/a-target" sorts first, so All fetches it before the slow feeds (feeds come back ordered by URL).
        val ids = subscribe("/a-target", "/slow0", "/slow1", "/slow2", "/slow3", "/slow4")
        val target = ids.first()
        val c = coordinator()
        val all = scope.async { c.refresh(RefreshScope.All, RefreshReason.MANUAL) }
        // Wait until All has finished and applied the target (not merely started it) while other feeds still run.
        waitFor { runBlocking { articles(target) } == 1 }
        assertTrue("All still running", !all.isCompleted)
        val narrow = c.refresh(RefreshScope.Feeds(listOf(target)), RefreshReason.MANUAL)
        assertEquals("fetched again for the new manual request", 2, count("/a-target"))
        assertEquals(1, narrow.updated)
        assertEquals(2, articles(target))
        all.await()
    }
}
