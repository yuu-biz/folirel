package io.github.yuubiz.feeddeck.backend.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlParser
import io.github.yuubiz.feeddeck.data.repository.RetryPolicy
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.network.NetworkState
import io.github.yuubiz.feeddeck.sync.RefreshCoordinator
import io.github.yuubiz.feeddeck.testutil.Fixtures
import io.github.yuubiz.feeddeck.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** T20 conditional GET, T21 redirects, T22 status/backoff, T23 partial failure/budget, T24 concurrency. */
@RunWith(AndroidJUnit4::class)
class RefreshBehaviorTest {
    private val server: MockWebServer = TestEnv.startServer()
    private var env = TestEnv()

    @After
    fun tearDown() {
        env.close()
        server.close()
    }

    private fun rss(guid: String = "g1", title: String = "t") =
        """<rss version="2.0"><channel><title>f</title><item><guid>$guid</guid><title>$title</title></item></channel></rss>"""

    private fun ok(body: String = rss()) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(body).build()

    private suspend fun subscribe(vararg urls: String): List<String> {
        val opml = buildString {
            append("<opml version=\"2.0\"><body>")
            urls.forEach { append("<outline text=\"f\" xmlUrl=\"$it\"/>") }
            append("</body></opml>")
        }
        return env.subscriptions.commitImport(env.subscriptions.ensureLocalAccount(), OpmlParser.parse(opml.toByteArray())).feedIdsToFetch
    }

    private suspend fun refresh(reason: RefreshReason = RefreshReason.MANUAL, scope: RefreshScope = RefreshScope.All) =
        env.backend.refresh(env.subscriptions.ensureLocalAccount(), scope, reason) {}

    private val state get() = env.db.subscriptionDao()

    // T20

    @Test
    fun conditionalGetSendsValidatorsAndHandles304() = runBlocking {
        val url = server.url("/feed.xml").toString()
        server.enqueue(ok().newBuilder().setHeader("ETag", "\"v1\"").setHeader("Last-Modified", "Sat, 03 Oct 2026 00:00:00 GMT").build())
        server.enqueue(MockResponse.Builder().code(304).build())
        val feedId = subscribe(url).single()
        refresh()
        val first = server.takeRequest()
        assertNull(first.headers["If-None-Match"])
        val afterFirst = state.fetchState(feedId)!!
        assertEquals("\"v1\"", afterFirst.etag)
        assertEquals(url, afterFirst.validatorUrl)
        assertTrue(afterFirst.initialized)

        env.clock.advanceMinutes(60)
        val report = refresh()
        val second = server.takeRequest()
        assertEquals("\"v1\"", second.headers["If-None-Match"])
        assertEquals("Sat, 03 Oct 2026 00:00:00 GMT", second.headers["If-Modified-Since"])
        assertEquals(1, report.notModified)
        assertEquals(1, env.db.articleDao().articlesOfFeed(feedId).size)
        assertEquals(env.clock.millis(), state.fetchState(feedId)!!.lastSuccessAt)
    }

    @Test
    fun first304IsRetriedOnceUnconditionally() = runBlocking {
        server.enqueue(MockResponse.Builder().code(304).build())
        server.enqueue(ok())
        val feedId = subscribe(server.url("/a.xml").toString()).single()
        val report = refresh()
        assertEquals(1, report.updated)
        assertEquals(2, server.requestCount)
        assertTrue(state.fetchState(feedId)!!.initialized)

        // Twice 304 on a first fetch is abnormal.
        server.enqueue(MockResponse.Builder().code(304).build())
        server.enqueue(MockResponse.Builder().code(304).build())
        val other = subscribe(server.url("/b.xml").toString()).single()
        val second = refresh(scope = RefreshScope.Feeds(listOf(other)))
        assertEquals(FetchError.UNEXPECTED_NOT_MODIFIED, second.failed[other])
        assertFalse(state.fetchState(other)!!.initialized)
    }

