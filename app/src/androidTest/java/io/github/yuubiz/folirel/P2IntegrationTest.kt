package io.github.yuubiz.folirel

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.CategoryDisplay
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.ResolvedLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2: Settings and the category settings are one system (global default, category override, the same picker and names), and an
 * appearance change is only a stored setting: no refresh, no read or saved change, no move of the category or the scroll position.
 */
@RunWith(AndroidJUnit4::class)
class P2IntegrationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var seeded: Map<String, List<String>>
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val c get() = TestData.container
    private val repo get() = c.subscriptions
    private val settings get() = c.settings

    private val alpha get() = ArticleScope.Category(seeded.keys.elementAt(0))
    private val beta get() = ArticleScope.Category(seeded.keys.elementAt(1))
    private val gamma get() = ArticleScope.Category(seeded.keys.elementAt(2))

    @Before
    fun seed() {
        TestData.reset()
        seeded = TestData.seedCategories(listOf("Alpha", "Beta", "Gamma"), perCategory = 40)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        until { exists("category_pager") }
        compose.waitForIdle()
        Thread.sleep(500)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun until(timeoutMs: Long = 10_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs) { condition() }
    private fun click(tag: String) {
        until { exists(tag) }
        // Not every control is in something that scrolls (the top bar); where it is, bring it into view first.
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun back() {
        device.pressBack()
        compose.waitForIdle()
    }

    private fun openSettingsPage(page: String) {
        click("drawer_open")
        click("drawer_item_settings")
        until { exists("settings_top") }
        click("settings_row_$page")
        until { exists("settings_page_$page") }
    }

    private fun backToHome() {
        while (!exists("category_pager")) back()
        compose.waitForIdle()
    }

    private fun account() = runBlocking { repo.ensureLocalAccount() }
    private fun prefs() = runBlocking { repo.observeStructure(account()).first().preferences }
    private fun page(scope: ArticleScope) = hasTestTag("page_${scope.encode()}")

    private fun firstArticleHeight(scope: ArticleScope, id: String): Int? =
        compose.onAllNodes(hasTestTag("article_$id") and hasAnyAncestor(page(scope))).fetchSemanticsNodes().firstOrNull()?.size?.height

    private fun isGrid(scope: ArticleScope): Boolean {
        val pageWidth = compose.onAllNodes(page(scope)).fetchSemanticsNodes().firstOrNull()?.size?.width ?: return false
        val cards = compose.onAllNodes(
            hasAnyAncestor(page(scope)) and SemanticsMatcher("an article") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.let { tag -> tag.startsWith("article_") && tag != "article_list" } == true
            },
        ).fetchSemanticsNodes()
        return cards.isNotEmpty() && cards.any { it.size.width < pageWidth * 0.8f }
    }

    private fun waitForPanelTag(tag: String) = until(5_000) { exists(tag) }

    /** P2-T25 and P2-T26: the default changes in Settings; a category that inherits follows it, one with its own layout does not. */
    @Test
    fun aChangeOfTheDefaultInSettingsReachesInheritingCategoriesAndLeavesOverriddenOnesAlone() {
        runBlocking {
            repo.setCategoryListLayout(account(), beta.categoryId, ListMode.COMPACT_LIST)
            settings.setListMode(ListMode.LIST)
            settings.setCardColumns(2)
            settings.setLastScope(alpha)
        }
        launch()
        val firstAlpha = seeded.getValue(alpha.categoryId).first()
        val firstBeta = seeded.getValue(beta.categoryId).first()
        until { firstArticleHeight(alpha, firstAlpha) != null }
        assertTrue("Alpha inherits the list", !isGrid(alpha))
        openSettingsPage("LAYOUT")
        click("settings_layout_MAGAZINE")
        until(5_000) { runBlocking { settings.current().listMode } == ListMode.MAGAZINE }
        backToHome()
        Thread.sleep(600)
        until { isGrid(alpha) }
        compose.onNodeWithTag("category_pager").performTouchInput { swipeLeft() }
        until { firstArticleHeight(beta, firstBeta) != null }
        assertTrue("Beta keeps its own compact list", !isGrid(beta))
        assertEquals(ListMode.COMPACT_LIST, prefs().getValue(beta.categoryId).listLayout)
        assertEquals("Alpha has no row of its own", null, prefs()[alpha.categoryId]?.listLayout)
        assertEquals(ListMode.MAGAZINE, runBlocking { settings.current().listMode })
        // The same data the display panel and the category settings resolve.
        val global = ResolvedLayout(ListMode.MAGAZINE, CardLayoutStyle.MIXED, 2)
        assertEquals(ListMode.MAGAZINE, CategoryDisplay.resolve(alpha, global, prefs()).listMode)
        assertEquals(ListMode.COMPACT_LIST, CategoryDisplay.resolve(beta, global, prefs()).listMode)
    }

    /** P2-T27 to T29: the category settings use the same picker; "use the default" is a choice of its own, and so is Automatic. */
    @Test
    fun theCategorySettingsOfferAllFiveLayoutsAndKeepInheritAndAutomaticApart() {
        runBlocking { settings.setLastScope(beta) }
        launch()
        click("display_open")
        click("layout_details")
        until { exists("category_layout_sheet") }
        for (mode in ListMode.entries) {
            click("cl_list_${mode.name}")
            until(5_000) { prefs()[beta.categoryId]?.listLayout == mode }
            compose.onNodeWithTag("cl_list_${mode.name}").assertIsSelected()
        }
        click("cl_list_INHERIT")
        until(5_000) { prefs()[beta.categoryId]?.listLayout == null }
        compose.onNodeWithTag("cl_list_INHERIT").assertIsSelected()
        // Magazine: the arrangement and the columns appear; Automatic (0) is not "inherit" (null).
        click("cl_list_MAGAZINE")
        until(5_000) { exists("cl_style_EVEN") }
        click("cl_style_EVEN")
        click("cl_cols_row")
        until { exists("cl_cols_0") }
        click("cl_cols_0")
        until(5_000) { prefs()[beta.categoryId]?.cardColumns == 0 }
        click("cl_cols_row")
        click("cl_cols_INHERIT")
        until(5_000) { prefs()[beta.categoryId]?.cardColumns == null }
        // Masonry and Bento do not show the Magazine arrangement; a list shows no columns either; the choices stay stored.
        for (mode in listOf(ListMode.MASONRY, ListMode.BENTO)) {
            click("cl_list_${mode.name}")
            until(5_000) { !exists("cl_style_EVEN") }
            assertTrue("$mode keeps the columns row", exists("cl_cols_row"))
            assertTrue(exists("cl_style_not_used"))
        }
        click("cl_list_LIST")
        until(5_000) { !exists("cl_cols_row") }
        assertEquals("the hidden arrangement is kept", CardLayoutStyle.EVEN, prefs().getValue(beta.categoryId).cardStyle)
        click("cl_reset")
        until(5_000) { prefs()[beta.categoryId]?.overridesLayout != true }
    }

    /** P2-T30: the home panel and Settings name the layouts alike, in the same order. */
    @Test
    fun theHomePanelAndSettingsShowTheSameLayoutsWithTheSameNamesInTheSameOrder() {
        launch()
        openSettingsPage("LAYOUT")
        fun describe(prefix: String) = ListMode.entries.map { m ->
            val node = compose.onNodeWithTag("$prefix${m.name}").fetchSemanticsNode()
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            Triple(m, text, node.boundsInRoot)
        }
        val inSettings = describe("settings_layout_")
        backToHome()
        click("display_open")
        until { exists("layout_LIST") }
        val inPanel = describe("layout_")
        assertEquals("same names", inSettings.map { it.second }, inPanel.map { it.second })
        fun order(items: List<Triple<ListMode, String?, androidx.compose.ui.geometry.Rect>>) =
            items.sortedWith(compareBy({ it.third.top.toInt() / 20 }, { it.third.left })).map { it.first }
        assertEquals("same order on screen", ListMode.entries, order(inSettings))
        assertEquals(ListMode.entries, order(inPanel))
    }

    /** P2-T31 to T33 (and P2-D03): every kind of appearance change, then back: same category, same place, nothing refreshed or marked. */
    @Test
    fun anAppearanceChangeKeepsTheCategoryTheScrollPositionAndEveryReadAndSavedState() {
        val ids = seeded.getValue(beta.categoryId)
        runBlocking {
            settings.setLastScope(beta)
            // A read and a saved article, to see that nothing of that is touched.
            c.articles.setRead(ids[3], true)
            c.articles.setSaved(ids[4], true)
        }
        launch()
        compose.onAllNodes(hasTestTag("article_list") and hasAnyAncestor(page(beta))).onFirst().performScrollToIndex(20)
        compose.waitForIdle()
        Thread.sleep(400)
        val statesBefore = seeded.values.flatten().map { TestData.state(it) }
        val attemptBefore = runBlocking { settings.current().lastRefreshAttemptAt }
        val countBefore = runBlocking { c.articles.countArticles(account()) }
        val prefsBefore = prefs()
        openSettingsPage("APPEARANCE")
        click("palette_LEAF")
        click("palette_SAND")
        click("theme_DARK")
        click("palette_VIOLET")
        compose.onNodeWithTag("text_size_slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(105f) }
        until(5_000) { runBlocking { settings.current().textScale } == 1.05f }
        assertEquals(ColorPalette.VIOLET, runBlocking { settings.current().palette })
        assertEquals(ThemeMode.DARK, runBlocking { settings.current().theme })
        backToHome()
        until { exists("page_${beta.encode()}") }
        Thread.sleep(600)
        val near = (16..26).any { i -> compose.onAllNodes(hasTestTag("article_${ids[i]}") and hasAnyAncestor(page(beta))).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the place in the list is kept (an article near the 21st is on screen)", near)
        assertEquals(beta, runBlocking { settings.current().lastScope })
        assertEquals("no state changed", statesBefore, seeded.values.flatten().map { TestData.state(it) })
        assertEquals("no refresh was started", attemptBefore, runBlocking { settings.current().lastRefreshAttemptAt })
        assertEquals("no article was added or removed", countBefore, runBlocking { c.articles.countArticles(account()) })
        assertEquals("the categories and their settings are as they were", prefsBefore, prefs())
        assertNotEquals(null, statesBefore.firstOrNull { it.isRead })
        assertTrue(statesBefore.any { it.isSaved })
    }

    /** P2-T34 and T35 at the screen level: the screen is recreated while a settings page is open, and nothing is lost. */
    @Test
    fun aRecreateOfTheScreenOnASettingsPageKeepsThePageAndTheValues() {
        launch()
        openSettingsPage("LAYOUT")
        click("settings_layout_BENTO")
        until(5_000) { runBlocking { settings.current().listMode } == ListMode.BENTO }
        scenario!!.recreate()
        until { exists("settings_page_LAYOUT") }
        compose.waitForIdle()
        compose.onNodeWithTag("settings_layout_BENTO").assertIsSelected()
        back()
        until { exists("settings_top") }
        back()
        until { exists("category_pager") }
    }
}
