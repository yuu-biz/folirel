package io.github.yuubiz.folirel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
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
import java.io.File

typealias HomeRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

/**
 * Hosts the real article list (the composable the home screen uses) in a [ComposeView] of its own, with a recording
 * [ListHost]. Articles and new-article markers are plain state the test changes, the way the view model's flows do.
 */
class ListHarness(private val compose: HomeRule) {
    companion object {
        fun fileImages(context: Context): List<String> = fileImagesImpl(context)
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** What the view model does with the list's callbacks. */
    inner class Host : ListHost {
        val anchors = HashMap<String, ListAnchor>()

        /** Every change of the first visible article with the time it was seen (nanoTime): the position trace of a run in real time. */
        val anchorLog = java.util.Collections.synchronizedList(ArrayList<Triple<Long, String, Int>>())
        val acknowledged = ArrayList<String>()
        override fun anchorFor(listKey: String) = anchors[listKey]
        override fun saveAnchor(anchor: ListAnchor) {
            anchors[anchor.listKey] = anchor
            anchorLog += Triple(System.nanoTime(), anchor.articleId, anchor.offset)
        }
        override fun acknowledgeNew(ids: Collection<String>) {
            if (ids.isEmpty()) return
            acknowledged += ids
            newIds = newIds - ids.toSet()
        }
    }

    val host = Host()
    var items by mutableStateOf<List<ArticleRow>>(emptyList())
    var newIds by mutableStateOf<Set<String>>(emptySet())
    var newestFirst by mutableStateOf(true)
    var isCurrent by mutableStateOf(true)
    var topTick by mutableStateOf(0)
    var listKey by mutableStateOf("test-list")
    var readCalls = 0
    var openCalls = 0

    fun row(n: Int, imageUrl: String? = null) = ArticleRow(
        id = "a$n", feedId = "f", title = "記事 $n", summaryText = "概要 $n", linkUrl = "https://example.com/$n", imageUrl = imageUrl,
        author = null, publishedAt = 1_000_000L - n, sortAt = 1_000_000L - n, isRead = false, isSaved = false,
        feedName = "feed", feedUrl = "https://feed.example.com/", feedSubscribed = true,
    )

    /** Like real feeds: titles of one to three lines, summaries of none to five; every [imageEvery]th article has a picture. */
    fun variedRow(n: Int, images: List<String> = emptyList(), imageEvery: Int = 2) = row(n, images.takeIf { it.isNotEmpty() && Math.floorMod(n, imageEvery) == 0 }?.let { it[Math.floorMod(n, it.size)] }).copy(
        title = "記事 $n " + "見出しの文章が長くなると行数が増える ".repeat(Math.floorMod(n, 3)),
        summaryText = if (Math.floorMod(n, 6) == 5) "" else "概要 $n " + "本文の冒頭が続きます ".repeat(Math.floorMod(n, 6) * 3),
    )

    /** Real PNG files of different shapes; the list loads them asynchronously like downloaded pictures. */
    fun makeImages(): List<String> = fileImages(context)

    /** [widthDp]: host the list in a column of that width (a wide window, to get three Magazine columns on a phone). */
    fun show(mode: ListMode, list: List<ArticleRow>, showImages: Boolean = false, cardColumns: Int = 0, widthDp: Int? = null) {
        items = list
        val actions = ArticleActions(
            open = { openCalls++ }, openExternal = {}, toggleSaved = {}, setRead = { _, _ -> readCalls++ }, share = {}, showSummary = {},
        )
        val loader = ImageLoader.Builder(context).build()
        // MainActivity already has its content; this list replaces it with a ComposeView of its own.
        compose.runOnUiThread {
            val view = ComposeView(compose.activity)
            compose.activity.setContentView(view)
            view.setContent {
                FolirelTheme(ThemeMode.SYSTEM, 1.0f) {
                    Box(if (widthDp != null) Modifier.fillMaxHeight().width(widthDp.dp) else Modifier.fillMaxSize()) {
                        ArticleList(
                            host = host,
                            state = ArticlesState(listKey, items, loaded = true),
                            mode = mode,
                            showImages = showImages,
                            cardColumns = cardColumns,
                            cardStyle = CardLayoutStyle.MIXED,
                            imageLoader = loader,
                            actions = actions,
                            newIds = newIds,
                            newestFirst = newestFirst,
                            isCurrent = isCurrent,
                            scrollToTopTick = topTick,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // --- reading the screen ---

    private val articleNode = SemanticsMatcher("article card") {
        val tag = it.config.getOrNull(SemanticsProperties.TestTag)
        tag != null && tag.startsWith("article_") && tag != "article_list"
    }

    fun displayed(id: String) = compose.onAllNodes(hasTestTag("article_$id")).fetchSemanticsNodes().isNotEmpty()

    fun bannerShown() = compose.onAllNodes(hasTestTag("new_articles_banner")).fetchSemanticsNodes().isNotEmpty()

    /** One observation of the list: the list's own scroll position and where every card on screen sits (px from the list top). */
    class Frame(val axis: Float, val tops: Map<String, Float>, val heights: Map<String, Float>, val listHeight: Float)

    fun sample(): Frame {
        val list = compose.onNodeWithTag("article_list").fetchSemanticsNode()
        val axis = list.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: -1f
        // boundsInRoot is clipped by the list, which pins a half-hidden card to 0; the unclipped position is what moves.
        val top = list.positionInRoot.y
        val height = list.size.height.toFloat()
        val tops = LinkedHashMap<String, Float>()
        val heights = HashMap<String, Float>()
        for (node in compose.onAllNodes(articleNode).fetchSemanticsNodes()) {
            val t = node.positionInRoot.y - top
            val h = node.size.height.toFloat()
            if (t + h <= 0f || t >= height) continue
            val id = node.config[SemanticsProperties.TestTag].removePrefix("article_")
            // A row can be reported by more than one node (the card and an inner node); keep the first.
            if (id !in tops) {
                tops[id] = t
                heights[id] = h
            }
        }
        return Frame(axis, tops, heights, height)
    }
}

/** Real PNG files of different shapes in the app's cache; the list loads them asynchronously like downloaded pictures. */
private fun fileImagesImpl(context: Context): List<String> {
    val dir = File(context.cacheDir, "uxr-images").apply { mkdirs() }
    val sizes = listOf(640 to 360, 800 to 450, 600 to 600, 480 to 640, 1024 to 400, 320 to 180)
    return sizes.mapIndexed { i, (w, h) ->
        val file = File(dir, "img-$i.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.HSVToColor(floatArrayOf(i * 57f % 360, 0.55f, 0.85f)) }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.color = Color.WHITE
            canvas.drawCircle(w / 2f, h / 2f, h / 4f, paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        "file://${file.absolutePath}"
    }
}