    @Test
    fun validatorsAreNotUpdatedWhenStoringFails() = runBlocking {
        server.enqueue(ok().newBuilder().setHeader("ETag", "\"v1\"").build())
        server.enqueue(MockResponse.Builder().code(200).setHeader("ETag", "\"v2\"").body("<rss><channel><item></channel></rss>").build())
        val feedId = subscribe(server.url("/f.xml").toString()).single()
        refresh()
        env.clock.advanceMinutes(30)
        refresh()
        assertEquals("\"v1\"", state.fetchState(feedId)!!.etag)
    }

    // T21

    @Test
    fun permanentRedirectUpdatesFetchUrlButNotSubscriptionUrl() = runBlocking {
        val a = server.url("/a.xml").toString()
        val b = server.url("/b.xml").toString()
        server.enqueue(MockResponse.Builder().code(301).setHeader("Location", "/b.xml").build())
        server.enqueue(ok().newBuilder().setHeader("ETag", "\"vb\"").build())
        val feedId = subscribe(a).single()
        refresh()
        val feed = state.feed(feedId)!!
        assertEquals(a, feed.subscriptionUrl)
        assertEquals(b, feed.fetchUrl)
        assertEquals(b, state.fetchState(feedId)!!.validatorUrl)

        server.takeRequest()
        server.takeRequest()
        server.enqueue(MockResponse.Builder().code(304).build())
        env.clock.advanceMinutes(30)
        refresh()
        val third = server.takeRequest()
        assertEquals("/b.xml", third.url.encodedPath)
        assertEquals("\"vb\"", third.headers["If-None-Match"])
    }

    @Test
    fun temporaryRedirectKeepsFetchUrlAndOldValidatorsDoNotLeak() = runBlocking {
        val a = server.url("/a.xml").toString()
        server.enqueue(ok().newBuilder().setHeader("ETag", "\"va\"").build())
        val feedId = subscribe(a).single()
        refresh()
        server.takeRequest()

        // a → 302 → b: validators of a are sent to a only, never to b.
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/b.xml").build())
        server.enqueue(ok().newBuilder().setHeader("ETag", "\"vb\"").build())
        env.clock.advanceMinutes(30)
        refresh()
        val toA = server.takeRequest()
        val toB = server.takeRequest()
        assertEquals("\"va\"", toA.headers["If-None-Match"])
        assertEquals("/b.xml", toB.url.encodedPath)
        assertNull(toB.headers["If-None-Match"])
        assertEquals(a, state.feed(feedId)!!.fetchUrl)
    }

    @Test
    fun permanentThenTemporaryUsesOnlyThePermanentPart() = runBlocking {
        server.enqueue(MockResponse.Builder().code(308).setHeader("Location", "/b.xml").build())
        server.enqueue(MockResponse.Builder().code(307).setHeader("Location", "/c.xml").build())
        server.enqueue(ok())
        val feedId = subscribe(server.url("/a.xml").toString()).single()
        refresh()
        assertEquals(server.url("/b.xml").toString(), state.feed(feedId)!!.fetchUrl)
    }

    @Test
    fun redirectLoopAndHopLimit() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/loop-a" -> MockResponse.Builder().code(302).setHeader("Location", "/loop-b").build()
                    path == "/loop-b" -> MockResponse.Builder().code(302).setHeader("Location", "/loop-a").build()
                    path.startsWith("/hop") -> {
                        val n = path.removePrefix("/hop").toInt()
                        val limit = request.url.queryParameter("limit")!!.toInt()
                        if (n < limit) MockResponse.Builder().code(301).setHeader("Location", "/hop${n + 1}?limit=$limit").build() else ok()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val loop = subscribe(server.url("/loop-a").toString()).single()
        val five = subscribe(server.url("/hop0?limit=5").toString()).single()
        val six = subscribe(server.url("/hop0?limit=6").toString()).single()
        val report = refresh()
        assertEquals(FetchError.REDIRECT_LOOP, report.failed[loop])
        assertFalse(five in report.failed)
        assertEquals(FetchError.TOO_MANY_REDIRECTS, report.failed[six])
        assertEquals(server.url("/hop5?limit=5").toString(), state.feed(five)!!.fetchUrl)
    }

