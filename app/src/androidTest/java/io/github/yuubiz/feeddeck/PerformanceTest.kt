package io.github.yuubiz.feeddeck

import android.os.Build
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.feeddeck.app.MainActivity
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlParser
import io.github.yuubiz.feeddeck.data.repository.FeedStore
import io.github.yuubiz.feeddeck.data.repository.FetchMetadata
import io.github.yuubiz.feeddeck.data.repository.IdGenerator
import io.github.yuubiz.feeddeck.model.ArticleQuerySpec
import io.github.yuubiz.feeddeck.model.ArticleScope
import io.github.yuubiz.feeddeck.model.NormalizedEntry
import io.github.yuubiz.feeddeck.model.NormalizedFeed
import io.github.yuubiz.feeddeck.model.ReadFilter
import io.github.yuubiz.feeddeck.model.SortOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock

/**
 * T47 (synthetic part): 300 feeds / 5,000 articles. Prints p50/p95 for warm activity start until the list is
 * visible and for local search queries. Debug build on an emulator: these are not release measurements.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun percentile(values: List<Long>, p: Double): Long = values.sorted()[((values.size - 1) * p).toInt()]

    @Test
    fun syntheticFiveThousandArticles() {
        TestData.reset(showImages = false)
        val c = TestData.container
        val seedStart = System.nanoTime()
        val account = runBlocking { c.subscriptions.ensureLocalAccount() }
        val opml = buildString {
            append("<opml version=\"2.0\"><body>")
            for (cat in 0 until 10) {
                append("<outline text=\"カテゴリ$cat\">")
                for (f in 0 until 30) append("<outline text=\"フィード$cat-$f\" xmlUrl=\"https://feed$cat-$f.example.com/rss\"/>")
                append("</outline>")
            }
            append("</body></opml>")
        }
        val feedIds = runBlocking { c.subscriptions.commitImport(account, OpmlParser.parse(opml.toByteArray())).feedIdsToFetch }
        assertEquals(300, feedIds.size)
        val store = FeedStore(c.database, Clock.systemUTC(), IdGenerator.Random)
        val now = System.currentTimeMillis()
        var n = 0
        runBlocking {
            feedIds.forEachIndexed { index, feedId ->
                val count = if (index < 200) 17 else 16 // 200*17 + 100*16 = 5,000
                val entries = (0 until count).map {
                    n++
                    NormalizedEntry(
                        "id:$index-$it", "記事$n 経済ニュース 日本語の見出し テスト $index-$it", "https://www.example.com/$n",
                        "概要テキスト $n 人工知能 半導体 為替 ".repeat(5), null, now - n * 30_000L, null,
                    )
                }
                store.applySuccess(feedId, NormalizedFeed("フィード$index", null, entries), FetchMetadata(null, null, "u", null))
            }
        }
        val seedMs = (System.nanoTime() - seedStart) / 1_000_000
        assertEquals(5000, runBlocking { c.database.articleDao().countArticles(account) })

        // Warm start: process alive, activity launched until the article list is in the composition.
        val startTimes = (0 until 7).map {
            val t0 = System.nanoTime()
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("article_list").fetchSemanticsNodes().isNotEmpty() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            scenario.close()
            ms
        }

        // Local search over all 5,000 articles (query execution after debounce).
        val terms = listOf("半導体", "記事4999", "テスト 12-", "%", "ニュース")
        val searchTimes = mutableListOf<Long>()
        runBlocking {
            repeat(4) {
                for (term in terms) {
                    val t0 = System.nanoTime()
                    c.articles.rows(ArticleQuerySpec(account, ArticleScope.All, ReadFilter.ALL, SortOrder.NEWEST, term))
                    searchTimes += (System.nanoTime() - t0) / 1_000_000
                }
            }
        }
        val listTimes = runBlocking {
            (0 until 10).map {
                val t0 = System.nanoTime()
                val rows = c.articles.rows(ArticleQuerySpec(account, ArticleScope.All, ReadFilter.ALL, SortOrder.NEWEST))
                check(rows.size == 5000)
                (System.nanoTime() - t0) / 1_000_000
            }
        }
        val report = "PERF device=${Build.MODEL} api=${Build.VERSION.SDK_INT} build=debug seed=${seedMs}ms " +
            "warmStart p50=${percentile(startTimes, 0.5)}ms p95=${percentile(startTimes, 0.95)}ms all=$startTimes " +
            "search p50=${percentile(searchTimes, 0.5)}ms p95=${percentile(searchTimes, 0.95)}ms " +
            "fullListQuery p50=${percentile(listTimes, 0.5)}ms p95=${percentile(listTimes, 0.95)}ms"
        Log.i("FeedDeckPerf", report)
        println(report)
        TestData.reset()
    }
}
