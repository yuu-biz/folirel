package io.github.yuubiz.folirel

import android.content.Context
import io.github.yuubiz.folirel.app.MainActivity
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
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.ui.home.ArticleActions
import io.github.yuubiz.folirel.ui.home.ArticleList
import io.github.yuubiz.folirel.ui.home.ArticlesState
import io.github.yuubiz.folirel.ui.home.ListAnchor
import io.github.yuubiz.folirel.ui.home.ListHost
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
    private var topTick by mutableStateOf(0)
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
            io.github.yuubiz.folirel.ui.theme.FolirelTheme(io.github.yuubiz.folirel.data.preferences.ThemeMode.SYSTEM, 1.0f) {
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
                    scrollToTopTick = topTick,
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
    fun aMarkerAddedAfterTheArticleIsShownDoesNotLeaveABannerInTheMagazineLayout() = lateMarker(ListMode.MAGAZINE)

    @Test
    fun aMarkerAddedAfterTheArticleIsShownDoesNotLeaveABannerInTheListLayout() = lateMarker(ListMode.LIST)

    @Test
    fun aMarkerAddedAfterTheArticleIsShownDoesNotLeaveABannerInTheCompactListLayout() = lateMarker(ListMode.COMPACT_LIST)

    @Test
    fun offScreenNewArticlesAreAnnouncedAndTheBannerJumpsToThemNewestFirst() {
        show(ListMode.MAGAZINE, articles(80))
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
        show(ListMode.COMPACT_LIST, articles(80).reversed())
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
        show(ListMode.MAGAZINE, articles(60))
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
    fun articlesArrivingAtTheHeadKeepTheReadingPlaceInTheMagazineLayout() = headArrival(ListMode.MAGAZINE)

    @Test
    fun articlesArrivingAtTheHeadKeepTheReadingPlaceInTheCompactListLayout() = headArrival(ListMode.COMPACT_LIST)

    /** Where the first article sits relative to the list: 0 when the list is really at its top (not a few articles short of it). */
    /** Like real feeds: titles of one to three lines, summaries of none to five. */
    private fun variedRow(n: Int) = row(n).copy(
        title = "記事 $n " + "見出しの文章が長くなると行数が増える ".repeat(Math.floorMod(n, 3)),
        summaryText = if (Math.floorMod(n, 6) == 5) "" else "概要 $n " + "本文の冒頭が続きます ".repeat(Math.floorMod(n, 6) * 3),
    )

    private fun firstArticleGap(firstId: String): Float {
        val list = compose.onNodeWithTag("article_list").fetchSemanticsNode().boundsInRoot.top
        return compose.onNodeWithTag("article_$firstId").fetchSemanticsNode().boundsInRoot.top - list
    }

    private fun assertAtTop(label: String, firstId: String) {
        compose.waitUntil(5_000) { displayed(firstId) }
        val gap = firstArticleGap(firstId)
        assertTrue("$label: $firstId is the first article but sits ${gap}px from the top of the list", gap in 0f..24f * 4)
    }

    private fun farDownThenArrivals(mode: ListMode): String {
        show(mode, (0 until BIG).map(::variedRow))
        compose.onNodeWithTag("article_list").performScrollToIndex(BIG - 400)
        compose.waitForIdle()
        val arrived = (-5..-1).map { variedRow(it) }
        items = arrived + items
        newIds = arrived.map { it.id }.toSet()
        compose.waitForIdle()
        return arrived.first().id
    }

    private fun backToTopButtonAfterArrivals(mode: ListMode) {
        val first = farDownThenArrivals(mode)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("scroll_to_top")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("scroll_to_top").performClick()
        compose.waitForIdle()
        assertAtTop("$mode button", first)
    }

    private fun backToTopTabTapAfterArrivals(mode: ListMode) {
        val first = farDownThenArrivals(mode)
        topTick++
        compose.waitForIdle()
        assertAtTop("$mode tab", first)
    }

    /** More articles arrive while the animation back to the top is running. */
    private fun backToTopWhileArticlesArrive(mode: ListMode) {
        show(mode, (0 until BIG).map(::variedRow))
        compose.onNodeWithTag("article_list").performScrollToIndex(BIG - 400)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            topTick++
            compose.mainClock.advanceTimeBy(150)
            val arrived = (-5..-1).map { variedRow(it) }
            items = arrived + items
            newIds = arrived.map { it.id }.toSet()
            compose.mainClock.advanceTimeBy(100)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertAtTop("$mode during arrivals", "a-5")
    }

    /** A refresh delivers feed after feed: the list is replaced again and again while the way back to the top is animated. */
    private fun backToTopWhileManyBatchesArrive(mode: ListMode) {
        show(mode, (0 until BIG).map(::variedRow))
        compose.onNodeWithTag("article_list").performScrollToIndex(BIG - 400)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        var next = -1
        try {
            topTick++
            repeat(12) {
                compose.mainClock.advanceTimeBy(48)
                val arrived = (next - 3 until next + 1).map { variedRow(it) }
                next -= 4
                items = arrived + items
                newIds = newIds + arrived.map { it.id }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertAtTop("$mode many batches", items.first().id)
    }

    @Test fun backToTopWhileManyBatchesArriveInTheListLayout() = backToTopWhileManyBatchesArrive(ListMode.LIST)
    @Test fun backToTopWhileManyBatchesArriveInTheCompactListLayout() = backToTopWhileManyBatchesArrive(ListMode.COMPACT_LIST)
    @Test fun backToTopWhileManyBatchesArriveInTheMagazineLayout() = backToTopWhileManyBatchesArrive(ListMode.MAGAZINE)

    @Test fun backToTopButtonAfterArrivalsInTheListLayout() = backToTopButtonAfterArrivals(ListMode.LIST)
    @Test fun backToTopButtonAfterArrivalsInTheCompactListLayout() = backToTopButtonAfterArrivals(ListMode.COMPACT_LIST)
    @Test fun backToTopButtonAfterArrivalsInTheMagazineLayout() = backToTopButtonAfterArrivals(ListMode.MAGAZINE)
    @Test fun backToTopTabTapAfterArrivalsInTheListLayout() = backToTopTabTapAfterArrivals(ListMode.LIST)
    @Test fun backToTopTabTapAfterArrivalsInTheCompactListLayout() = backToTopTabTapAfterArrivals(ListMode.COMPACT_LIST)
    @Test fun backToTopTabTapAfterArrivalsInTheMagazineLayout() = backToTopTabTapAfterArrivals(ListMode.MAGAZINE)
    @Test fun backToTopWhileArticlesArriveInTheListLayout() = backToTopWhileArticlesArrive(ListMode.LIST)
    @Test fun backToTopWhileArticlesArriveInTheCompactListLayout() = backToTopWhileArticlesArrive(ListMode.COMPACT_LIST)
    @Test fun backToTopWhileArticlesArriveInTheMagazineLayout() = backToTopWhileArticlesArrive(ListMode.MAGAZINE)

    private companion object { const val BIG = 3000 }
}
