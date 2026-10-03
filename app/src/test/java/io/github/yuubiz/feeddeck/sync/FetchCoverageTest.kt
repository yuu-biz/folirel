package io.github.yuubiz.feeddeck.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.feeddeck.backend.FeedResult
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.backend.local.LocalBackend
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlParser
import io.github.yuubiz.feeddeck.model.ArticleQuerySpec
import io.github.yuubiz.feeddeck.model.ArticleScope
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.model.ReadFilter
import io.github.yuubiz.feeddeck.model.SortOrder
import io.github.yuubiz.feeddeck.network.NetworkState
import io.github.yuubiz.feeddeck.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fetch coverage and diagnostics (docs/FeedDeck_Next_Iteration_Mobile_v2.md §5.1 S01, S02, S07–S16 and §5
 * N12–N17). Each test checks the HTTP calls the local server actually received and what the DB holds.
 */
@RunWith(AndroidJUnit4::class)
class FetchCoverageTest {
    private val server: MockWebServer = TestEnv.startServer()
    private var env = TestEnv()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val order: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val calls = ConcurrentHashMap<String, AtomicInteger>()

    /** Response per path; called for every request. */
    private var respond: (path: String, n: Int, request: RecordedRequest) -> MockResponse = { path, n, _ -> ok(rss(listOf(item("$path-$n")))) }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                order += path
                requests += request
                val n = calls.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                return respond(path, n, request)
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
        server.close()
    }

    private fun date(daysAgo: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(env.clock.instant().minusSeconds(daysAgo * 86_400).atOffset(ZoneOffset.UTC))

    private fun item(guid: String, pubDate: String? = null) =
        "<item><guid>$guid</guid><title>$guid</title>${pubDate?.let { "<pubDate>$it</pubDate>" }.orEmpty()}</item>"

    private fun rss(items: List<String>) = """<rss version="2.0"><channel><title>f</title>${items.joinToString("")}</channel></rss>"""

    private fun ok(body: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(body).build()

    private fun count(path: String) = calls[path]?.get() ?: 0

    private suspend fun account() = env.subscriptions.ensureLocalAccount()

    /** Outline XML: `"Cat/Sub" to listOf(urls)`; an empty category name means uncategorized. */
    private suspend fun import(vararg groups: Pair<String, List<String>>): Map<String, String> {
        val opml = buildString {
            append("<opml version=\"2.0\"><body>")
            for ((category, urls) in groups) {
                val segments = category.split('/').filter { it.isNotEmpty() }
                segments.forEach { append("<outline text=\"$it\">") }
                urls.forEach { append("<outline text=\"f\" xmlUrl=\"$it\"/>") }
                segments.forEach { _ -> append("</outline>") }
            }
            append("</body></opml>")
        }
        env.subscriptions.commitImport(account(), OpmlParser.parse(opml.toByteArray()))
        return env.db.subscriptionDao().feeds(account()).associate { it.subscriptionUrl.substringAfterLast('/') to it.id }
    }

    private fun url(path: String) = server.url(path).toString()

    private suspend fun refresh(scope: RefreshScope, reason: RefreshReason = RefreshReason.MANUAL, force: Boolean = false) =
        env.backend.refresh(account(), scope, reason, force) {}

    private suspend fun categoryId(name: String) = env.db.subscriptionDao().categories(account()).single { it.name == name }.id

    private suspend fun diag(feedId: String) = env.db.diagnosticsDao().diagnostics(feedId)

    private fun waitFor(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not reached" }
            Thread.sleep(10)
        }
    }

    /** S01: a category refresh contacts exactly the feeds of the category and its children, each once. */
    @Test
    fun s01_categoryRefreshTargetsOnlyItsFeeds() = runBlocking<Unit> {
        val ids = import(
            "Tech" to listOf(url("/a1"), url("/ab")),
            "Tech/Android" to listOf(url("/a2")),
            "News" to listOf(url("/ab"), url("/b1")),
            "" to listOf(url("/u")),
        )
        val report = refresh(RefreshScope.Category(categoryId("Tech")))
        assertEquals(setOf("/a1", "/ab", "/a2"), order.toSet())
        assertEquals("each feed once (multi-category feed not twice)", 3, order.size)
        assertEquals(3, report.updated)
        assertEquals(setOf(ids["a1"], ids["ab"], ids["a2"]), report.perFeed.keys)

        order.clear()
        refresh(RefreshScope.Uncategorized)
        assertEquals(listOf("/u"), order.toList())
    }

    /** S02: a feed whose newest article is 30 days old (and was fetched successfully before) is still fetched. */
    @Test
    fun s02_singleFeedRefreshIgnoresArticleDates() = runBlocking<Unit> {
        respond = { _, n, _ -> ok(rss(listOf(item("old-$n", date(30))))) }
        val ids = import("" to listOf(url("/old"), url("/other")))
        refresh(RefreshScope.All)
        env.clock.advanceMinutes(5)
        order.clear()
        val report = refresh(RefreshScope.Feeds(listOf(ids.getValue("old"))))
        assertEquals(listOf("/old"), order.toList())
        assertEquals(1, report.updated)
        assertEquals(2, env.db.articleDao().articlesOfFeed(ids.getValue("old")).size)
    }

    /** S07: one feed with many entries does not crowd out the others; every current document is applied in full. */
    @Test
    fun s07_largeFeedDoesNotStopOthers() = runBlocking<Unit> {
        env.close()
        env = TestEnv(resolver = Dns { host -> if (host.endsWith(".test")) listOf(InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(host) })
        respond = { path, _, _ ->
            if (path == "/big") ok(rss((1..300).map { item("big-$it", date(it.toLong())) })) else ok(rss((1..5).map { item("$path-$it") }))
        }
        val other = "http://other.test:${server.port}"
        val ids = import("" to listOf(url("/big"), url("/b"), url("/c"), "$other/d", "$other/e"))
        val report = refresh(RefreshScope.All)
        assertEquals(5, report.updated)
        assertEquals(300, env.db.articleDao().articlesOfFeed(ids.getValue("big")).size)
        for (name in listOf("b", "c", "d", "e")) assertEquals(name, 5, env.db.articleDao().articlesOfFeed(ids.getValue(name)).size)
    }

    /** S08: a budget cut leaves feeds pending; the next run (also after a "restart") starts with them. */
    @Test
    fun s08_unfinishedFeedsGoFirstNextTime() = runBlocking<Unit> {
        env.close()
        env = TestEnv(budgetMillis = 900, maxParallel = 1, maxPerHost = 1)
        respond = { path, n, _ ->
            Thread.sleep(400)
            ok(rss(listOf(item("$path-$n"))))
        }
        val ids = import("" to listOf(url("/f1"), url("/f2"), url("/f3"), url("/f4")))
        val first = refresh(RefreshScope.All)
        assertTrue("budget cut expected: $first", first.notAttempted > 0)
        val done = first.perFeed.filterValues { it.result == FeedResult.UPDATED }.keys
        val unfinished = ids.values.toSet() - done
        assertTrue(done.isNotEmpty() && unfinished.isNotEmpty())
        for (id in unfinished) {
            val d = diag(id)!!
            assertNotNull("pending survives for $id", d.pendingSince)
            assertNull("no success time for an unstarted feed", env.db.subscriptionDao().fetchState(id)!!.lastSuccessAt)
        }
        assertTrue(first.perFeed.values.any { it.result == FeedResult.NOT_STARTED })
        assertEquals(unfinished, env.store.activeFeeds(account(), RefreshScope.Pending).map { it.id }.toSet())

        // "Restart": a new backend instance on the same database.
        val restarted = LocalBackend(env.store, env.fetcher, env.clock, env.network, { true }, budgetMillis = 60_000, maxParallel = 1, maxPerHost = 1)
        env.clock.advanceMinutes(1)
        order.clear()
        restarted.refresh(account(), RefreshScope.All, RefreshReason.PERIODIC) {}
        val byPath = ids.entries.associate { it.value to "/${it.key}" }
        val firstPaths = order.take(unfinished.size).toSet()
        assertEquals("unfinished feeds first: $order", unfinished.map { byPath.getValue(it) }.toSet(), firstPaths)
        assertTrue(env.store.activeFeeds(account(), RefreshScope.Pending).isEmpty())
    }

    /** S09: dates never cut the current document: old, recent and undated entries are all stored. */
    @Test
    fun s09_allEntriesOfTheDocumentAreStoredRegardlessOfDate() = runBlocking<Unit> {
        val items = (1..10).map { item("old-$it", date(30)) } + (1..100).map { item("new-$it", date(it % 7L)) } + (1..3).map { item("nodate-$it") }
        respond = { _, _, _ -> ok(rss(items)) }
        val id = import("" to listOf(url("/mixed"))).getValue("mixed")
        refresh(RefreshScope.All)
        assertEquals(113, env.db.articleDao().articlesOfFeed(id).size)
        val d = diag(id)!!
        assertEquals(113, d.rawEntries)
        assertEquals(113, d.uniqueEntries)
        assertEquals(3, d.undatedEntries)
        assertEquals(113, d.insertedArticles)
    }

    /** S10: a feed returning 3 entries stores 3, reports 3, and is requested once (no paging loop). */
    @Test
    fun s10_shortFeedIsReportedAsIs() = runBlocking<Unit> {
        respond = { _, _, _ -> ok(rss((1..3).map { item("s-$it") })) }
        val id = import("" to listOf(url("/short"))).getValue("short")
        refresh(RefreshScope.Feeds(listOf(id)))
        assertEquals(1, count("/short"))
        assertEquals(3, env.db.articleDao().articlesOfFeed(id).size)
        assertEquals(3, diag(id)!!.uniqueEntries)
    }

    /** S11 / N15: a 304 keeps the previous body counts; a forced refetch sends no validators and applies the new body. */
    @Test
    fun s11_notModifiedKeepsCountsAndForceRevalidates() = runBlocking<Unit> {
        respond = { _, n, request ->
            when {
                n == 1 -> ok(rss((1..5).map { item("e-$it") })).newBuilder().setHeader("ETag", "\"v1\"").build()
                request.headers["If-None-Match"] != null -> MockResponse.Builder().code(304).build()
                else -> ok(rss((1..6).map { item("e-$it") })).newBuilder().setHeader("ETag", "\"v2\"").build()
            }
        }
        val id = import("" to listOf(url("/c"))).getValue("c")
        refresh(RefreshScope.All)
        env.clock.advanceMinutes(10)
        val second = refresh(RefreshScope.Feeds(listOf(id)))
        assertEquals(FeedResult.NOT_MODIFIED, second.perFeed.getValue(id).result)
        assertEquals("\"v1\"", requests[1].headers["If-None-Match"])
        val afterNotModified = diag(id)!!
        assertEquals(5, afterNotModified.uniqueEntries)
        assertEquals(304, afterNotModified.httpStatus)

        env.clock.advanceMinutes(10)
        val forced = refresh(RefreshScope.Feeds(listOf(id)), force = true)
        val last = requests.last()
        assertNull(last.headers["If-None-Match"])
        assertNull(last.headers["If-Modified-Since"])
        assertEquals("no-cache", last.headers["Cache-Control"])
        assertEquals(FeedResult.UPDATED, forced.perFeed.getValue(id).result)
        assertEquals(1, forced.perFeed.getValue(id).newArticles)
        assertEquals(5, forced.perFeed.getValue(id).updatedArticles)
        assertEquals(6, env.db.articleDao().articlesOfFeed(id).size)
        assertEquals("\"v2\"", env.db.subscriptionDao().fetchState(id)!!.etag)
        assertEquals(1, requests.count { it.headers["Cache-Control"] == "no-cache" })
    }

    /** S12: force during a running conditional fetch → exactly one follow-up unconditional fetch, even when pressed twice. */
    @Test
    fun s12_forceIsNotAbsorbedByARunningConditionalFetch() = runBlocking<Unit> {
        val gate = CountDownLatch(1)
        respond = { _, n, _ ->
            if (n == 2) gate.await(5, TimeUnit.SECONDS)
            ok(rss(listOf(item("x-$n")))).newBuilder().setHeader("ETag", "\"v$n\"").build()
        }
        val id = import("" to listOf(url("/x"))).getValue("x")
        refresh(RefreshScope.All)
        val conditional = scope.async { refresh(RefreshScope.Feeds(listOf(id))) }
        waitFor { count("/x") == 2 }
        val force1 = scope.async { refresh(RefreshScope.Feeds(listOf(id)), force = true) }
        val force2 = scope.async { refresh(RefreshScope.Feeds(listOf(id)), force = true) }
        Thread.sleep(200)
        assertEquals("follow-up waits for the running fetch", 2, count("/x"))
        gate.countDown()
        assertEquals(FeedResult.UPDATED, conditional.await().perFeed.getValue(id).result)
        assertEquals(FeedResult.UPDATED, force1.await().perFeed.getValue(id).result)
        assertEquals(FeedResult.UPDATED, force2.await().perFeed.getValue(id).result)
        assertEquals("one conditional + one forced follow-up", 3, count("/x"))
        assertNotNull("the second fetch was conditional", requests[1].headers["If-None-Match"])
        assertNull("the follow-up is unconditional", requests[2].headers["If-None-Match"])
    }

    /** S13: force still respects a valid Retry-After (429/503), HTTP opt-in and the size limit. */
    @Test
    fun s13_forceKeepsSafetyRules() = runBlocking<Unit> {
        val hugeBody = "<rss version=\"2.0\"><channel><title>" + "a".repeat(6 * 1024 * 1024) + "</title></channel></rss>"
        respond = { path, _, _ ->
            when (path) {
                "/busy" -> MockResponse.Builder().code(503).setHeader("Retry-After", "600").build()
                "/limited" -> MockResponse.Builder().code(429).setHeader("Retry-After", "600").build()
                "/huge" -> ok(hugeBody)
                else -> ok(rss(listOf(item(path))))
            }
        }
        val ids = import("" to listOf(url("/busy"), url("/limited"), url("/huge"), url("/plain")))
        refresh(RefreshScope.All)
        val busy = ids.getValue("busy")
        val limited = ids.getValue("limited")
        val before = requests.size
        val forced = refresh(RefreshScope.Feeds(listOf(busy, limited)), force = true)
        assertEquals(before, requests.size)
        assertEquals(FeedResult.BACKOFF, forced.perFeed.getValue(busy).result)
        assertEquals(FeedResult.BACKOFF, forced.perFeed.getValue(limited).result)

        val huge = ids.getValue("huge")
        val big = refresh(RefreshScope.Feeds(listOf(huge)), force = true)
        assertEquals(FetchError.LIMIT_EXCEEDED, big.failed[huge])
        assertEquals("BODY_SIZE", diag(huge)!!.errorDetail)

        env.allowHttp.set(false)
        val plain = ids.getValue("plain")
        val http = refresh(RefreshScope.Feeds(listOf(plain)), force = true)
        assertEquals(FeedResult.HTTP_DISABLED, http.perFeed.getValue(plain).result)
        assertEquals(FeedResult.HTTP_DISABLED.name, diag(plain)!!.result)
    }

    /** S14 / N14: new ids → raw count → unique count → insert/update → category list, with duplicates, no date, 2 categories. */
    @Test
    fun s14_entriesFlowFromResponseToCategoryList() = runBlocking<Unit> {
        respond = { _, n, _ ->
            val base = listOf(item("g1", date(1)), item("g2", date(2)), item("g2", date(2)), item("g3"), item("g4", date(3)), item("g5", date(4)))
            ok(rss(if (n == 1) base else base + item("g6", date(0))))
        }
        val ids = import("Tech" to listOf(url("/s")), "News" to listOf(url("/s")))
        val id = ids.getValue("s")
        val first = refresh(RefreshScope.Category(categoryId("Tech")))
        assertEquals(5, first.perFeed.getValue(id).newArticles)
        val d = diag(id)!!
        assertEquals(6, d.rawEntries)
        assertEquals(5, d.uniqueEntries)
        assertEquals(1, d.undatedEntries)
        assertEquals(5, d.insertedArticles)
        assertEquals(0, d.updatedArticles)
        val tech = categoryId("Tech")
        val spec = ArticleQuerySpec(account(), ArticleScope.Category(tech), ReadFilter.ALL, SortOrder.NEWEST, categoryIds = listOf(tech, categoryId("News")))
        assertEquals("each article once in a multi-category list", 5, env.articles.rows(spec).size)

        env.clock.advanceMinutes(5)
        val second = refresh(RefreshScope.Category(categoryId("News")))
        assertEquals(1, second.perFeed.getValue(id).newArticles)
        assertEquals(5, second.perFeed.getValue(id).updatedArticles)
        assertEquals(second.newArticleIds, env.articles.rows(spec).filter { it.title == "g6" }.map { it.id })
        assertEquals(6, env.articles.rows(spec).size)
    }

    /** S15 / N17: queued vs running vs cancelled, failure vs 304, offline/backoff/HTTP visible; no success time when not started. */
    @Test
    fun s15_requestStatesAreDistinguished() = runBlocking<Unit> {
        env.close()
        env = TestEnv(budgetMillis = 600, maxParallel = 1, maxPerHost = 1)
        val gate = CountDownLatch(1)
        respond = { path, _, _ ->
            when (path) {
                "/hang" -> {
                    gate.await(3, TimeUnit.SECONDS)
                    ok(rss(listOf(item("h"))))
                }
                "/fail" -> MockResponse.Builder().code(500).build()
                else -> ok(rss(listOf(item(path))))
            }
        }
        val ids = import("" to listOf(url("/hang"), url("/queued")))
        val account = account()
        val coordinator = RefreshCoordinator(env.backend, { account }, { true }, {}, scope, env.clock)
        val running = scope.async { coordinator.refresh(RefreshScope.All, RefreshReason.MANUAL) }
        waitFor { count("/hang") == 1 }
        val status = coordinator.state.value.runningFor(RefreshScope.All)
        assertNotNull(status)
        val queued = ids.getValue("queued")
        assertNull("queued: no result yet", diag(queued)!!.result)
        assertNotNull(diag(queued)!!.pendingSince)
        val report = running.await()
        gate.countDown()
        val hang = ids.getValue("hang")
        assertEquals(FeedResult.CANCELLED, report.perFeed.getValue(hang).result)
        assertEquals(FeedResult.NOT_STARTED, report.perFeed.getValue(queued).result)
        assertEquals(1, report.cancelled)
        assertEquals(1, report.notStarted)
        assertFalse(report.isComplete)
        assertEquals(FeedResult.NOT_STARTED.name, diag(queued)!!.result)
        assertNull(env.db.subscriptionDao().fetchState(queued)!!.lastSuccessAt)
        assertNull(env.db.subscriptionDao().fetchState(queued)!!.lastAttemptAt)
        assertNotNull("finished request kept for the UI", coordinator.state.value.latestFor(RefreshScope.All)?.report)

        // Failure and 304 are different results; offline is a constraint, not a failure.
        val more = import("" to listOf(url("/fail")))
        val failed = refresh(RefreshScope.Feeds(listOf(more.getValue("fail"))))
        assertEquals(FeedResult.FAILED, failed.perFeed.getValue(more.getValue("fail")).result)
        assertEquals(500, diag(more.getValue("fail"))!!.httpStatus)
        env.networkState = NetworkState(connected = false, metered = false)
        val offline = refresh(RefreshScope.Feeds(listOf(queued)))
        assertEquals(FeedResult.OFFLINE, offline.perFeed.getValue(queued).result)
        env.networkState = NetworkState(connected = true, metered = false)
        val auto = refresh(RefreshScope.Feeds(listOf(more.getValue("fail"))), RefreshReason.PERIODIC)
        assertEquals(FeedResult.BACKOFF, auto.perFeed.getValue(more.getValue("fail")).result)
    }

    /** N12: stopping a worker-only refresh cancels its queued and running fetches; finished feeds stay applied. */
    @Test
    fun n12_stoppingTheWorkerCancelsItsOwnFetches() = runBlocking<Unit> {
        env.close()
        env = TestEnv(maxParallel = 1, maxPerHost = 1)
        val gate = CountDownLatch(1)
        respond = { path, _, _ ->
            if (path == "/b-slow") gate.await(3, TimeUnit.SECONDS)
            ok(rss(listOf(item(path))))
        }
        val ids = import("" to listOf(url("/a-fast"), url("/b-slow"), url("/c-later")))
        val account = account()
        val coordinator = RefreshCoordinator(env.backend, { account }, { true }, {}, scope, env.clock)
        // The worker calls the coordinator in its own coroutine; cancelling it is what WorkManager does on stop.
        val worker = scope.async { coordinator.refresh(RefreshScope.All, RefreshReason.PERIODIC) }
        waitFor { count("/b-slow") == 1 }
        worker.cancelAndJoin()
        gate.countDown()
        Thread.sleep(500)
        assertEquals("queued feed never requested after the stop", 0, count("/c-later"))
        assertEquals(1, env.db.articleDao().articlesOfFeed(ids.getValue("a-fast")).size)
        assertEquals(0, env.db.articleDao().articlesOfFeed(ids.getValue("b-slow")).size)
        assertNotNull(diag(ids.getValue("c-later"))!!.pendingSince)
        assertFalse(coordinator.state.value.running)
    }

    /** N13: a fetch shared by the worker and a manual request survives the worker's stop and serves the manual one. */
    @Test
    fun n13_sharedFetchSurvivesWorkerStopForManualRequest() = runBlocking<Unit> {
        env.close()
        env = TestEnv(maxParallel = 1, maxPerHost = 1)
        val gate = CountDownLatch(1)
        respond = { path, _, _ ->
            if (path == "/a-shared") gate.await(3, TimeUnit.SECONDS)
            ok(rss(listOf(item(path))))
        }
        val ids = import("" to listOf(url("/a-shared"), url("/b-other")))
        val shared = ids.getValue("a-shared")
        val account = account()
        val coordinator = RefreshCoordinator(env.backend, { account }, { true }, {}, scope, env.clock)
        val worker = scope.async { coordinator.refresh(RefreshScope.All, RefreshReason.PERIODIC) }
        waitFor { count("/a-shared") == 1 }
        val manual = scope.async { coordinator.refresh(RefreshScope.Feeds(listOf(shared)), RefreshReason.MANUAL) }
        Thread.sleep(200)
        worker.cancelAndJoin()
        gate.countDown()
        val report = manual.await()
        assertEquals(FeedResult.UPDATED, report.perFeed.getValue(shared).result)
        assertEquals("shared fetch not repeated", 1, count("/a-shared"))
        assertEquals(1, env.db.articleDao().articlesOfFeed(shared).size)
        Thread.sleep(300)
        assertEquals("the worker-only feed was not fetched after the stop", 0, count("/b-other"))
    }

    /** N16: entry count, body size, XML depth and broken XML are told apart; old articles and state stay. */
    @Test
    fun n16_limitKindsAreDistinguishedAndOldDataKept() = runBlocking<Unit> {
        var phase = 1
        respond = { path, _, _ ->
            if (phase == 1) {
                ok(rss(listOf(item("$path-keep"))))
            } else {
                when (path) {
                    "/count" -> ok(rss((0..2000).map { item("c$it") }))
                    "/size" -> ok("<rss version=\"2.0\"><channel><title>" + "b".repeat(6 * 1024 * 1024) + "</title></channel></rss>")
                    "/depth" -> ok("<rss version=\"2.0\"><channel>" + "<x>".repeat(80) + "</x>".repeat(80) + "</channel></rss>")
                    else -> ok("<rss><channel><item>")
                }
            }
        }
        val ids = import("" to listOf(url("/count"), url("/size"), url("/depth"), url("/broken")))
        refresh(RefreshScope.All)
        val keep = env.db.articleDao().articlesOfFeed(ids.getValue("count")).single()
        env.articles.setSaved(keep.id, true)
        phase = 2
        env.clock.advanceMinutes(5)
        val report = refresh(RefreshScope.All)
        assertEquals("ENTRY_COUNT", diag(ids.getValue("count"))!!.errorDetail)
        assertEquals("BODY_SIZE", diag(ids.getValue("size"))!!.errorDetail)
        assertEquals("XML_DEPTH", diag(ids.getValue("depth"))!!.errorDetail)
        assertEquals(FetchError.FORMAT, report.failed[ids.getValue("broken")])
        for (id in ids.values) assertEquals(1, env.db.articleDao().articlesOfFeed(id).size)
        assertTrue(env.db.articleDao().state(keep.id)!!.isSaved)
        assertEquals("previous body counts kept", 1, diag(ids.getValue("count"))!!.uniqueEntries)
    }
}
