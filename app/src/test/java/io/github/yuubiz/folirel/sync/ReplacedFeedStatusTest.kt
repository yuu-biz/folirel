package io.github.yuubiz.folirel.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.FeedResult
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshReport
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.testutil.literalUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * V31-C02: a request that did not fetch a feed because its address changed (REPLACED) is history, not unfinished work.
 * What the home screen must show about that feed is its present state, which the request that follows the change
 * decides: failed, not reached, in progress or fine. Everything runs through the real RefreshCoordinator, LocalBackend,
 * Room and MockWebServer, in a fixed order (latches), with the screen's inputs read from the coordinator state.
 */
@RunWith(AndroidJUnit4::class)
class ReplacedFeedStatusTest {
    private val server = TestEnv.startServer()
    private var env = TestEnv()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val arrived = ConcurrentHashMap<String, CountDownLatch>()
    private val gates = ConcurrentHashMap<String, CountDownLatch>()

    /** What /b.xml answers (null: held until its gate opens, then 200). */
    private val bAnswer = AtomicReference<MockResponse?>(null)

    @After
    fun tearDown() {
        gates.values.forEach { it.countDown() }
        bg.cancel()
        env.close()
        server.close()
    }

    private fun rss(guid: String) =
        """<rss version="2.0"><channel><title>feed $guid</title><item><guid>$guid</guid><title>$guid</title></item></channel></rss>"""

