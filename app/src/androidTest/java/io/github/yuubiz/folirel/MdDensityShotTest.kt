package io.github.yuubiz.folirel

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.ui.home.ArticleActions
import io.github.yuubiz.folirel.ui.home.ArticleList
import io.github.yuubiz.folirel.ui.home.ArticlesState
import io.github.yuubiz.folirel.ui.home.ListAnchor
import io.github.yuubiz.folirel.ui.home.ListHost
import io.github.yuubiz.folirel.ui.theme.FolirelTheme
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Evidence helper for the Magazine density work (not an assertion test; skipped unless `-e p1evidence true`). The same articles,
 * the same pictures, the same width and text size, the top of the list: it writes a screenshot per case and one `MDDENSITY` log line
 * with the measured row heights, how many cards are wholly on screen and how many have their headline on screen. Only the real
 * article list composable is used, so the same file runs against the version before the change and the one after it.
 * Label: `-e mdLabel before|after`. Pictures: `/sdcard/Android/media/<package>/mdshots/<label>/`.
 */
@RunWith(AndroidJUnit4::class)
class MdDensityShotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val label get() = InstrumentationRegistry.getArguments().getString("mdLabel") ?: "after"

    @Before
    fun onlyWhenAsked() {
        Assume.assumeTrue("evidence helper (pass -e p1evidence true)", InstrumentationRegistry.getArguments().getString("p1evidence") == "true")
        TestData.reset()
    }

    private val host = object : ListHost {
        override fun anchorFor(listKey: String): ListAnchor? = null
        override fun saveAnchor(anchor: ListAnchor) = Unit
        override fun acknowledgeNew(ids: Collection<String>) = Unit
    }

    private fun show(items: List<ArticleRow>, style: CardLayoutStyle, columns: Int, widthDp: Int?, fontScale: Float, showImages: Boolean) {
        val context = instrumentation.targetContext
        val loader = ImageLoader.Builder(context).build()
        val actions = ArticleActions({}, {}, {}, { _, _ -> }, {}, {})
        compose.runOnUiThread {
            val view = ComposeView(compose.activity)
            compose.activity.setContentView(view)
            view.setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    FolirelTheme(ThemeMode.DARK, 1.0f) {
                        Box(if (widthDp != null) Modifier.fillMaxHeight().requiredWidth(widthDp.dp) else Modifier.fillMaxSize()) {
                            ArticleList(
                                host = host,
                                state = ArticlesState("md-$label", items, loaded = true),
                                mode = ListMode.MAGAZINE,
                                showImages = showImages,
                                cardColumns = columns,
                                cardStyle = style,
                                imageLoader = loader,
                                actions = actions,
                                newIds = emptySet(),
                                newestFirst = true,
                                isCurrent = true,
                                scrollToTopTick = 0,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(1_500) // the pictures arrive
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        val dir = File(instrumentation.targetContext.externalMediaDirs.first { it != null }, "mdshots/$label").also { it.mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun measure(name: String) {
        val list = compose.onAllNodes(hasTestTag("article_list")).fetchSemanticsNodes().first()
        val top = list.positionInRoot.y
        val height = list.size.height
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val cards = compose.onAllNodes(SemanticsMatcher("card") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.let { t -> t.startsWith("article_") && t != "article_list" } == true
        }).fetchSemanticsNodes().map { Triple(it.positionInRoot.y - top, it.size.height.toFloat(), it.config[SemanticsProperties.TestTag]) }
        val rows = cards.groupBy { (it.first / 4).toInt() }.toSortedMap().values.map { row -> row.maxOf { it.second } / density }
        val wholly = cards.count { it.first >= -1f && it.first + it.second <= height + 1f }
        // A headline is on screen when the card's top part (its picture and the first line of text) is.
        val images = compose.onAllNodes(hasTestTag("card_image"), useUnmergedTree = true).fetchSemanticsNodes()
        val imageH = images.map { it.size.height / density }.sorted()
        val imageMedian = imageH.getOrNull(imageH.size / 2) ?: 0f
        val headlines = cards.count { it.first + (imageMedian * density) + 30f * density <= height && it.first + it.second > 0 }
        val sorted = rows.filterIndexed { i, _ -> i < rows.size - 1 }.sorted() // the last row may be cut by the bottom
        val median = sorted.getOrNull(sorted.size / 2) ?: 0f
        Log.i(
            "MDDENSITY",
            "$label $name viewportDp=${"%.0f".format(height / density)} rows=${rows.joinToString(",") { "%.0f".format(it) }} medianRowDp=${"%.1f".format(median)} " +
                "whollyVisible=$wholly headlinesVisible=$headlines imageDp=${"%.0f".format(imageMedian)} cards=${cards.size}",
        )
    }

    private val pictures by lazy { ListHarness.fileImages(instrumentation.targetContext) }

    private fun article(n: Int, title: String, summary: String, image: Boolean = true) = ArticleRow(
        id = "md$n", feedId = "f$n", title = title, summaryText = summary, linkUrl = "https://example.com/$n",
        imageUrl = if (image) pictures[n % pictures.size] else null, author = null, publishedAt = 2_000_000L - n * 60_000L,
        sortAt = 2_000_000L - n * 60_000L, isRead = false, isSaved = false, feedName = "まとめ速報 ${n % 4}", feedUrl = "https://feed.example.com/$n",
        feedSubscribed = true,
    )

    /** Close to the problem: headlines of one to three lines, summaries that are empty, only "read more", or short. */
    private fun problemData(image: (Int) -> Boolean = { true }) = (0 until 24).map { n ->
        val title = when (n % 3) {
            0 -> "短い見出し $n"
            1 -> "二行になる見出しの例です。少し長めの文章で $n"
            else -> "三行になる長い見出しの例です。まとめサイトの記事タイトルはこのくらい長いことがよくあります $n"
        }
        val summary = when (n % 4) {
            0 -> ""
            1 -> "続きを読む"
            2 -> "短い概要です。"
            else -> "通常の概要の文章です。記事の内容を一、二行で説明しています。ここまでが概要です。"
        }
        article(n, title, summary, image(n))
    }

    /** Long headlines and long summaries: nothing useful may be dropped. */
    private fun longData() = (0 until 24).map { n ->
        article(n, "長い見出しの例です。記事タイトルが三行を超えるほど長い場合は省略記号で終わることを確かめるための文章 $n", "長い概要の文章です。".repeat(8) + n)
    }

    @Test
    fun densityCases() {
        val cases = listOf(
            "01-even2-problem" to { show(problemData(), CardLayoutStyle.EVEN, 2, null, 1f, true) },
            "02-mixed2-problem" to { show(problemData(), CardLayoutStyle.MIXED, 2, null, 1f, true) },
            "03-even2-long" to { show(longData(), CardLayoutStyle.EVEN, 2, null, 1f, true) },
            "04-even2-half-images" to { show(problemData { it % 2 == 0 }, CardLayoutStyle.EVEN, 2, null, 1f, true) },
            "05-even2-images-off" to { show(problemData(), CardLayoutStyle.EVEN, 2, null, 1f, false) },
            "06-mixed-560dp-3col" to { show(problemData(), CardLayoutStyle.MIXED, 3, 560, 1f, true) },
            "07-even2-font200" to { show(problemData(), CardLayoutStyle.EVEN, 2, null, 2f, true) },
            "08-mixed-auto-1col" to { show(problemData(), CardLayoutStyle.MIXED, 1, null, 1f, true) },
        )
        for ((name, showIt) in cases) {
            showIt()
            measure(name)
            shot(name)
        }
    }
}
