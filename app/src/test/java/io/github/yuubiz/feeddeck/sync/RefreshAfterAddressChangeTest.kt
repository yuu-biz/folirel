package io.github.yuubiz.feeddeck.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.feeddeck.backend.FeedResult
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshReport
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.data.repository.AddFeedResult
import io.github.yuubiz.feeddeck.data.repository.SubscriptionRepository
import io.github.yuubiz.feeddeck.testutil.TestEnv
import io.github.yuubiz.feeddeck.testutil.literalUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * V31-R01: the path the screen takes (RefreshCoordinator, LocalBackend, Room, HTTP) when the address of a feed is
 * changed while a request for it is in flight. The refresh that follows the change must really fetch the new
 * address (a request MockWebServer receives), whichever kind of request was running, and plain repeated taps with
 * an unchanged address must still share one fetch. Order is fixed with latches, not sleeps.
 */
@RunWith(AndroidJUnit4::class)
class RefreshAfterAddressChangeTest {
    private val server = TestEnv.startServer()
    private val env = TestEnv()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requested = CopyOnWriteArrayList<String>()

    /** Per path: counted down when a request arrived, and the response is held until the gate is opened. */
    private val arrived = ConcurrentHashMap<String, CountDownLatch>()
    private val gates = ConcurrentHashMap<String, CountDownLatch>()

    @After
    fun tearDown() {
        gates.values.forEach { it.countDown() }
        bg.cancel()
        env.close()
        server.close()
    }

    private fun rss(guid: String) =
        """<rss version="2.0"><channel><title>feed $guid</title><item><guid>$guid</guid><title>article $guid</title><link>https://site.example/$guid</link></item></channel></rss>"""

    private fun hold(path: String) {
        arrived[path] = CountDownLatch(1)
        gates[path] = CountDownLatch(1)
    }

    private fun release(path: String) = gates[path]?.countDown()

    init {
        val bodies = mapOf("/a.xml" to rss("a1"), "/b.xml" to rss("b1"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requested += path
                arrived[path]?.countDown()
                gates[path]?.let { assertTrue("$path was never released", it.await(30, TimeUnit.SECONDS)) }
                val body = bodies[path] ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(body).build()
            }
        }
    }

    private fun url(path: String) = server.literalUrl(path).toString()

    private suspend fun coordinator(): RefreshCoordinator {
        val account = env.subscriptions.ensureLocalAccount()
        return RefreshCoordinator(env.backend, { account }, { true }, {}, bg, env.clock)
    }

    private suspend fun subscribeA(): String =
        (env.subscriptions.addFeed(env.subscriptions.ensureLocalAccount(), url("/a.xml")) as AddFeedResult.Added).feedId

    private fun articleKeys(feedId: String) = runBlocking { env.db.articleDao().articlesOfFeed(feedId).map { it.entryKey }.sorted() }

    private fun await(path: String) = assertTrue("request for $path did not arrive", arrived.getValue(path).await(10, TimeUnit.SECONDS))

    /**
     * The sequence of the screen: [first] is running and held on A; the user adopts B (change, then refresh, as
     * SubscriptionsViewModel.applyMove does); A is released only after that refresh ended.
     * Returns the refresh after the change and the report of the earlier request.
     */
    private suspend fun adoptWhile(
        coordinator: RefreshCoordinator,
        feedId: String,
        followUpForce: Boolean = false,
        first: () -> Deferred<RefreshReport>,
    ): Pair<RefreshReport, RefreshReport> {
        hold("/a.xml")
        val running = first()
        await("/a.xml")
        val change = FeedAddressChange(env.subscriptions, coordinator)
        assertEquals(SubscriptionRepository.MoveResult.Applied, change.adopt(feedId, url("/a.xml"), url("/b.xml")))
        val followUp = bg.async { coordinator.refresh(RefreshScope.Feeds(listOf(feedId)), RefreshReason.MANUAL, followUpForce) }
        val report = withTimeoutOrNull(10_000) { followUp.await() }
        release("/a.xml")
        val old = running.await()
        if (report == null) {
            followUp.await()
            throw AssertionError("the refresh after the address change was merged into the request for the old address")
        }
        return report to old
    }

    private fun feedsRefresh(c: RefreshCoordinator, id: String, force: Boolean) =
        bg.async { c.refresh(RefreshScope.Feeds(listOf(id)), RefreshReason.MANUAL, force) }

    private fun assertFetchedB(feedId: String, report: RefreshReport) {
        assertTrue("the new address was requested", "/b.xml" in requested)
        assertEquals(FeedResult.UPDATED, report.perFeed.getValue(feedId).result)
        assertEquals(url("/b.xml"), runBlocking { env.db.subscriptionDao().feed(feedId)!!.fetchUrl })
        assertEquals(listOf("id:b1"), articleKeys(feedId))
    }

