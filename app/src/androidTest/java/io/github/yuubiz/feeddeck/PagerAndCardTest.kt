package io.github.yuubiz.feeddeck

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.feeddeck.app.MainActivity
import io.github.yuubiz.feeddeck.model.ArticleScope
import io.github.yuubiz.feeddeck.model.ListMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** N01/N03/N24 (category pager) and N09 (card mode) on a device. Uses seeded data only; no network. */
@RunWith(AndroidJUnit4::class)
class PagerAndCardTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var seeded: Map<String, List<String>>
    private val alpha get() = seeded.keys.elementAt(0)
    private val beta get() = seeded.keys.elementAt(1)

    @Before
    fun setUp() {
        TestData.reset()
        seeded = TestData.seedCategories(listOf("Alpha", "Beta"), perCategory = 40)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun lastScope(): ArticleScope = runBlocking { TestData.container.settings.current().lastScope }

    private fun waitForScope(scope: ArticleScope) = compose.waitUntil(5_000) { lastScope() == scope }

    /** A node inside the page of [scope] (the pager may also compose the neighbouring page). */
    private fun inPage(scope: ArticleScope, tag: String) =
        compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_${scope.encode()}")))

    private fun waitInPage(scope: ArticleScope, tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_${scope.encode()}"))).fetchSemanticsNodes().isNotEmpty()
    }

    private fun swipePager(left: Boolean) {
        compose.onNodeWithTag("category_pager").performTouchInput { if (left) swipeLeft() else swipeRight() }
        compose.waitForIdle()
    }

    /** N03: swipe, tab and drawer select the same page; the ends do not wrap around. */
    @Test
    fun swipeAndTabsSelectTheSameCategoryWithoutWrapping() {
        launch()
        waitForTag("category_pager")
        compose.onNodeWithTag("tab_all").assertIsSelected()
        swipePager(left = true)
        waitForScope(ArticleScope.Category(alpha))
        compose.onNodeWithTag("tab_category:$alpha").assertIsSelected()
        swipePager(left = true)
        waitForScope(ArticleScope.Category(beta))
        // Last page: another swipe stays here.
        swipePager(left = true)
        Thread.sleep(500)
        assertEquals(ArticleScope.Category(beta), lastScope())
        compose.onNodeWithTag("tab_category:$beta").assertIsSelected()
        // Tab click moves the pager back.
        compose.onNodeWithTag("tab_all").performClick()
        waitForScope(ArticleScope.All)
        compose.waitForIdle()
        inPage(ArticleScope.All, "article_${seeded.getValue(alpha).first()}").assertIsDisplayed()
        // First page: swiping right stays on All.
        swipePager(left = false)
        Thread.sleep(500)
        assertEquals(ArticleScope.All, lastScope())
    }


    /** The tabs follow the finger: the tab of the page that is more than half in view is selected before the page settles. */
    @Test
    fun tabFollowsTheDragBeforeThePageHasSettled() {
        launch()
        waitForTag("category_pager")
        compose.onNodeWithTag("tab_all").assertIsSelected()
        compose.onNodeWithTag("category_pager").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-width * 0.7f, 0f))
        }
        compose.waitForIdle()
        // Still dragging: nothing has settled or been saved, yet the tab already moved.
        compose.onNodeWithTag("tab_category:$alpha").assertIsSelected()
        assertEquals(ArticleScope.All, lastScope())
        compose.onNodeWithTag("category_pager").performTouchInput { up() }
        waitForScope(ArticleScope.Category(alpha))
        compose.onNodeWithTag("tab_category:$alpha").assertIsSelected()
    }

    /** Back to the top from far down: by the arrow button and by tapping the selected tab again. Nothing is marked read. */
    @Test
    fun arrowButtonAndSelectedTabTakeTheListBackToTheTop() {
        launch()
        waitForTag("category_pager")
        val first = seeded.getValue(alpha).first()
        compose.onNodeWithTag("tab_category:$alpha").performClick()
        waitForScope(ArticleScope.Category(alpha))
        val a = ArticleScope.Category(alpha)
        waitInPage(a, "article_list")
        // Near the top there is no button.
        assertEquals(0, compose.onAllNodes(hasTestTag("scroll_to_top")).fetchSemanticsNodes().size)
        inPage(a, "article_list").performScrollToIndex(30)
        waitInPage(a, "scroll_to_top")
        inPage(a, "scroll_to_top").performClick()
        waitInPage(a, "article_$first")
        inPage(a, "article_$first").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("scroll_to_top")).fetchSemanticsNodes().isEmpty() }

        inPage(a, "article_list").performScrollToIndex(30)
        waitInPage(a, "scroll_to_top")
        compose.onNodeWithTag("tab_category:$alpha").performClick() // the selected tab again
        waitInPage(a, "article_$first")
        inPage(a, "article_$first").assertIsDisplayed()
        assertEquals(ArticleScope.Category(alpha), lastScope())
        assertTrue(seeded.values.flatten().none { TestData.state(it).isRead })
    }


    /** The indicator sits under its own tab: same centre, and wide enough to see, for short and long titles alike. */
    @Test
    fun indicatorIsCentredUnderEachTab() {
        TestData.reset()
        val names = listOf("IT", "ss", "Matome2chNews", "MatomeGeha")
        val ids = TestData.seedCategories(names, perCategory = 3)
        launch()
        waitForTag("category_pager")
        val density = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        for (id in ids.keys) {
            val scope = ArticleScope.Category(id)
            compose.onNodeWithTag("tab_category:$id").performClick()
            waitForScope(scope)
            compose.waitForIdle()
            val tab = compose.onNodeWithTag("tab_category:$id").getUnclippedBoundsInRoot()
            val bar = compose.onNodeWithTag("tab_indicator").getUnclippedBoundsInRoot()
            val tabCentre = (tab.left + tab.right).value / 2
            val barCentre = (bar.left + bar.right).value / 2
            assertTrue("centre of $id: tab $tabCentre vs indicator $barCentre (dp, density $density)", kotlin.math.abs(tabCentre - barCentre) <= 1.5f)
            assertTrue("indicator of $id is at least 20dp wide, was ${(bar.right - bar.left).value}", (bar.right - bar.left).value >= 20f)
            assertTrue("indicator of $id is no wider than its tab", (bar.right - bar.left).value <= (tab.right - tab.left).value + 0.5f)
        }
    }


    // V31-T03

    private fun assertTabAndIndicatorAt(id: String) {
        compose.onNodeWithTag("tab_category:$id").assertIsSelected()
        val tab = compose.onNodeWithTag("tab_category:$id").getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("tab_indicator").getUnclippedBoundsInRoot()
        val d = ((tab.left + tab.right).value - (bar.left + bar.right).value) / 2
        assertTrue("underline of $id is ${d}dp off its tab", kotlin.math.abs(d) <= 1.5f)
    }

    /** A drag that goes to the next page and comes back before the finger is lifted ends on the first page, all agreeing. */
    @Test
    fun dragForwardThenBackLeavesTabUnderlinePageAndSelectionOnTheFirstPage() {
        launch()
        waitForTag("category_pager")
        compose.onNodeWithTag("category_pager").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-width * 0.7f, 0f))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tab_category:$alpha").assertIsSelected()
        compose.onNodeWithTag("category_pager").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(width * 0.6f, 0f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("tab_all").assertIsSelected()
        compose.onNodeWithTag("category_pager").performTouchInput { up() }
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals(ArticleScope.All, lastScope())
        compose.onNodeWithTag("tab_all").assertIsSelected()
        inPage(ArticleScope.All, "article_${seeded.getValue(alpha).first()}").assertIsDisplayed()
    }

    /** Taps in quick succession: the last one wins and tab, underline, page and saved selection agree afterwards. */
    @Test
    fun quickSuccessiveTapsEndOnTheLastTab() {
        launch()
        waitForTag("category_pager")
        compose.onNodeWithTag("tab_category:$alpha").performClick()
        compose.onNodeWithTag("tab_category:$beta").performClick()
        compose.onNodeWithTag("tab_category:$alpha").performClick()
        compose.onNodeWithTag("tab_category:$beta").performClick()
        waitForScope(ArticleScope.Category(beta))
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals(ArticleScope.Category(beta), lastScope())
        assertTabAndIndicatorAt(beta)
        waitInPage(ArticleScope.Category(beta), "article_${seeded.getValue(beta).first()}")
        inPage(ArticleScope.Category(beta), "article_${seeded.getValue(beta).first()}").assertIsDisplayed()
    }

    /** Back to the top in every list style; other categories keep their place and no article changes state. */
    private fun backToTop(mode: ListMode, style: io.github.yuubiz.feeddeck.model.CardLayoutStyle) {
        runBlocking {
            TestData.container.settings.setListMode(mode)
            TestData.container.settings.setCardStyle(style)
        }
        launch()
        waitForTag("category_pager")
        val a = ArticleScope.Category(alpha)
        val b = ArticleScope.Category(beta)
        compose.onNodeWithTag("tab_category:$beta").performClick()
        waitForScope(b)
        waitInPage(b, "article_list")
        inPage(b, "article_list").performScrollToIndex(20)
        val betaAnchor = seeded.getValue(beta)[20]
        inPage(b, "article_$betaAnchor").assertIsDisplayed()

        compose.onNodeWithTag("tab_category:$alpha").performClick()
        waitForScope(a)
        waitInPage(a, "article_list")
        inPage(a, "article_list").performScrollToIndex(30)
        waitInPage(a, "scroll_to_top")
        inPage(a, "scroll_to_top").performClick()
        val first = seeded.getValue(alpha).first()
        waitInPage(a, "article_$first")
        inPage(a, "article_$first").assertIsDisplayed()

        compose.onNodeWithTag("tab_category:$beta").performClick()
        waitForScope(b)
        waitInPage(b, "article_$betaAnchor")
        inPage(b, "article_$betaAnchor").assertIsDisplayed()
        assertTrue(seeded.values.flatten().none { TestData.state(it).isRead })
        assertTrue(seeded.values.flatten().none { TestData.state(it).isSaved })
    }

    @Test
    fun backToTopInTheMixedCardGrid() = backToTop(ListMode.CARD, io.github.yuubiz.feeddeck.model.CardLayoutStyle.MIXED)

    @Test
    fun backToTopInTheEvenCardGrid() = backToTop(ListMode.CARD, io.github.yuubiz.feeddeck.model.CardLayoutStyle.EVEN)

    @Test
    fun backToTopInTheMagazineList() = backToTop(ListMode.MAGAZINE, io.github.yuubiz.feeddeck.model.CardLayoutStyle.MIXED)

    @Test
    fun backToTopInTheColumnList() = backToTop(ListMode.LIST, io.github.yuubiz.feeddeck.model.CardLayoutStyle.MIXED)


    /** A tab far from the first one (the row scrolls to it): tab, underline, page and saved selection agree, also with large text. */
    @Test
    fun aDistantTabIsReachedWithLargeTextToo() {
        TestData.reset()
        runBlocking { TestData.container.settings.setTextScale(2.0f) }
        val many = TestData.seedCategories(listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta"), perCategory = 5)
        val last = many.keys.last()
        launch()
        waitForTag("category_pager")
        compose.onNodeWithTag("tab_category:$last").performClick()
        waitForScope(ArticleScope.Category(last))
        compose.waitForIdle()
        Thread.sleep(500)
        assertTabAndIndicatorAt(last)
        val first = many.getValue(last).first()
        waitInPage(ArticleScope.Category(last), "article_$first")
        inPage(ArticleScope.Category(last), "article_$first").assertIsDisplayed()
        runBlocking { TestData.container.settings.setTextScale(1.0f) }
    }

    /** N01 + N24: each category keeps its own position across swipes and tab switches; nothing is marked read. */
    @Test
    fun eachCategoryKeepsItsOwnPositionAndNothingIsMarkedRead() {
        launch()
        waitForTag("category_pager")
        compose.onNodeWithTag("tab_category:$alpha").performClick()
        waitForScope(ArticleScope.Category(alpha))
        compose.waitForIdle()
        val a = ArticleScope.Category(alpha)
        val b = ArticleScope.Category(beta)
        waitInPage(a, "article_list")
        inPage(a, "article_list").performScrollToIndex(30)
        val alphaAnchor = seeded.getValue(alpha)[30]
        inPage(a, "article_$alphaAnchor").assertIsDisplayed()

        compose.onNodeWithTag("tab_category:$beta").performClick()
        waitForScope(ArticleScope.Category(beta))
        compose.waitForIdle()
        waitInPage(b, "article_list")
        inPage(b, "article_list").performScrollToIndex(10)
        val betaAnchor = seeded.getValue(beta)[10]
        inPage(b, "article_$betaAnchor").assertIsDisplayed()

        swipePager(left = false) // back to Alpha by swipe
        waitForScope(ArticleScope.Category(alpha))
        waitInPage(a, "article_$alphaAnchor")
        inPage(a, "article_$alphaAnchor").assertIsDisplayed()

        compose.onNodeWithTag("tab_category:$beta").performClick() // and to Beta by tab
        waitForScope(ArticleScope.Category(beta))
        waitInPage(b, "article_$betaAnchor")
        inPage(b, "article_$betaAnchor").assertIsDisplayed()

        assertTrue(seeded.values.flatten().none { TestData.state(it).isRead })
    }

    /** N09: card mode from the menu, persisted, same article area kept; images off → no image area. */
    @Test
    fun cardModeIsSelectableAndPersisted() {
        launch()
        waitForTag("category_pager")
        waitInPage(ArticleScope.All, "article_list")
        inPage(ArticleScope.All, "article_list").performScrollToIndex(5)
        compose.onNodeWithTag("home_more").performClick()
        compose.onNodeWithTag("menu_mode_CARD").performClick()
        compose.waitUntil(5_000) { runBlocking { TestData.container.settings.current().listMode } == ListMode.CARD }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("card_image", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        scenario?.recreate()
        waitForTag("article_list")
        assertEquals(ListMode.CARD, runBlocking { TestData.container.settings.current().listMode })
        assertFalse(compose.onAllNodesWithTag("article_list").fetchSemanticsNodes().isEmpty())
    }
}
