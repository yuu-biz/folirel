package io.github.yuubiz.folirel.backend.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.FeedResult
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshReport
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.data.repository.SubscriptionRepository
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.testutil.literalUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * V3-R01 (review 0.3.0): the fetch URL of a feed changes (user adopts a discovered address, HTTPS migration) while a
 * fetch of the old address is in flight. The late answer of the old address must not write into the feed, and the
 * request for the new address must really fetch the new address instead of joining the old fetch.
 *
 * Order is fixed by latches, never by sleeping: the old address is held until the test has moved the feed, asked for
 * a refresh of the new address and seen that request through.
 */
@RunWith(AndroidJUnit4::class)
class StaleFetchResponseTest {
    private val server = TestEnv.startServer()
    private val env = TestEnv()
    private val requested = CopyOnWriteArrayList<String>()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Counted down when the request for /a.xml arrived at the server (so the test knows it is in flight). */
    private var aArrived = CountDownLatch(1)

    /** /a.xml is answered only after this. */
    private var aRelease = CountDownLatch(0)
    private val aResponse = AtomicReference(rssResponse("a1"))
    private val bResponse = AtomicReference(rssResponse("b1"))

    @After
    fun tearDown() {
        aRelease.countDown()
        bg.cancel()
        env.close()
        server.close()
    }

    private fun rss(guid: String) =
        """<rss version="2.0"><channel><title>feed $guid</title><item><guid>$guid</guid><title>article $guid</title><link>https://site.example/$guid</link></item></channel></rss>"""

    private fun rssResponse(guid: String, etag: String? = null) = MockResponse.Builder().code(200)
        .setHeader("Content-Type", "application/rss+xml").apply { if (etag != null) setHeader("ETag", etag) }.body(rss(guid)).build()

    private fun serve() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requested += path
                return when (path) {
                    "/a.xml" -> {
                        aArrived.countDown()
                        assertTrue("old address was never released", aRelease.await(30, TimeUnit.SECONDS))
                        aResponse.get()
                    }
                    "/b.xml" -> bResponse.get()
                    "/c.xml" -> rssResponse("c1")
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
    }

    private fun a() = server.literalUrl("/a.xml").toString()
    private fun b() = server.literalUrl("/b.xml").toString()

    private suspend fun subscribeA(): String {
        val account = env.subscriptions.ensureLocalAccount()
        return (env.subscriptions.addFeed(account, a()) as AddFeedResult.Added).feedId
    }

    private suspend fun refresh(feedId: String? = null): RefreshReport {
        val account = env.subscriptions.ensureLocalAccount()
        val scope = if (feedId == null) RefreshScope.All else RefreshScope.Feeds(listOf(feedId))
        return env.backend.refresh(account, scope, RefreshReason.MANUAL) {}
    }

    private fun articleKeys(feedId: String) = runBlocking { env.db.articleDao().articlesOfFeed(feedId).map { it.entryKey }.sorted() }

    /**
     * Starts a fetch of A and holds its answer, moves the feed to B through [move], asks for the refresh a user action
     * would trigger and releases A only after that refresh finished. Returns that refresh's report.
     */
    private suspend fun moveWhileAIsInFlight(feedId: String, move: suspend () -> Unit): RefreshReport {
        serve()
        aArrived = CountDownLatch(1)
        aRelease = CountDownLatch(1)
        val first: Deferred<RefreshReport> = bg.async { refresh() }
        assertTrue("fetch of A did not start", aArrived.await(10, TimeUnit.SECONDS))
        move()
        // The refresh that follows the adoption. It must not wait for the stale fetch of A.
        val second = bg.async { refresh(feedId) }
        val report = withTimeoutOrNull(10_000) { second.await() }
        aRelease.countDown()
        first.await()
        if (report == null) {
            second.await()
            throw AssertionError("refresh of the new address waited for the old address")
        }
        return report
    }


