package io.github.yuubiz.feeddeck

import android.content.Context
import io.github.yuubiz.feeddeck.app.MainActivity
import org.junit.Before
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import io.github.yuubiz.feeddeck.model.ArticleRow
import io.github.yuubiz.feeddeck.model.CardLayoutStyle
import io.github.yuubiz.feeddeck.model.ListMode
import io.github.yuubiz.feeddeck.ui.home.ArticleActions
import io.github.yuubiz.feeddeck.ui.home.ArticleList
import io.github.yuubiz.feeddeck.ui.home.ArticlesState
import io.github.yuubiz.feeddeck.ui.home.ListAnchor
import io.github.yuubiz.feeddeck.ui.home.ListHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V31-T02 / V3-R03 on a device, through the real article list (the composable the home screen uses) with the real
 * LazyGrid / LazyColumn: the new-article banner, its consumption by what is on screen, the jump in both sort
 * orders, the pre-composed neighbour page, and the reading position when articles arrive at the head. The list is
 * hosted with a recording [ListHost]; nothing here can mark an article read (the actions count every call).
 */
@RunWith(AndroidJUnit4::class)
class NewArticleBannerTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun quietApp() {
        // The app behind the replaced content must not start any network work.
        TestData.reset()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** What the view model does with the list's callbacks. */
    private inner class Host : ListHost {
        val anchors = HashMap<String, ListAnchor>()
        val seen = ArrayList<String>()
        override fun anchorFor(listKey: String) = anchors[listKey]
        override fun saveAnchor(anchor: ListAnchor) {
            anchors[anchor.listKey] = anchor
        }
        override fun markNewSeen(ids: Collection<String>) {
            if (ids.isEmpty()) return
            seen += ids
            newIds = newIds - ids.toSet()
        }
    }

    private val host = Host()
    private var items by mutableStateOf<List<ArticleRow>>(emptyList())
    private var newIds by mutableStateOf<Set<String>>(emptySet())
    private var newestFirst by mutableStateOf(true)
    private var isCurrent by mutableStateOf(true)
    private var readCalls = 0

    private fun row(n: Int) = ArticleRow(
        id = "a$n", feedId = "f", title = "記事 $n", summaryText = "概要 $n", linkUrl = "https://example.com/$n", imageUrl = null,
        author = null, publishedAt = 1_000_000L - n, sortAt = 1_000_000L - n, isRead = false, isSaved = false,
        feedName = "feed", feedUrl = "https://feed.example.com/", feedSubscribed = true,
    )

    /** a0 is the newest. */
    private fun articles(count: Int) = (0 until count).map(::row)

    private fun show(mode: ListMode, list: List<ArticleRow>) {
        items = list
        val actions = ArticleActions(
            open = {}, openExternal = {}, toggleSaved = {}, setRead = { _, _ -> readCalls++ }, share = {}, showSummary = {},
        )
        // MainActivity already has its content; this list replaces it with a ComposeView of its own.
        compose.runOnUiThread {
            val view = androidx.compose.ui.platform.ComposeView(compose.activity)
            compose.activity.setContentView(view)
            view.setContent {
            io.github.yuubiz.feeddeck.ui.theme.FeedDeckTheme(io.github.yuubiz.feeddeck.data.preferences.ThemeMode.SYSTEM, 1.0f) {
                ArticleList(
                    host = host,
                    state = ArticlesState("test-list", items, loaded = true),
                    mode = mode,
                    showImages = false,
                    cardColumns = 0,
                    cardStyle = CardLayoutStyle.MIXED,
                    imageLoader = ImageLoader.Builder(context).build(),
                    actions = actions,
                    newIds = newIds,
                    newestFirst = newestFirst,
                    isCurrent = isCurrent,
                    scrollToTopTick = 0,
                )
            }
            }
        }
        compose.waitForIdle()
    }

    private fun bannerShown() = compose.onAllNodes(hasTestTag("new_articles_banner")).fetchSemanticsNodes().isNotEmpty()

    private fun waitForBanner(shown: Boolean) = compose.waitUntil(5_000) { bannerShown() == shown }

    private fun isInView(id: String) = runCatching { compose.onNodeWithTag("article_$id").assertIsDisplayed() }.isSuccess