    // T22

    @Test
    fun statusCodesAreClassified() = runBlocking {
        val codes = mapOf(401 to FetchError.ACCESS_DENIED, 403 to FetchError.ACCESS_DENIED, 404 to FetchError.NOT_FOUND, 410 to FetchError.GONE, 500 to FetchError.SERVER, 503 to FetchError.SERVER, 418 to FetchError.HTTP_STATUS)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(request.url.encodedPath.removePrefix("/").toInt()).build()
        }
        val ids = codes.keys.associateWith { subscribe(server.url("/$it").toString()).single() }
        val report = refresh()
        for ((code, error) in codes) assertEquals("HTTP $code", error, report.failed[ids.getValue(code)])
        // Nothing is deleted or unsubscribed automatically.
        assertTrue(ids.values.all { state.feed(it)!!.isSubscribed })
    }

    @Test
    fun retryAfterAndBackoffWithFixedClock() = runBlocking {
        val now = env.clock.millis()
        val httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(env.clock.instant().plusSeconds(600).atOffset(ZoneOffset.UTC))
        val past = DateTimeFormatter.RFC_1123_DATE_TIME.format(env.clock.instant().minusSeconds(600).atOffset(ZoneOffset.UTC))
        val cases = linkedMapOf(
            "/s120" to MockResponse.Builder().code(429).setHeader("Retry-After", "120").build(),
            "/date" to MockResponse.Builder().code(429).setHeader("Retry-After", httpDate).build(),
            "/bad" to MockResponse.Builder().code(429).setHeader("Retry-After", "soon").build(),
            "/none" to MockResponse.Builder().code(429).build(),
            "/past" to MockResponse.Builder().code(429).setHeader("Retry-After", past).build(),
            "/huge" to MockResponse.Builder().code(429).setHeader("Retry-After", "999999999999").build(),
            "/500" to MockResponse.Builder().code(500).build(),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = cases.getValue(request.url.encodedPath)
        }
        val ids = cases.keys.associateWith { subscribe(server.url(it).toString()).single() }
        refresh()
        suspend fun next(path: String) = state.fetchState(ids.getValue(path))!!.nextEligibleAt!! - now
        assertEquals(120_000L, next("/s120"))
        assertEquals(600_000L, next("/date"))
        assertEquals(RetryPolicy.MIN_DELAY_MS, next("/bad"))
        assertEquals(RetryPolicy.MIN_DELAY_MS, next("/none"))
        assertEquals(RetryPolicy.MIN_DELAY_MS, next("/past"))
        assertEquals(RetryPolicy.MAX_DELAY_MS, next("/huge"))
        assertEquals(RetryPolicy.MIN_DELAY_MS, next("/500"))

        // Automatic refresh respects every wait; manual refresh may retry 5xx but never before Retry-After.
        val before = server.requestCount
        val auto = refresh(RefreshReason.PERIODIC)
        assertEquals(cases.size, auto.deferredBackoff)
        assertEquals(before, server.requestCount)
        val manual = refresh(RefreshReason.MANUAL)
        assertEquals(6, manual.deferredBackoff)
        assertEquals(before + 1, server.requestCount)
        // Exponential backoff for repeated failures, capped at 24 h.
        assertEquals(2 * RetryPolicy.MIN_DELAY_MS, state.fetchState(ids.getValue("/500"))!!.nextEligibleAt!! - now)
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayMillis(FetchError.SERVER, 30, null))
    }

    @Test
    fun timeoutIsClassified() = runBlocking {
        val fast = env.httpClient.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).callTimeout(500, TimeUnit.MILLISECONDS).build()
        server.enqueue(ok().newBuilder().headersDelay(2, TimeUnit.SECONDS).build())
        val outcome = FeedFetcher(fast, env.clock).fetch(server.url("/slow").toString(), null, initialized = false)
        assertEquals(FetchOutcome.Failure(FetchError.TIMEOUT), outcome)
    }

    // T23

    @Test
    fun partialFailureKeepsOtherFeedsAndOldArticles() = runBlocking {
        val good = server.url("/good").toString()
        val flaky = server.url("/flaky").toString()
        val broken = server.url("/broken").toString()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = calls.getOrPut(request.url.encodedPath) { AtomicInteger() }.incrementAndGet()
                return when (request.url.encodedPath) {
                    "/good" -> ok(rss("good-$n"))
                    "/flaky" -> if (n == 1) ok(rss("flaky-1")) else MockResponse.Builder().code(503).build()
                    else -> MockResponse.Builder().body("<rss><channel>").build()
                }
            }
        }
        val (goodId, flakyId, brokenId) = subscribe(good, flaky, broken)
        refresh()
        env.clock.advanceMinutes(60)
        val report = refresh()
        assertEquals(1, report.updated)
        assertEquals(mapOf(flakyId to FetchError.SERVER, brokenId to FetchError.FORMAT), report.failed)
        assertFalse(report.isComplete)
        assertEquals(2, env.db.articleDao().articlesOfFeed(goodId).size)
        assertEquals(1, env.db.articleDao().articlesOfFeed(flakyId).size) // previous data kept
    }

    @Test
    fun budgetExpiryReportsNotAttemptedAndNextRefreshContinues() = runBlocking {
        env.close()
        env = TestEnv(budgetMillis = 300)
        server.enqueue(ok().newBuilder().headersDelay(2, TimeUnit.SECONDS).build())
        server.enqueue(ok())
        val feedId = subscribe(server.url("/slow").toString()).single()
        val first = refresh()
        assertEquals(1, first.notAttempted)
        assertFalse(first.isComplete)
        assertEquals(0, first.updated)
        // The cancelled fetch must not leave a stale in-flight entry behind.
        val second = refresh()
        assertEquals(1, second.updated)
        assertTrue(state.fetchState(feedId)!!.initialized)
    }

    @Test
    fun responseLimitsAreEnforcedAfterDecompression() = runBlocking {
        val huge = Buffer()
        GZIPOutputStream(huge.outputStream()).use { gz ->
            gz.write("<rss version=\"2.0\"><channel><title>".toByteArray())
            val chunk = ByteArray(1024 * 1024) { 'a'.code.toByte() }
            repeat(6) { gz.write(chunk) }
            gz.write("</title></channel></rss>".toByteArray())
        }
        assertTrue("compressed body is small", huge.size < 100_000)
        server.enqueue(MockResponse.Builder().setHeader("Content-Encoding", "gzip").body(huge).build())
        val many = buildString {
            append("<rss version=\"2.0\"><channel><title>t</title>")
            repeat(2001) { append("<item><guid>$it</guid></item>") }
            append("</channel></rss>")
        }
        server.enqueue(ok(many))
        val (bomb, tooMany) = subscribe(server.url("/bomb").toString(), server.url("/many").toString())
        val report = refresh()
        assertEquals(FetchError.LIMIT_EXCEEDED, report.failed[bomb])
        assertEquals(FetchError.LIMIT_EXCEEDED, report.failed[tooMany])
        assertEquals(0, env.db.articleDao().countArticles(env.subscriptions.ensureLocalAccount()))
    }

    @Test
    fun offlineDefersAllFeedsWithoutNetworkCalls() = runBlocking {
        subscribe(server.url("/a").toString(), server.url("/b").toString())
        env.networkState = NetworkState(connected = false, metered = true)
        val report = refresh()
        assertEquals(2, report.deferredConstraint)
        assertTrue(report.offline)
        assertFalse(report.isComplete)
        assertEquals(0, server.requestCount)
    }

    // T24

    @Test
    fun concurrencyLimitsGlobalFourAndPerHostTwo() = runBlocking {
        env.close()
        env = TestEnv(resolver = Dns { host -> if (host.endsWith(".test")) listOf(InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(host) })
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val perHost = ConcurrentHashMap<String, AtomicInteger>()
        val maxPerHost = ConcurrentHashMap<String, Int>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val host = request.headers["Host"]!!.substringBefore(':')
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                val h = perHost.getOrPut(host) { AtomicInteger() }.incrementAndGet()
                maxPerHost.merge(host, h) { a, b -> maxOf(a, b) }
                Thread.sleep(150)
                perHost.getValue(host).decrementAndGet()
                active.decrementAndGet()
                return ok(rss(request.url.encodedPath))
            }
        }
        val port = server.port
        val urls = (0 until 12).map { "http://h${it % 3}.test:$port/f$it.xml" }
        subscribe(*urls.toTypedArray())
        val report = refresh()
        assertEquals(12, report.updated)
        assertTrue("global max ${maxActive.get()}", maxActive.get() <= 4)
        assertTrue("per-host max $maxPerHost", maxPerHost.values.all { it <= 2 })
        assertTrue("some parallelism expected", maxActive.get() >= 2)
    }

    @Test
    fun overlappingRefreshesFetchEachFeedOnce() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(200)
                return ok(rss(request.url.encodedPath))
            }
        }
        val ids = subscribe(server.url("/1").toString(), server.url("/2").toString(), server.url("/3").toString())
        val account = env.subscriptions.ensureLocalAccount()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = RefreshCoordinator(env.backend, { account }, { true }, {}, scope, env.clock)
        val reports = listOf(
            scope.async { coordinator.refresh(RefreshScope.All, RefreshReason.MANUAL) },
            scope.async { coordinator.refresh(RefreshScope.All, RefreshReason.MANUAL) },
            scope.async { env.backend.refresh(account, RefreshScope.Feeds(ids.take(2)), RefreshReason.MANUAL) {} },
        ).awaitAll()
        assertEquals(3, server.requestCount)
        assertEquals(3, reports[0].updated)
        assertEquals(reports[0], reports[1]) // joined, same job
        scope.cancel()
    }

    @Test
    fun unsubscribeAndManualStateDuringFetchAreNotOverwritten() = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/slow") gate.await(5, TimeUnit.SECONDS)
                return ok(rss("x", "updated title"))
            }
        }
        val (keepId, dropId) = subscribe(server.url("/fast").toString(), server.url("/slow").toString())
        val account = env.subscriptions.ensureLocalAccount()
        refresh(scope = RefreshScope.Feeds(listOf(keepId)))
        val article = env.db.articleDao().articlesOfFeed(keepId).single()
        env.articles.setRead(article.id, true)
        env.articles.setSaved(article.id, true)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = scope.async { env.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {} }
        Thread.sleep(300)
        env.subscriptions.unsubscribe(dropId) // while /slow is in flight
        gate.countDown()
        val report = running.await()
        assertEquals(1, report.skippedInactive)
        assertFalse(state.feed(dropId)!!.isSubscribed)
        assertEquals(0, env.db.articleDao().articlesOfFeed(dropId).size)
        val stateAfter = env.db.articleDao().state(article.id)!!
        assertTrue(stateAfter.isRead && stateAfter.isSaved)
        scope.cancel()
    }

    @Test
    fun doctypeResponseIsRejectedWithoutFollowingEntities() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(Fixtures.bytes("feeds/rss2-xxe.xml"))).build())
        val feedId = subscribe(server.url("/xxe").toString()).single()
        val report = refresh()
        assertEquals(FetchError.SECURITY, report.failed[feedId])
        assertEquals(1, server.requestCount)
    }
}
