package io.github.yuubiz.folirel

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not an assertion test: puts a realistic state into an installed app for the upgrade check (P1-T31). It is run with
 * `am instrument -e class io.github.yuubiz.folirel.P1SeedTest` against the PREVIOUS release (0.4.2), which is installed first, so it
 * uses only what that release has: its own repositories and settings. Subscriptions in nested categories, articles, some read
 * and some saved, and a handful of changed settings; no network.
 */
@RunWith(AndroidJUnit4::class)
class P1SeedTest {
    /** Skipped in an ordinary run (one method fetches public feeds): only `-e p1evidence true` runs these helpers. */
    @org.junit.Before
    fun onlyWhenAsked() {
        org.junit.Assume.assumeTrue("evidence helper (pass -e p1evidence true)", androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("p1evidence") == "true")
    }

    @Test
    fun seedForTheUpgradeCheck() = runBlocking<Unit> {
        TestData.reset()
        val seeded = TestData.seedCategories(listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta"), perCategory = 30)
        val articles = seeded.values.flatten()
        val c = TestData.container
        articles.filterIndexed { i, _ -> i % 12 == 0 }.forEach { c.articles.setRead(it, true) }
        articles.filterIndexed { i, _ -> i % 35 == 0 }.forEach { c.articles.setSaved(it, true) }
        c.settings.setListMode(ListMode.MAGAZINE)
        c.settings.setCardColumns(2)
        c.settings.setCardStyle(CardLayoutStyle.EVEN)
        c.settings.setTheme(ThemeMode.DARK)
        c.settings.setTextScale(1.15f)
        c.settings.setLastScope(ArticleScope.Category(seeded.keys.elementAt(2)))
        android.util.Log.i("P1SEED", "articles=${articles.size} read=${articles.count { TestData.state(it).isRead }} saved=${articles.count { TestData.state(it).isSaved }}")
    }

    /**
     * Public feeds for the check that a refresh after the upgrade leaves the reader's state alone: five public feeds in two categories,
     * fetched once by the app's own coordinator and network policy (the feeds named in local-data/smoke-v3.opml), some articles read
     * and saved. Needs a network; feeds that cannot be reached simply have no articles.
     */
    @Test
    fun seedPublicFeeds() = runBlocking<Unit> {
        val c = TestData.container
        val account = c.subscriptions.ensureLocalAccount()
        val opml = """<opml version="2.0"><body>
            <outline text="ニュース">
              <outline text="BBC News" type="rss" xmlUrl="https://feeds.bbci.co.uk/news/rss.xml"/>
              <outline text="GIGAZINE" type="rss" xmlUrl="https://gigazine.net/news/rss_2.0/"/>
              <outline text="ITmedia" type="rss" xmlUrl="https://rss.itmedia.co.jp/rss/2.0/itmedia_all.xml"/>
            </outline>
            <outline text="Tech">
              <outline text="The Verge" type="rss" xmlUrl="https://www.theverge.com/rss/index.xml"/>
              <outline text="NASA" type="rss" xmlUrl="https://www.nasa.gov/feed/"/>
            </outline></body></opml>"""
        c.subscriptions.commitImport(account, io.github.yuubiz.folirel.backend.local.opml.OpmlParser.parse(opml.toByteArray()))
        c.settings.setAllowHttp(false)
        c.refresh.refresh(io.github.yuubiz.folirel.backend.RefreshScope.All, io.github.yuubiz.folirel.backend.RefreshReason.MANUAL)
        val ids = c.database.subscriptionDao().feeds(account).flatMap { f -> c.database.articleDao().articlesOfFeed(f.id).map { it.id } }
        ids.filterIndexed { i, _ -> i % 9 == 0 }.forEach { c.articles.setRead(it, true) }
        ids.filterIndexed { i, _ -> i % 17 == 0 }.forEach { c.articles.setSaved(it, true) }
        android.util.Log.i("P1SEED", "public articles=${ids.size} read=${ids.count { TestData.state(it).isRead }} saved=${ids.count { TestData.state(it).isSaved }}")
    }

    /** One ordinary refresh of everything, run inside the app that was just upgraded (the after-refresh half of the upgrade check). */
    @Test
    fun refreshOnce() = runBlocking<Unit> {
        val report = TestData.container.refresh.refresh(io.github.yuubiz.folirel.backend.RefreshScope.All, io.github.yuubiz.folirel.backend.RefreshReason.MANUAL)
        android.util.Log.i("P1SEED", "refreshed: $report")
    }
}