    @Test
    fun plainRefreshInFlightThenAddressChange() = runBlocking<Unit> {
        val id = subscribeA()
        val c = coordinator()
        val (report, _) = adoptWhile(c, id) { feedsRefresh(c, id, force = false) }
        assertFetchedB(id, report)
    }

    @Test
    fun forcedRefreshInFlightThenAddressChange() = runBlocking<Unit> {
        val id = subscribeA()
        val c = coordinator()
        val (report, _) = adoptWhile(c, id) { feedsRefresh(c, id, force = true) }
        assertFetchedB(id, report)
    }

    @Test
    fun forcedRefreshInFlightThenForcedRefreshAfterTheChange() = runBlocking<Unit> {
        // The same request kind on both sides (same target, manual, force): the case a force-only workaround would miss.
        val id = subscribeA()
        val c = coordinator()
        val (report, _) = adoptWhile(c, id, followUpForce = true) { feedsRefresh(c, id, force = true) }
        assertFetchedB(id, report)
    }

    @Test
    fun refreshOfEverythingInFlightThenAddressChange() = runBlocking<Unit> {
        val id = subscribeA()
        val c = coordinator()
        val (report, old) = adoptWhile(c, id) { bg.async { c.refresh(RefreshScope.All, RefreshReason.MANUAL) } }
        assertFetchedB(id, report)
        // V31-R02: the old request did not fetch this feed itself; it says so as history, not as unfinished work.
        assertEquals(FeedResult.REPLACED, old.perFeed.getValue(id).result)
        assertEquals(0, old.notAttempted)
        assertEquals(0, old.continuing)
        // What the screen shows for "all" afterwards comes from this stored report: nothing unfinished, and the feed itself
        // is current (the follow-up succeeded) - no leftover "not reached" for it.
        val shown = c.state.value.latestFor(RefreshScope.All)!!.report!!
        assertEquals(1, shown.replaced)
        assertEquals(0, shown.notAttempted)
        assertTrue(shown.isComplete)
        val fetchState = runBlocking { env.db.subscriptionDao().fetchState(id)!! }
        assertTrue(fetchState.lastSuccessAt != null && fetchState.lastErrorCode == null)
    }

    @Test
    fun refreshOfEverythingAfterTheChangeAlsoFetchesTheNewAddress() = runBlocking<Unit> {
        val id = subscribeA()
        val c = coordinator()
        hold("/a.xml")
        val running = bg.async { c.refresh(RefreshScope.All, RefreshReason.MANUAL) }
        await("/a.xml")
        FeedAddressChange(env.subscriptions, c).adopt(id, url("/a.xml"), url("/b.xml"))
        // The user taps "refresh all" again: the same request as the running one, but not for the same addresses.
        val again = bg.async { c.refresh(RefreshScope.All, RefreshReason.MANUAL) }
        val report = withTimeoutOrNull(10_000) { again.await() }
        release("/a.xml")
        running.await()
        assertTrue("refresh all after the change was merged into the old one", report != null)
        assertFetchedB(id, report!!)
    }

    @Test
    fun repeatedTapsWithTheSameAddressStillShareOneFetch() = runBlocking<Unit> {
        val id = subscribeA()
        val c = coordinator()
        hold("/a.xml")
        val first = feedsRefresh(c, id, force = false)
        await("/a.xml")
        val second = feedsRefresh(c, id, force = false)
        val third = feedsRefresh(c, id, force = false)
        release("/a.xml")
        val reports = listOf(first, second, third).map { it.await() }
        assertEquals(1, requested.count { it == "/a.xml" })
        assertTrue(reports.all { it.perFeed.getValue(id).result == FeedResult.UPDATED })
    }

    @Test
    fun theOldRequestEndingDoesNotUnregisterTheNewOne() = runBlocking<Unit> {
        val id = subscribeA()
        val c = coordinator()
        hold("/a.xml")
        val old = feedsRefresh(c, id, force = false)
        await("/a.xml")
        FeedAddressChange(env.subscriptions, c).adopt(id, url("/a.xml"), url("/b.xml"))
        hold("/b.xml")
        val second = feedsRefresh(c, id, force = false)
        await("/b.xml")
        // The old request ends now; a third identical request must still join the second, not start another fetch.
        release("/a.xml")
        old.await()
        val third = feedsRefresh(c, id, force = false)
        release("/b.xml")
        val reports = listOf(second, third).map { withTimeoutOrNull(10_000) { it.await() } }
        assertTrue(reports.all { it != null })
        assertEquals("one fetch of the new address for both", 1, requested.count { it == "/b.xml" })
        assertEquals(listOf("id:b1"), articleKeys(id))
    }
}
