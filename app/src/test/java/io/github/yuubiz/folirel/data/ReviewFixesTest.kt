package io.github.yuubiz.folirel.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.FeedResult
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.backend.local.FeedFetcher
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.data.repository.ApplyResult
import io.github.yuubiz.folirel.data.repository.AttemptInfo
import io.github.yuubiz.folirel.data.repository.BodyStats
import io.github.yuubiz.folirel.data.repository.FetchMetadata
import io.github.yuubiz.folirel.data.repository.RetentionManager
import io.github.yuubiz.folirel.data.repository.RetryPolicy
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Review 0.2.0 follow-ups: F01 pruned scheme variant, F02 import preview/commit agreement, F03 server Retry-After. */
@RunWith(AndroidJUnit4::class)
class ReviewFixesTest {
    private val env = TestEnv()

    @After
    fun tearDown() = env.close()

    private fun entry(key: String, at: Long) = NormalizedEntry(
        entryKey = key, title = key, linkUrl = key.removePrefix("url:"), summaryText = "", author = null, publishedAt = at, imageUrl = null,
    )

    private val meta = FetchMetadata(null, null, "https://example.com/feed.xml", null)

    private suspend fun apply(feedId: String, vararg entries: NormalizedEntry) =
        env.store.applySuccess(feedId, NormalizedFeed("t", null, entries.toList()), meta, BodyStats(0, entries.size), AttemptInfo(200, false))
            as ApplyResult.Applied

    // F01

    @Test
    fun prunedHttpKeyDeliveredAsHttpsIsNotResurrectedAsUnread() = runBlocking<Unit> {
        val account = env.subscriptions.ensureLocalAccount()
        val feedId = (env.subscriptions.addFeed(account, "https://example.com/feed.xml") as AddFeedResult.Added).feedId
        val base = env.clock.millis() - 10_000_000
        apply(feedId, entry("url:http://example.com/a", base), entry("url:http://example.com/b", base + 1000))
        val a = env.db.articleDao().articlesOfFeed(feedId).first { it.entryKey == "url:http://example.com/a" }
        env.articles.setRead(a.id, true)
        // Limit 1: the oldest read, unsaved article is removed and remembered.
        assertEquals(1, RetentionManager(env.db, env.clock).apply(account, 1))
        assertEquals(1, env.db.articleDao().articlesOfFeed(feedId).size)

        // The feed now serves the same articles over https:// (URL-like keys changed scheme only).
        env.clock.advanceMinutes(10)
        val applied = apply(feedId, entry("url:https://example.com/a", base), entry("url:https://example.com/b", base + 1000))
        assertEquals("a is not new", 0, applied.newArticles)
        assertEquals(1, applied.skippedPruned)
        assertEquals(1, env.db.articleDao().articlesOfFeed(feedId).size)

        // An unrelated URL is still new: only the scheme alias is resolved.
        assertEquals(1, apply(feedId, entry("url:https://example.com/c", base + 2000)).newArticles)
        // Another feed with the same path is not affected by this feed's record.
        val otherFeed = (env.subscriptions.addFeed(account, "https://example.org/feed.xml") as AddFeedResult.Added).feedId
        assertEquals(1, apply(otherFeed, entry("url:https://example.com/a", base)).newArticles)
    }

    // F02

    private fun opml(vararg urls: String) = OpmlParser.parse(
        ("<opml version=\"2.0\"><body>" + urls.joinToString("") { "<outline text=\"f\" xmlUrl=\"$it\"/>" } + "</body></opml>").toByteArray(),
    )

    @Test
    fun previewAndCommitAgreeForMigratedFeeds() = runBlocking<Unit> {
        val account = env.subscriptions.ensureLocalAccount()
        val feedId = (env.subscriptions.addFeed(account, "http://example.com/feed.xml") as AddFeedResult.Added).feedId
        assertTrue(env.subscriptions.applyHttpsUpgrade(feedId, "http://example.com/feed.xml", "https://example.com/feed.xml"))
        // A second feed fetched over HTTP is the only one that is really pending while HTTP is off.
        env.subscriptions.addFeed(account, "http://other.example.org/feed.xml")

        for (url in listOf("https://example.com/feed.xml", "http://example.com/feed.xml")) {
            val doc = opml(url)
            val preview = env.subscriptions.previewImport(account, doc, allowHttp = false)
            assertEquals(url, 0, preview.newFeeds)
            assertEquals(url, 1, preview.existingFeeds)
            assertEquals("fetched over https: not held back by the HTTP setting ($url)", 0, preview.httpPending)
            val commit = env.subscriptions.commitImport(account, doc)
            assertEquals(url, preview.newFeeds, commit.createdFeeds)
            assertEquals(url, preview.resubscribeFeeds, commit.resubscribedFeeds)
        }
        assertEquals(2, env.db.subscriptionDao().feeds(account).size)

        // Still pending: an http:// feed that is really fetched over http://.
        val pending = env.subscriptions.previewImport(account, opml("http://other.example.org/feed.xml"), allowHttp = false)
        assertEquals(1, pending.httpPending)
        assertEquals(0, pending.newFeeds)
    }