    private fun displayed(id: String) = compose.onAllNodes(hasTestTag("article_$id")).fetchSemanticsNodes().isNotEmpty()

    private fun lateMarker(mode: ListMode) {
        show(mode, articles(60))
        assertTrue("$mode: a1 is on screen", displayed("a1"))
        // The slower feed's request completes afterwards: its articles on screen get their markers.
        newIds = setOf("a1", "a2")
        compose.waitForIdle()
        waitForBanner(false)
        assertEquals("$mode", setOf("a1", "a2"), host.seen.toSet())
        assertEquals(0, readCalls)
    }

    @Test
    fun aMarkerAddedAfterTheArticleIsShownDoesNotLeaveABannerInTheCardGrid() = lateMarker(ListMode.CARD)

    @Test
    fun aMarkerAddedAfterTheArticleIsShownDoesNotLeaveABannerInTheMixedMagazineList() = lateMarker(ListMode.MAGAZINE)

    @Test
    fun aMarkerAddedAfterTheArticleIsShownDoesNotLeaveABannerInTheColumnList() = lateMarker(ListMode.LIST)

    @Test
    fun offScreenNewArticlesAreAnnouncedAndTheBannerJumpsToThemNewestFirst() {
        show(ListMode.CARD, articles(80))
        newIds = setOf("a60", "a61")
        compose.waitForIdle()
        waitForBanner(true)
        assertTrue("a60 is not on screen yet", !displayed("a60"))
        assertTrue("nothing was consumed", host.seen.isEmpty())
        compose.onNodeWithTag("new_articles_banner").performClick()
        compose.waitUntil(5_000) { displayed("a60") }
        compose.onNodeWithTag("article_a60").assertIsDisplayed()
        waitForBanner(false)
        assertTrue(host.seen.contains("a60"))
        assertEquals("opening or jumping never marks anything read", 0, readCalls)
    }

    @Test
    fun theBannerJumpsToTheFirstNewArticleOfTheCurrentOrderWhenOldestFirst() {
        newestFirst = false
        // Oldest first: the list is a79 ... a0; the new (newest) ones are at the end.
        show(ListMode.LIST, articles(80).reversed())
        newIds = setOf("a0", "a1")
        compose.waitForIdle()
        waitForBanner(true)
        compose.onNodeWithTag("new_articles_banner").performClick()
        compose.waitUntil(5_000) { displayed("a1") }
        waitForBanner(false)
        assertEquals(0, readCalls)
    }

    @Test
    fun aPreComposedNeighbourPageDoesNotConsumeMarkers() {
        isCurrent = false
        show(ListMode.CARD, articles(60))
        newIds = setOf("a1")
        compose.waitForIdle()
        assertTrue("announced, and a1 is composed on screen", bannerShown() && displayed("a1"))
        assertTrue("but not consumed: the page is not the current one", host.seen.isEmpty())
        // The page settles: now it is shown, and the marker goes.
        isCurrent = true
        compose.waitForIdle()
        waitForBanner(false)
        assertEquals(listOf("a1"), host.seen)
    }

    private fun headArrival(mode: ListMode) {
        show(mode, articles(80))
        compose.onNodeWithTag("article_list").performScrollToIndex(40)
        compose.waitForIdle()
        // The article the reader has at the top of the screen: the first one that is really in view (a composed neighbour is not).
        val reading = (30..50).map { "a$it" }.first { id -> isInView(id) }
        // Three newer articles arrive at the head, announced as new.
        val arrived = (-3..-1).map { row(it) }
        items = arrived + items
        newIds = arrived.map { it.id }.toSet()
        compose.waitForIdle()
        assertTrue("$mode: still reading $reading", isInView(reading))
        compose.onNodeWithTag("article_$reading").assertIsDisplayed()
        waitForBanner(true)
        assertTrue("$mode: the new ones are off screen and announced, not consumed", host.seen.isEmpty())
        assertEquals(0, readCalls)
    }

    @Test
    fun articlesArrivingAtTheHeadKeepTheReadingPlaceInTheCardGrid() = headArrival(ListMode.CARD)

    @Test
    fun articlesArrivingAtTheHeadKeepTheReadingPlaceInTheColumnList() = headArrival(ListMode.LIST)
}
