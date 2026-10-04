package io.github.yuubiz.folirel.backend.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.data.db.ArticleEntity
import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.data.repository.RetentionManager
import io.github.yuubiz.folirel.data.repository.SubscriptionRepository
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.testutil.literalUrl
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

/**
 * V3-R02 (review 0.3.0): discovery calls two articles "shared" when their keys differ only in id:/url:, http/https and
 * www., so the user adopts the new address because of that. The first real update after the move must then treat
 * the same articles as the same articles: no second unread copy, no lost read/saved state, no pruned (read, removed)
 * article coming back as unread. Runs the real parser and the normal update path against a MockWebServer.
 */
@RunWith(AndroidJUnit4::class)
class MovedFeedIdentityTest {
    private val server = TestEnv.startServer()
    private val env = TestEnv()
    private val bodies = ConcurrentHashMap<String, String>()

    @After
    fun tearDown() {
        env.close()
        server.close()
    }

    private fun serve() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = bodies[request.url.encodedPath] ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(body).build()
            }
        }
    }

    private fun item(title: String, link: String, guid: String? = null, day: Int) =
        "<item>" + (guid?.let { "<guid>$it</guid>" } ?: "") + "<title>$title</title><link>$link</link>" +
            "<pubDate>Sat, ${day.toString().padStart(2, '0')} Aug 2026 10:00:00 GMT</pubDate></item>"

    private fun rss(vararg items: String) = """<rss version="2.0"><channel><title>site</title>${items.joinToString("")}</channel></rss>"""

    private suspend fun subscribe(path: String): String {
        val account = env.subscriptions.ensureLocalAccount()
        return (env.subscriptions.addFeed(account, server.literalUrl(path).toString()) as AddFeedResult.Added).feedId
    }

    private suspend fun refresh(feedId: String) =
        env.backend.refresh(env.subscriptions.ensureLocalAccount(), RefreshScope.Feeds(listOf(feedId)), RefreshReason.MANUAL) {}

    private suspend fun articles(feedId: String): List<ArticleEntity> = env.db.articleDao().articlesOfFeed(feedId)

    private suspend fun stateOf(articleId: String) = env.db.articleDao().state(articleId)!!

    private val base = "https://site.example/p"

    @Test
    fun movedFeedKeepsEveryArticleItAlreadyHadWhateverFormTheNewFeedUsesForTheId() = runBlocking<Unit> {
        serve()
        // The old feed has no GUIDs: identity is the link.
        bodies["/old.xml"] = rss(
            item("one", "$base/1", day = 1), item("two", "$base/2", day = 2),
            item("three", "$base/3", day = 3), item("four", "$base/4", day = 4),
        )
        val feedId = subscribe("/old.xml")
        refresh(feedId)
        val before = articles(feedId).associateBy { it.linkUrl!!.substringAfterLast('/') }
        assertEquals(4, before.size)
        env.articles.setRead(before.getValue("1").id, true)
        env.articles.setSaved(before.getValue("2").id, true)
        env.articles.setRead(before.getValue("3").id, true)
        // Article 1 is read and unsaved, the oldest: the retention cleanup removes it and remembers it.
        assertEquals(1, RetentionManager(env.db, env.clock).apply(env.subscriptions.ensureLocalAccount(), 3))
        assertEquals(3, articles(feedId).size)

        // The site moved; the new feed uses the link as GUID, in other spellings of the same address.
        bodies["/new.xml"] = rss(
            item("one", "$base/1", guid = "$base/1", day = 1),
            item("two", "$base/2", guid = "http://site.example/p/2", day = 2),
            item("three", "$base/3", guid = "https://www.site.example/p/3", day = 3),
            item("four", "$base/4", guid = "$base/4", day = 4),
            item("five", "$base/5", guid = "$base/5", day = 5),
            item("four again, other address", "$base/other", guid = "opaque-guid-1", day = 6),
        )
        val newUrl = server.literalUrl("/new.xml").toString()
        assertEquals(
            SubscriptionRepository.MoveResult.Applied,
            env.subscriptions.applyUrlMove(feedId, server.literalUrl("/old.xml").toString(), newUrl),
        )
        env.clock.advanceMinutes(30)
        val report = refresh(feedId)

        val after = articles(feedId)
        assertEquals("old 3 (one was pruned) + 2 genuinely new", 5, after.size)
        assertEquals("only the two really new articles are new", 2, report.newArticles)
        for (n in listOf("2", "3", "4")) {
            assertEquals("article $n keeps its id", before.getValue(n).id, after.single { it.linkUrl == "$base/$n" }.id)
        }
        assertEquals("saved stays saved", true, stateOf(before.getValue("2").id).isSaved)
        assertEquals("read stays read", true, stateOf(before.getValue("3").id).isRead)
        assertEquals("unread stays unread, no second copy", false, stateOf(before.getValue("4").id).isRead)
        assertTrue("pruned read article does not return as a new unread article", after.none { it.linkUrl == "$base/1" })
        // The unrelated ones are separate articles and unread.
        for (n in listOf("5", "other")) assertFalse(stateOf(after.single { it.linkUrl == "$base/$n" }.id).isRead)

        // A second update changes nothing.
        env.clock.advanceMinutes(30)
        assertEquals(0, refresh(feedId).newArticles)
        assertEquals(5, articles(feedId).size)
    }

    @Test
    fun theSameSpellingChangeInAnotherFeedIsNotMergedAcrossFeeds() = runBlocking<Unit> {
        serve()
        bodies["/old.xml"] = rss(item("two", "$base/2", day = 2))
        bodies["/new.xml"] = rss(item("two", "$base/2", guid = "$base/2", day = 2))
        bodies["/other.xml"] = rss(item("two", "$base/2", guid = "$base/2", day = 2))
        val moved = subscribe("/old.xml")
        refresh(moved)
        val other = subscribe("/other.xml")
        refresh(other)
        env.subscriptions.applyUrlMove(moved, server.literalUrl("/old.xml").toString(), server.literalUrl("/new.xml").toString())
        env.clock.advanceMinutes(30)
        refresh(moved)
        assertEquals(1, articles(moved).size)
        assertEquals("the other feed keeps its own article", 1, articles(other).size)
        assertTrue(articles(moved).single().id != articles(other).single().id)
    }

    @Test
    fun aFeedThatWasNeverMovedKeepsStrictIdentity() = runBlocking<Unit> {
        // The wider equivalence (id:/url:, www) is applied to approved moves only; an ordinary feed is not guessed at.
        serve()
        bodies["/f.xml"] = rss(item("two", "$base/2", day = 2))
        val feedId = subscribe("/f.xml")
        refresh(feedId)
        bodies["/f.xml"] = rss(item("two", "$base/2", guid = "$base/2", day = 2))
        env.clock.advanceMinutes(30)
        refresh(feedId)
        assertEquals(2, articles(feedId).size)
    }
}
