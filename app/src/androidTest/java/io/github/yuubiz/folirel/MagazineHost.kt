package io.github.yuubiz.folirel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
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

/**
 * The real article list in Magazine layout, hosted with a chosen width, text size, arrangement and column choice (Magazine density
 * tests). Articles are made here; pictures are real PNG files that load asynchronously.
 */
class MagazineHost(private val compose: HomeRule) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val pictures: List<String> by lazy { ListHarness.fileImages(instrumentation.targetContext) }
    val density: Float get() = instrumentation.targetContext.resources.displayMetrics.density

    private val host = object : ListHost {
        val anchors = HashMap<String, ListAnchor>()
        override fun anchorFor(listKey: String): ListAnchor? = anchors[listKey]
        override fun saveAnchor(anchor: ListAnchor) {
            anchors[anchor.listKey] = anchor
        }
        override fun acknowledgeNew(ids: Collection<String>) = Unit
    }

    fun article(n: Int, title: String, summary: String, image: String? = pictures[n % pictures.size], read: Boolean = false) = ArticleRow(
        id = "md$n", feedId = "f$n", title = title, summaryText = summary, linkUrl = "https://example.com/$n",
        imageUrl = image, author = null, publishedAt = 2_000_000L - n * 60_000L, sortAt = 2_000_000L - n * 60_000L,
        isRead = read, isSaved = false, feedName = "まとめ速報 ${n % 4}", feedUrl = "https://feed.example.com/$n", feedSubscribed = true,
    )

    fun show(
        items: List<ArticleRow>,
        style: CardLayoutStyle = CardLayoutStyle.EVEN,
        columns: Int = 2,
        widthDp: Int? = null,
        fontScale: Float = 1f,
        showImages: Boolean = true,
        listKey: String = "md",
        mode: ListMode = ListMode.MAGAZINE,
    ) {
        val loader = ImageLoader.Builder(instrumentation.targetContext).build()
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
                                state = ArticlesState(listKey, items, loaded = true),
                                mode = mode,
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
    }

    private fun rect(node: SemanticsNode) =
        Rect(node.positionInRoot.x, node.positionInRoot.y, node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)

    /** Every card on screen, by article id, in px (root coordinates). */
    fun cards(): Map<String, Rect> = compose.onAllNodes(SemanticsMatcher("card") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.let { t -> t.startsWith("article_") && t != "article_list" } == true
    }).fetchSemanticsNodes().associate { it.config[SemanticsProperties.TestTag].removePrefix("article_") to rect(it) }

    /** Nodes with [tag] inside the card of [id] (unmerged: pictures and summaries are parts of the card). */
    fun partsOf(id: String, tag: String): List<Rect> {
        val card = cards()[id] ?: return emptyList()
        return compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { rect(it) }
            .filter { it.center.x in card.left..card.right && it.center.y in card.top..card.bottom }
    }

    /** The summary texts shown in the card of [id]. */
    fun summaryOf(id: String): List<String> {
        val card = cards()[id] ?: return emptyList()
        return compose.onAllNodes(hasTestTag("card_summary"), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { rect(it).center.x in card.left..card.right && rect(it).center.y in card.top..card.bottom }
            .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() }
    }

    fun dp(px: Float) = px / density
}