    @Test
    fun moveDuringAFetchAndDifferentIdSpellingTogetherKeepEveryArticleOnceWithItsState() = runBlocking<Unit> {
        // R01 × R02: the old address answers late with a changed feed while the new one spells every id differently.
        fun linkOnly(vararg n: Int) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(
            """<rss version="2.0"><channel><title>site</title>""" + n.joinToString("") {
                "<item><title>t$it</title><link>https://site.example/p/$it</link><pubDate>Sat, 0$it Aug 2026 10:00:00 GMT</pubDate></item>"
            } + "</channel></rss>",
        ).build()
        val feedId = subscribeA()
        serve()
        aRelease.countDown()
        aResponse.set(linkOnly(1, 2, 3))
        refresh()
        val before = runBlocking { env.db.articleDao().articlesOfFeed(feedId) }.associateBy { it.linkUrl!!.substringAfterLast('/') }
        env.articles.setRead(before.getValue("1").id, true)
        env.articles.setSaved(before.getValue("2").id, true)

        // The next update of A is in flight and will bring article 4; meanwhile the user adopts B.
        env.clock.advanceMinutes(60)
        aResponse.set(linkOnly(1, 2, 3, 4))
        bResponse.set(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(
                """<rss version="2.0"><channel><title>site</title>""" +
                    "<item><guid>https://site.example/p/1</guid><title>t1</title><link>https://site.example/p/1</link><pubDate>Sat, 01 Aug 2026 10:00:00 GMT</pubDate></item>" +
                    "<item><guid>http://site.example/p/2</guid><title>t2</title><link>https://site.example/p/2</link><pubDate>Sat, 02 Aug 2026 10:00:00 GMT</pubDate></item>" +
                    "<item><guid>https://www.site.example/p/3</guid><title>t3</title><link>https://site.example/p/3</link><pubDate>Sat, 03 Aug 2026 10:00:00 GMT</pubDate></item>" +
                    "</channel></rss>",
            ).build(),
        )
        val report = moveWhileAIsInFlight(feedId) { env.subscriptions.applyUrlMove(feedId, a(), b()) }

        val after = runBlocking { env.db.articleDao().articlesOfFeed(feedId) }
        assertEquals("A's late article 4 is not applied; B's spellings are the old articles", 3, after.size)
        assertEquals(before.values.map { it.id }.toSet(), after.map { it.id }.toSet())
        assertEquals(0, report.newArticles)
        assertEquals(true, runBlocking { env.db.articleDao().state(before.getValue("1").id)!!.isRead })
        assertEquals(true, runBlocking { env.db.articleDao().state(before.getValue("2").id)!!.isSaved })
        assertEquals(b(), env.db.subscriptionDao().feed(feedId)!!.fetchUrl)
    }

    @Test
    fun oldAddressSuccessDoesNotOverwriteNewAddress() = runBlocking<Unit> {
        val feedId = subscribeA()
        env.clock.advanceMinutes(5)
        val report = moveWhileAIsInFlight(feedId) { assertEquals(SubscriptionRepository.MoveResult.Applied, env.subscriptions.applyUrlMove(feedId, a(), b())) }

        assertTrue("the new address was requested", "/b.xml" in requested)
        assertEquals(FeedResult.UPDATED, report.perFeed.getValue(feedId).result)
        assertEquals(b(), env.db.subscriptionDao().feed(feedId)!!.fetchUrl)
        assertEquals("only what the new address served", listOf("id:b1"), articleKeys(feedId))
        assertEquals("b1 feed title, not a1", "feed b1", env.db.subscriptionDao().feed(feedId)!!.feedTitle)
    }

    @Test
    fun oldAddressPermanentRedirectDoesNotMoveTheFeedAway() = runBlocking<Unit> {
        val feedId = subscribeA()
        aResponse.set(MockResponse.Builder().code(308).setHeader("Location", "/c.xml").build())
        moveWhileAIsInFlight(feedId) { env.subscriptions.applyUrlMove(feedId, a(), b()) }

        assertEquals("the user's choice B stays", b(), env.db.subscriptionDao().feed(feedId)!!.fetchUrl)
        assertEquals(listOf("id:b1"), articleKeys(feedId))
    }

    @Test
    fun oldAddressFailureDoesNotPutBackoffOnNewAddress() = runBlocking<Unit> {
        val feedId = subscribeA()
        aResponse.set(MockResponse.Builder().code(429).setHeader("Retry-After", "86400").build())
        moveWhileAIsInFlight(feedId) { env.subscriptions.applyUrlMove(feedId, a(), b()) }

        val state = env.db.subscriptionDao().fetchState(feedId)!!
        assertNull("B succeeded; A's 429 is not B's error", state.lastErrorCode)
        assertEquals(0, state.failureCount)
        assertNull(state.nextEligibleAt)
        assertNull("no server wait carried over from A", state.retryAfterUntil)
        assertEquals(listOf("id:b1"), articleKeys(feedId))
    }

