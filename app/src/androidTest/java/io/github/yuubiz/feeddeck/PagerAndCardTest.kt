package io.github.yuubiz.feeddeck

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
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
