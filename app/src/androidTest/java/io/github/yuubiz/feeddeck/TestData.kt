package io.github.yuubiz.feeddeck

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.feeddeck.app.AppContainer
import io.github.yuubiz.feeddeck.app.FeedDeckApp
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlParser
import io.github.yuubiz.feeddeck.data.repository.FeedStore
import io.github.yuubiz.feeddeck.data.repository.FetchMetadata
import io.github.yuubiz.feeddeck.data.repository.IdGenerator
import io.github.yuubiz.feeddeck.model.ArticleScope
import io.github.yuubiz.feeddeck.model.ListMode
import io.github.yuubiz.feeddeck.model.NormalizedEntry
import io.github.yuubiz.feeddeck.model.NormalizedFeed
import io.github.yuubiz.feeddeck.model.ReadFilter
import io.github.yuubiz.feeddeck.model.SortOrder
import kotlinx.coroutines.runBlocking
import java.time.Clock

/** Test-only seeding through the app's own repositories (no network, no demo switch in the app). */
object TestData {
    val container: AppContainer
        get() = (ApplicationProvider.getApplicationContext<Context>() as FeedDeckApp).container

    fun reset(onboardingDone: Boolean = true, showImages: Boolean = false) = runBlocking {
        val c = container
        c.database.clearAllTables()
        c.settings.setRefreshIntervalMinutes(0) // no automatic network activity during UI tests
        c.settings.setStartupRefresh(false)
        c.settings.setOnboardingDone(onboardingDone)
        c.settings.setShowImages(showImages)
        c.settings.setListMode(ListMode.MAGAZINE)
        c.settings.setLastScope(ArticleScope.All)
        c.settings.setLastFilter(ReadFilter.ALL)
        c.settings.setSortOrder(SortOrder.NEWEST)
        c.settings.setTextScale(1.0f)
    }

    /** One subscribed feed with [count] articles; index 0 is the newest. Returns article ids newest first. */
    fun seedArticles(count: Int, withLinks: Boolean = true): List<String> = runBlocking {
        val c = container
        val account = c.subscriptions.ensureLocalAccount()
        val opml = """<opml version="2.0"><body><outline text="テスト"><outline text="架空フィード" xmlUrl="https://feed.example.com/rss.xml"/></outline></body></opml>"""
        val feedId = c.subscriptions.commitImport(account, OpmlParser.parse(opml.toByteArray())).feedIdsToFetch.single()
        val now = System.currentTimeMillis()
        val entries = (0 until count).map { i ->
            NormalizedEntry(
                entryKey = "id:item-$i",
                title = "記事 $i 長い日本語の見出しが折り返しても行の高さが崩れないことを確認するためのテキスト",
                linkUrl = if (withLinks) "https://www.example.com/" else null,
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
}
