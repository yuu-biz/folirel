package io.github.yuubiz.folirel.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.parser.FeedParser
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B3: a feed that spells the identity of the same items differently on a later fetch. The real parser builds the keys and the
 * real store applies them, as a refresh does.
 */
@RunWith(AndroidJUnit4::class)
class EntryIdentityDriftTest {
    private val env = TestEnv()

    @After
    fun tearDown() = env.close()

    private data class Item(val title: String, val link: String?, val guid: String?, val day: Int?, val summary: String)

    private fun rss(items: List<Item>) = """<rss version="2.0"><channel><title>site</title>""" + items.joinToString("") { i ->
        "<item>" + (i.guid?.let { "<guid isPermaLink=\"false\">$it</guid>" } ?: "") + "<title>${i.title}</title>" +
            (i.link?.let { "<link>$it</link>" } ?: "") +
            (i.day?.let { "<pubDate>Sat, ${it.toString().padStart(2, '0')} Aug 2026 10:00:00 GMT</pubDate>" } ?: "") +
            "<description>${i.summary}</description></item>"
    } + "</channel></rss>"

    private suspend fun feed(): String {
        val account = env.subscriptions.ensureLocalAccount()
        return (env.subscriptions.addFeed(account, "https://example.com/feed.xml") as AddFeedResult.Added).feedId
    }

    private suspend fun fetch(feedId: String, items: List<Item>) {
        val parsed = FeedParser.parse(rss(items), "https://example.com/feed.xml")
        env.store.applySuccess(feedId, parsed, FetchMetadata(null, null, "https://example.com/feed.xml", null))
    }

    /** title -> (read, saved) of every stored article of the feed, one line each, sorted. */
    private suspend fun stored(feedId: String): List<String> = env.db.articleDao().articlesOfFeed(feedId).map { a ->
        val s = env.db.articleDao().state(a.id)!!
        "${a.title}|read=${s.isRead}|saved=${s.isSaved}"
    }.sorted()

    private suspend fun idOf(feedId: String, title: String) = env.db.articleDao().articlesOfFeed(feedId).single { it.title == title }.id

    private val articles = listOf(
        Item("First", "https://example.com/posts/1", "a-1", 1, "One"),
        Item("Second", "https://example.com/posts/2", "a-2", 2, "Two"),
        Item("Third", "https://example.com/posts/3", "a-3", 3, "Three"),
    )

    @Test
    fun rewrittenGuidsKeepTheArticlesAndTheirState() = runBlocking {
        val feedId = feed()
        fetch(feedId, articles)
        env.articles.setRead(idOf(feedId, "First"), true)
        env.articles.setSaved(idOf(feedId, "Second"), true)

        // Same address, same items (link, title, date, text); only the opaque GUIDs are new.
        fetch(feedId, articles.map { it.copy(guid = "b-" + it.guid!!.removePrefix("a-")) })

        assertEquals(listOf("First|read=true|saved=false", "Second|read=false|saved=true", "Third|read=false|saved=false"), stored(feedId))
    }

    /** Documents current behaviour (not a fix): with neither GUID nor link, the summary is part of the identity. */
    @Test
    fun currentBehaviour_editedSummaryWithoutGuidOrLinkIsStoredAgainAsUnread() = runBlocking {
        val feedId = feed()
        val bare = articles.map { it.copy(guid = null, link = null) }
        fetch(feedId, bare)
        env.articles.setRead(idOf(feedId, "First"), true)

        fetch(feedId, bare.map { if (it.title == "First") it.copy(summary = "One, corrected") else it })

        assertEquals(
            listOf("First|read=false|saved=false", "First|read=true|saved=false", "Second|read=false|saved=false", "Third|read=false|saved=false"),
            stored(feedId),
        )
    }
}
