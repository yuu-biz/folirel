package io.github.yuubiz.folirel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.folirel.app.AppContainer
import io.github.yuubiz.folirel.app.FolirelApp
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.data.repository.FeedStore
import io.github.yuubiz.folirel.data.repository.FetchMetadata
import io.github.yuubiz.folirel.data.repository.IdGenerator
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import kotlinx.coroutines.runBlocking
import java.time.Clock

/** Test-only seeding through the app's own repositories (no network, no demo switch in the app). */
object TestData {
    val container: AppContainer
        get() = (ApplicationProvider.getApplicationContext<Context>() as FolirelApp).container

    fun reset(onboardingDone: Boolean = true, showImages: Boolean = false) = runBlocking {
        val c = container
        c.database.clearAllTables()
        c.settings.setRefreshIntervalMinutes(0) // no automatic network activity during UI tests
        c.settings.setStartupRefresh(false)
        c.settings.setOnboardingDone(onboardingDone)
        c.settings.setShowImages(showImages)
        c.settings.setListMode(ListMode.LIST)
        c.settings.setLastScope(ArticleScope.All)
        c.settings.setLastFilter(ReadFilter.ALL)
        c.settings.setSortOrder(SortOrder.NEWEST)
        c.settings.setTextScale(1.0f)
        c.settings.setConfirmHttpOpen(true)
        c.settings.setCardColumns(0)
        c.settings.setCardStyle(io.github.yuubiz.folirel.model.CardLayoutStyle.MIXED)
    }

    private var seededFeedId: String? = null
    private var seededCount = 0
    private var arrivedCount = 0

    /** Title of the newest article that [arriveAtTheHead] delivered. */
    val newestArrivedTitle: String get() = "新着 ${arrivedCount - 1}"

    /** What a refresh does: [count] articles newer than everything stored arrive at the head of the seeded feed. */
    fun arriveAtTheHead(count: Int) = runBlocking {
        val c = container
        val now = System.currentTimeMillis()
        val entries = (0 until count).map { _ ->
            val k = arrivedCount++
            NormalizedEntry("id:new-$k", "新着 $k", "https://www.example.com/", "新着の概要 $k", null, now + k * 1_000L + 1_000_000L, null)
        }
        FeedStore(c.database, Clock.systemUTC(), IdGenerator.Random)
            .applySuccess(seededFeedId!!, NormalizedFeed("架空フィード", null, entries), FetchMetadata(null, null, "https://feed.example.com/rss.xml", null))
    }

    /** One subscribed feed with [count] articles; index 0 is the newest. Returns article ids newest first. */
    fun seedArticles(count: Int, withLinks: Boolean = true, link: String = "https://www.example.com/"): List<String> = runBlocking {
        val c = container
        val account = c.subscriptions.ensureLocalAccount()
        val opml = """<opml version="2.0"><body><outline text="テスト"><outline text="架空フィード" xmlUrl="https://feed.example.com/rss.xml"/></outline></body></opml>"""
        val feedId = c.subscriptions.commitImport(account, OpmlParser.parse(opml.toByteArray())).feedIdsToFetch.single()
        seededFeedId = feedId
        arrivedCount = 0
        val now = System.currentTimeMillis()
        val entries = (0 until count).map { i ->
            NormalizedEntry(
                entryKey = "id:item-$i",
                title = "記事 $i 長い日本語の見出しが折り返しても行の高さが崩れないことを確認するためのテキスト",
                linkUrl = if (withLinks) link else null,
                summaryText = "概要 $i",
                author = null,
                publishedAt = now - i * 60_000L,
                imageUrl = null,
            )
        }
        FeedStore(c.database, Clock.systemUTC(), IdGenerator.Random)
            .applySuccess(feedId, NormalizedFeed("架空フィード", null, entries), FetchMetadata(null, null, "https://feed.example.com/rss.xml", null))
        c.database.articleDao().articlesOfFeed(feedId).map { it.id } // sortAt DESC = newest first
    }

    fun state(articleId: String) = runBlocking { container.database.articleDao().state(articleId)!! }

    /**
     * One feed per category ("Alpha", "Beta", ...), [perCategory] articles each, no network.
     * Returns category id → article ids newest first.
     */
    fun seedCategories(names: List<String>, perCategory: Int): Map<String, List<String>> = runBlocking {
        val c = container
        val account = c.subscriptions.ensureLocalAccount()
        val outlines = names.joinToString("") { n -> """<outline text="$n"><outline text="feed $n" xmlUrl="https://feed.example.com/$n.xml"/></outline>""" }
        c.subscriptions.commitImport(account, OpmlParser.parse("""<opml version="2.0"><body>$outlines</body></opml>""".toByteArray()))
        val store = FeedStore(c.database, Clock.systemUTC(), IdGenerator.Random)
        val now = System.currentTimeMillis()
        val categories = c.database.subscriptionDao().categories(account).associateBy { it.name }
        val feeds = c.database.subscriptionDao().feeds(account).associateBy { it.subscriptionUrl }
        names.associate { n ->
            val feedId = feeds.getValue("https://feed.example.com/$n.xml").id
            val entries = (0 until perCategory).map { i ->
                NormalizedEntry("id:$n-$i", "$n 記事 $i", "https://www.example.com/", "概要 $n $i", null, now - i * 60_000L, null)
            }
            store.applySuccess(feedId, NormalizedFeed("feed $n", null, entries), FetchMetadata(null, null, "https://feed.example.com/$n.xml", null))
            categories.getValue(n).id to c.database.articleDao().articlesOfFeed(feedId).map { it.id }
        }
    }
}