    @Test
    fun unsubscribedMigratedFeedIsReEnabledOnceWhateverSchemeTheOpmlUses() = runBlocking<Unit> {
        val account = env.subscriptions.ensureLocalAccount()
        val feedId = (env.subscriptions.addFeed(account, "http://example.com/feed.xml") as AddFeedResult.Added).feedId
        env.subscriptions.applyHttpsUpgrade(feedId, "http://example.com/feed.xml", "https://example.com/feed.xml")
        env.subscriptions.unsubscribe(feedId)
        val doc = opml("https://example.com/feed.xml", "http://example.com/feed.xml")
        val preview = env.subscriptions.previewImport(account, doc, allowHttp = true)
        assertEquals(0, preview.newFeeds)
        assertEquals("one feed, listed once", 1, preview.resubscribeFeeds)
        val commit = env.subscriptions.commitImport(account, doc)
        assertEquals(preview.newFeeds, commit.createdFeeds)
        assertEquals(preview.resubscribeFeeds, commit.resubscribedFeeds)
        assertEquals(listOf(feedId), commit.feedIdsToFetch)
    }

    // F03

    @Test
    fun serverRetryAfterBeyondOneDayIsNotShortened() = runBlocking<Unit> {
        val server = TestEnv.startServer()
        try {
            val seconds = mapOf("/h48" to 48L * 3600, "/h6" to 6L * 3600, "/d9" to 9L * 24 * 3600)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return MockResponse.Builder().code(if (path == "/h6") 503 else 429).setHeader("Retry-After", seconds.getValue(path).toString()).build()
                }
            }
            val account = env.subscriptions.ensureLocalAccount()
            val ids = seconds.keys.associateWith { (env.subscriptions.addFeed(account, server.url(it).toString()) as AddFeedResult.Added).feedId }
            val now = env.clock.millis()
            env.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {}
            for ((path, s) in seconds) {
                val state = env.db.subscriptionDao().fetchState(ids.getValue(path))!!
                assertEquals(path, now + s * 1000, state.retryAfterUntil)
                assertEquals(path, now + s * 1000, state.nextEligibleAt)
            }
            // 25 hours later the 6 h feed may be asked again; the 48 h and 9 day ones may not, whether the request
            // is manual, forced or automatic.
            env.clock.advanceMinutes(25 * 60)
            val before = server.requestCount
            val targets = RefreshScope.Feeds(listOf(ids.getValue("/h48"), ids.getValue("/d9")))
            for ((reason, force) in listOf(RefreshReason.MANUAL to false, RefreshReason.MANUAL to true, RefreshReason.PERIODIC to false)) {
                val report = env.backend.refresh(account, targets, reason, force) {}
                assertEquals("$reason force=$force", 2, report.deferredBackoff)
                assertEquals(FeedResult.BACKOFF, report.perFeed.getValue(ids.getValue("/h48")).result)
            }
            assertEquals("nothing was requested before the server's time", before, server.requestCount)
            // After 48 h the 48 h feed is asked again.
            env.clock.advanceMinutes(24 * 60)
            val later = env.backend.refresh(account, RefreshScope.Feeds(listOf(ids.getValue("/h48"))), RefreshReason.MANUAL) {}
            assertEquals(0, later.deferredBackoff)
            assertEquals(before + 1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun absurdRetryAfterIsBoundedAndDoesNotOverflow() {
        assertEquals(RetryPolicy.MAX_SERVER_WAIT_MS, RetryPolicy.delayMillis(FetchError.RATE_LIMITED, 1, Long.MAX_VALUE / 2))
        assertEquals(RetryPolicy.MAX_SERVER_WAIT_MS, RetryPolicy.delayMillis(FetchError.SERVER, 1, 400L * 24 * 3600 * 1000))
        val fetcher = FeedFetcher(env.httpClient, env.clock)
        assertNull(fetcher.retryAfterMillis("soon"))
        // Beyond the old 7 day cut-off, and still safe to multiply.
        val huge = fetcher.retryAfterMillis("999999999999")!!
        assertTrue(huge > 7L * 24 * 3600 * 1000)
        assertTrue(huge > 0)
        assertEquals(30L * 24 * 3600 * 1000, fetcher.retryAfterMillis("2592000"))
    }
}