    @Test
    fun oldAddressNotModifiedDoesNotClearNewAddressFailure() = runBlocking<Unit> {
        val feedId = subscribeA()
        // A was fetched before and has validators, so the next fetch of A is conditional.
        serve()
        aRelease.countDown()
        aResponse.set(rssResponse("a1", etag = "\"a-v1\""))
        refresh()
        assertEquals("\"a-v1\"", env.db.subscriptionDao().fetchState(feedId)!!.etag)
        requested.clear()
        env.clock.advanceMinutes(120)
        aResponse.set(MockResponse.Builder().code(304).build())
        bResponse.set(MockResponse.Builder().code(500).build())
        moveWhileAIsInFlight(feedId) { env.subscriptions.applyUrlMove(feedId, a(), b()) }

        assertTrue("/b.xml" in requested)
        val state = env.db.subscriptionDao().fetchState(feedId)!!
        assertEquals("B's own failure stands", FetchError.SERVER.name, state.lastErrorCode)
        assertEquals(1, state.failureCount)
        assertNotNull(state.nextEligibleAt)
    }

    @Test
    fun httpsUpgradeOfAnInFlightFeedFetchesTheNewAddress() = runBlocking<Unit> {
        val feedId = subscribeA()
        moveWhileAIsInFlight(feedId) {
            // The same repository call the HTTPS migration uses; the test server only speaks one scheme.
            assertTrue(env.subscriptions.applyHttpsUpgrade(feedId, a(), b()))
        }
        assertTrue("/b.xml" in requested)
        assertEquals(b(), env.db.subscriptionDao().feed(feedId)!!.fetchUrl)
        assertEquals(listOf("id:b1"), articleKeys(feedId))
    }

    @Test
    fun unsubscribeAndResubscribeWhileFetchingKeepsTheSameAddressFetch() = runBlocking<Unit> {
        val feedId = subscribeA()
        serve()
        aArrived = CountDownLatch(1)
        aRelease = CountDownLatch(1)
        val first = bg.async { refresh() }
        assertTrue(aArrived.await(10, TimeUnit.SECONDS))
        env.subscriptions.unsubscribe(feedId)
        env.subscriptions.resubscribe(feedId)
        // Same address: joining the running fetch is right, and its answer belongs to this feed.
        val second = bg.async { refresh(feedId) }
        aRelease.countDown()
        first.await()
        assertEquals(FeedResult.UPDATED, second.await().perFeed.getValue(feedId).result)
        assertEquals(listOf("id:a1"), articleKeys(feedId))
    }

    @Test
    fun forcedFollowUpOfTheOldAddressIsNotLeftWaitingForever() = runBlocking<Unit> {
        // A running fetch of A with a forced follow-up queued behind it; then the feed moves to B.
        val feedId = subscribeA()
        serve()
        aArrived = CountDownLatch(1)
        aRelease = CountDownLatch(1)
        val first = bg.async { refresh() }
        assertTrue(aArrived.await(10, TimeUnit.SECONDS))
        val account = env.subscriptions.ensureLocalAccount()
        val forced = bg.async { env.backend.refresh(account, RefreshScope.Feeds(listOf(feedId)), RefreshReason.MANUAL, force = true) {} }
        // The forced request is registered as the follow-up of the running fetch once the old job has it as owner.
        // Waiting for that is not observable from outside, so the move may happen before or after; both orders must end.
        env.subscriptions.applyUrlMove(feedId, a(), b())
        val moved = bg.async { refresh(feedId) }
        assertEquals(FeedResult.UPDATED, withTimeoutOrNull(10_000) { moved.await() }?.perFeed?.get(feedId)?.result)
        aRelease.countDown()
        first.await()
        assertNotNull("forced request ended", withTimeoutOrNull(10_000) { forced.await() })
        assertEquals(b(), env.db.subscriptionDao().feed(feedId)!!.fetchUrl)
        assertEquals(listOf("id:b1"), articleKeys(feedId))
    }

    @Test
    fun movingBackAndForthKeepsWhateverAddressIsCurrent() = runBlocking<Unit> {
        // A → B → A while A is in flight: an answer from A is again an answer for the current address.
        val feedId = subscribeA()
        serve()
        aArrived = CountDownLatch(1)
        aRelease = CountDownLatch(1)
        val first = bg.async { refresh() }
        assertTrue(aArrived.await(10, TimeUnit.SECONDS))
        env.subscriptions.applyUrlMove(feedId, a(), b())
        env.subscriptions.applyUrlMove(feedId, b(), a())
        aRelease.countDown()
        first.await()
        assertEquals(a(), env.db.subscriptionDao().feed(feedId)!!.fetchUrl)
        assertEquals(listOf("id:a1"), articleKeys(feedId))
    }
}