    private fun ok(guid: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(rss(guid)).build()

    private fun hold(path: String) {
        arrived[path] = CountDownLatch(1)
        gates[path] = CountDownLatch(1)
    }

    private fun serve() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                arrived[path]?.countDown()
                gates[path]?.let { assertTrue("$path never released", it.await(30, TimeUnit.SECONDS)) }
                return when (path) {
                    "/a.xml" -> ok("a1")
                    "/b.xml" -> bAnswer.get() ?: ok("b1")
                    "/other.xml" -> MockResponse.Builder().code(500).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
    }

    private fun url(path: String) = server.literalUrl(path).toString()

    private suspend fun add(path: String) =
        (env.subscriptions.addFeed(env.subscriptions.ensureLocalAccount(), url(path)) as AddFeedResult.Added).feedId

    private suspend fun coordinator(): RefreshCoordinator {
        val account = env.subscriptions.ensureLocalAccount()
        return RefreshCoordinator(env.backend, { account }, { true }, {}, bg, env.clock)
    }

    private class Scenario(val coordinator: RefreshCoordinator, val feedId: String, val allRequest: () -> RequestStatus)

    /** Refresh of everything is on A; the user adopts B; the refresh after the change runs (and ends) before A is released. */
    private suspend fun replacedThenFollowUp(c: RefreshCoordinator, feedId: String, followUp: suspend () -> RefreshReport): Scenario {
        hold("/a.xml")
        val all = bg.async { c.refresh(RefreshScope.All, RefreshReason.MANUAL) }
        assertTrue(arrived.getValue("/a.xml").await(10, TimeUnit.SECONDS))
        FeedAddressChange(env.subscriptions, c).adopt(feedId, url("/a.xml"), url("/b.xml"))
        followUp()
        gates.getValue("/a.xml").countDown()
        all.await()
        return Scenario(c, feedId) { c.state.value.latestFor(RefreshScope.All)!! }
    }

    private suspend fun refreshFeed(c: RefreshCoordinator, feedId: String) =
        c.refresh(RefreshScope.Feeds(listOf(feedId)), RefreshReason.MANUAL)

    private fun Scenario.now() = coordinator.state.value.replacedNow(allRequest())

    @Test
    fun theOldRequestIsCleanHistoryWhateverBDoes() = runBlocking<Unit> {
        serve()
        bAnswer.set(MockResponse.Builder().code(500).build())
        val id = add("/a.xml")
        val c = coordinator()
        val s = replacedThenFollowUp(c, id) { refreshFeed(c, id) }
        val report = s.allRequest().report!!
        // The old request says nothing is wrong with it: the feed was replaced, not left unfinished.
        assertEquals(FeedResult.REPLACED, report.perFeed.getValue(id).result)
        assertEquals(0, report.notAttempted)
        assertTrue(report.failed.isEmpty())
        assertTrue("this is what the screen used to read, and it is clean", report.isComplete)
    }

    @Test
    fun aFailureOfTheNewAddressIsAProblemNow() = runBlocking<Unit> {
        serve()
        bAnswer.set(MockResponse.Builder().code(500).build())
        val id = add("/a.xml")
        val c = coordinator()
        val s = replacedThenFollowUp(c, id) { refreshFeed(c, id) }
        assertEquals(ReplacedNow(failed = 1), s.now())
    }

    @Test
    fun aNewAddressThatSucceedsLeavesNothingToShow() = runBlocking<Unit> {
        serve()
        val id = add("/a.xml")
        val c = coordinator()
        val s = replacedThenFollowUp(c, id) { refreshFeed(c, id) }
        assertEquals(ReplacedNow(), s.now())
    }

    @Test
    fun theFailureGoesAwayWhenTheNewAddressLaterWorks() = runBlocking<Unit> {
        serve()
        bAnswer.set(MockResponse.Builder().code(500).build())
        val id = add("/a.xml")
        val c = coordinator()
        val s = replacedThenFollowUp(c, id) { refreshFeed(c, id) }
        assertEquals(1, s.now().failed)
        bAnswer.set(null)
        env.clock.advanceMinutes(30)
        refreshFeed(c, id)
        assertEquals(ReplacedNow(), s.now())
    }

    @Test
    fun aFailureOfAnotherFeedIsNotThisFeedsProblem() = runBlocking<Unit> {
        serve()
        val id = add("/a.xml")
        val other = add("/other.xml")
        val c = coordinator()
        val s = replacedThenFollowUp(c, id) { refreshFeed(c, id) }
        // A later request for the unrelated feed fails; the replaced feed is fine.
        c.refresh(RefreshScope.Feeds(listOf(other)), RefreshReason.MANUAL)
        assertEquals(ReplacedNow(), s.now())
    }

    @Test
    fun aNewAddressThatIsNotReachedInTimeIsUnfinishedNow() = runBlocking<Unit> {
        env.close()
        env = TestEnv(budgetMillis = 700)
        serve()
        hold("/b.xml")
        val id = add("/a.xml")
        val c = coordinator()
        val s = replacedThenFollowUp(c, id) {
            // B is held past the request's time budget: the request ends without a result for the feed.
            refreshFeed(c, id).also { assertTrue(it.perFeed.getValue(id).result.unfinished) }
        }
        assertEquals(ReplacedNow(unreached = 1), s.now())
    }

    @Test
    fun aRequestForTheNewAddressStillRunningIsInProgress() = runBlocking<Unit> {
        serve()
        hold("/b.xml")
        val id = add("/a.xml")
        val c = coordinator()
        hold("/a.xml")
        val all = bg.async { c.refresh(RefreshScope.All, RefreshReason.MANUAL) }
        assertTrue(arrived.getValue("/a.xml").await(10, TimeUnit.SECONDS))
        FeedAddressChange(env.subscriptions, c).adopt(id, url("/a.xml"), url("/b.xml"))
        val running = bg.async { refreshFeed(c, id) }
        assertTrue(arrived.getValue("/b.xml").await(10, TimeUnit.SECONDS))
        gates.getValue("/a.xml").countDown()
        all.await()
        val request = c.state.value.latestFor(RefreshScope.All)!!
        assertEquals(ReplacedNow(inProgress = 1), c.state.value.replacedNow(request))
        gates.getValue("/b.xml").countDown()
        running.await()
        assertEquals(ReplacedNow(), c.state.value.replacedNow(request))
    }
}
