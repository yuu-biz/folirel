package io.github.yuubiz.folirel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.cancel
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.ListMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1-ORG on the real app (MainActivity: home, pager, picker, organizer screen, Room): the jump to a category, pins, the order of the
 * tabs by drag and by menu, and what must not change while that happens. Real touch input for the drag.
 */
@RunWith(AndroidJUnit4::class)
class P1OrgTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var names: List<String>
    private lateinit var byPath: Map<String, String>

    /** Category ids in the order the tabs have when nothing is saved: path order. */
    private lateinit var ids: List<String>
    private val density get() = compose.density.density
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val repo get() = TestData.container.subscriptions

    @Before
    fun seed() {
        TestData.reset()
        names = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta")
        val paths = names.map { listOf(it) } + listOf(listOf("Alpha", "共通"), listOf("Beta", "共通"))
        byPath = TestData.seedPaths(paths, perCategory = 30)
        val sorted = paths.sortedBy { it.joinToString("\u0000") }
        ids = sorted.map { byPath.getValue(it.joinToString(" / ")) }
        runBlocking { TestData.container.settings.setListMode(ListMode.LIST) }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitTag("category_pager")
        compose.waitForIdle()
        Thread.sleep(500)
    }

    private fun waitTag(tag: String, timeout: Long = 10_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun id(path: String) = byPath.getValue(path)
    private fun scope(path: String) = ArticleScope.Category(id(path))
    private fun savedScope() = runBlocking { TestData.container.settings.current().lastScope }

    private fun storedTabs(): List<String> = runBlocking {
        val account = repo.ensureLocalAccount()
        repo.observeStructure(account).first().tabCategories.map { it.display }
    }

    /** What the tab row shows, left to right (by position on the screen): the real order a reader sees. */
    private fun shownTabs(): List<String> {
        val nodes = compose.onAllNodes(hasTestTag("tab_row_scroll")).fetchSemanticsNodes().first()
        val tabs = nodes.children.filter { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tab_") == true }
        val idToName = byPath.entries.associate { (path, id) -> id to path }
        return tabs.sortedBy { it.positionInRoot.x }.mapNotNull { n ->
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)!!.removePrefix("tab_")
            if (tag.startsWith("category:")) idToName[tag.removePrefix("category:")] else tag
        }
    }

    private fun openPicker() {
        compose.onNodeWithTag("scope_title").performClick()
        waitTag("category_picker")
        compose.waitForIdle()
    }

    private fun pickerPin(path: String) {
        compose.onNodeWithTag("picker_list").performScrollToNode(hasTestTag("picker_pin_${id(path)}"))
        compose.onNodeWithTag("picker_pin_${id(path)}").performClick()
        compose.waitForIdle()
    }

    private fun openOrganizer() {
        compose.onNodeWithTag("home_more").performClick()
        compose.onNodeWithTag("menu_organize_tabs").performClick()
        waitTag("tab_order_list")
        compose.waitForIdle()
    }

    private fun backToHome() {
        device.pressBack()
        waitTag("category_pager")
        compose.waitForIdle()
        Thread.sleep(300)
    }

    private fun inPage(scope: ArticleScope, tag: String) =
        compose.onNode(hasTestTag(tag) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("page_${scope.encode()}")))

    private fun articleAxis(scope: ArticleScope): Float? =
        compose.onAllNodes(hasTestTag("article_list") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("page_${scope.encode()}")))
            .fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()

    private fun readCount(): Int = runBlocking {
        val c = TestData.container
        val account = c.subscriptions.ensureLocalAccount()
        c.database.subscriptionDao().feeds(account).sumOf { f -> c.database.articleDao().articlesOfFeed(f.id).count { a -> c.database.articleDao().state(a.id)?.isRead == true } }
    }

    /** P1-T14: names and parent paths are searched on the device; two categories of one name are told apart; the article search is not touched. */
    @Test
    fun thePickerSearchesNamesAndParentPathsAndSelectsAcrossTheTabs() {
        launch()
        openPicker()
        compose.onNodeWithTag("picker_search").performTextInput("共通")
        compose.waitForIdle()
        assertTrue(exists("picker_item_${scope("Alpha / 共通").encode()}"))
        assertTrue(exists("picker_item_${scope("Beta / 共通").encode()}"))
        assertTrue("others are filtered out", !exists("picker_item_${scope("Gamma").encode()}"))
        // The parent path makes the two of one name differ, and the count is of the matches.
        val shown = compose.onAllNodes(androidx.compose.ui.test.hasText("Alpha", substring = false)).fetchSemanticsNodes().size
        assertTrue("the parent path of the child is on its row", shown >= 1)
        val count = compose.onNodeWithTag("picker_count").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        assertTrue("the count is of the matches (2): $count", count.contains("2"))
        compose.onNodeWithTag("picker_item_${scope("Beta / 共通").encode()}").performClick()
        compose.waitUntil(10_000) { savedScope() == scope("Beta / 共通") }
        waitTag("tab_${scope("Beta / 共通").encode()}")
        compose.onNodeWithTag("tab_${scope("Beta / 共通").encode()}").assertIsSelected()
        assertEquals(0, readCount())
    }

    /** P1-T14: choosing the category already shown only closes the picker: the place in the list stays and nothing is answered. */
    @Test
    fun choosingTheShownCategoryAgainOnlyClosesThePicker() {
        runBlocking { TestData.container.settings.setLastScope(scope("Gamma")) }
        launch()
        waitTag("page_${scope("Gamma").encode()}")
        compose.waitUntil(10_000) { articleAxis(scope("Gamma")) != null }
        inPage(scope("Gamma"), "article_list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val before = articleAxis(scope("Gamma"))!!
        assertTrue("the list is scrolled down", before > 100f)
        openPicker()
        compose.onNodeWithTag("picker_item_${scope("Gamma").encode()}").performClick()
        compose.waitUntil(5_000) { !exists("category_picker") }
        compose.waitForIdle()
        Thread.sleep(600)
        assertEquals("no movement to the top", before, articleAxis(scope("Gamma"))!!, 1f)
        assertEquals(scope("Gamma"), savedScope())
        assertEquals(0, readCount())
    }

    /** P1-T15: pin and unpin from the picker: the pager has the category once, in front; unpinning gives the old place back; it survives a restart. */
    @Test
    fun pinningMovesATabToTheFrontOnceAndUnpinningBringsItBack() {
        launch()
        val before = shownTabs()
        assertEquals("All first, then path order", listOf("all", "Alpha", "Alpha / 共通", "Beta", "Beta / 共通", "Delta", "Epsilon", "Eta", "Gamma", "Theta", "Zeta"), before.map { it })
        openPicker()
        pickerPin("Gamma")
        pickerPin("Zeta")
        device.pressBack()
        compose.waitUntil(5_000) { !exists("category_picker") }
        compose.waitForIdle()
        Thread.sleep(500)
        val pinned = shownTabs()
        assertEquals(listOf("all", "Gamma", "Zeta"), pinned.take(3))
        assertEquals("no tab twice", pinned.size, pinned.toSet().size)
        assertEquals("one tab per category and All", before.size, pinned.size)
        launch() // a restart
        assertEquals(pinned, shownTabs())
        openPicker()
        pickerPin("Gamma")
        pickerPin("Zeta")
        device.pressBack()
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals("the normal places are back", before, shownTabs())
        assertEquals(0, readCount())
    }

    /** P1-T16: the handle moves a row within its group, the stored order and the tabs agree; a cancelled drag changes nothing. */
    @Test
    fun draggingTheHandleReordersAndACancelledDragDoesNot() {
        launch()
        openOrganizer()
        val before = storedTabs()
        val gamma = id("Gamma")
        val gammaRow = compose.onNodeWithTag("tab_order_item_$gamma").fetchSemanticsNode()
        val row = gammaRow.size.height.toFloat()
        // A drag that is cancelled: back to the stored order.
        compose.onNodeWithTag("tab_order_handle_$gamma").performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(0f, row * 0.4f)) }
            cancel()
        }
        compose.waitForIdle()
        Thread.sleep(300)
        assertEquals("a cancelled drag saves nothing", before, storedTabs())
        // Two rows up, in steps, then lifted: saved once.
        compose.onNodeWithTag("tab_order_handle_$gamma").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -row * 0.25f)) }
            up()
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { storedTabs() != before }
        val after = storedTabs()
        assertEquals("Gamma moved up by two places (2.5 rows pass two neighbours)", before.indexOf("Gamma") - 2, after.indexOf("Gamma"))
        assertEquals("the others keep their relative order", before.filter { it != "Gamma" }, after.filter { it != "Gamma" })
        backToHome()
        assertEquals("the tabs show the stored order", listOf("all") + after, shownTabs())
    }

    /** P1-T17: up and down from the row's menu, and "standard order" which keeps pins and takes only the places away. */
    @Test
    fun upDownAndStandardOrderAreAlternativesToDragging() {
        launch()
        openOrganizer()
        val before = storedTabs()
        compose.onNodeWithTag("tab_order_list").performScrollToNode(hasTestTag("tab_order_pin_${id("Theta")}"))
        compose.onNodeWithTag("tab_order_pin_${id("Theta")}").performClick()
        compose.waitUntil(5_000) { storedTabs().first() == "Theta" }
        val epsilon = id("Epsilon")
        compose.onNodeWithTag("tab_order_list").performScrollToNode(hasTestTag("tab_order_menu_$epsilon"))
        compose.onNodeWithTag("tab_order_menu_$epsilon").performClick()
        compose.onNodeWithTag("tab_order_down_$epsilon").performClick()
        // Theta (after Epsilon in path order) went to the front, which shifts Epsilon by one; the move down is one more.
        compose.waitUntil(5_000) { storedTabs().indexOf("Epsilon") == before.indexOf("Epsilon") + 2 }
        compose.onNodeWithTag("tab_order_list").performScrollToNode(hasTestTag("tab_order_reset"))
        compose.onNodeWithTag("tab_order_reset").performClick()
        compose.waitUntil(5_000) { storedTabs().drop(1) == before.filter { it != "Theta" } }
        assertEquals("the pin stays: Theta is first", "Theta", storedTabs().first())
        assertEquals(0, readCount())
    }

    /**
     * P1-T19: the category that is open keeps its identity and its place in the list when a pin moves its tab to another index; a swipe
     * afterwards goes to the neighbour in the new order.
     */
    @Test
    fun pinningAnotherCategoryDoesNotChangeTheOneShownOrItsArticlePlace() {
        runBlocking { TestData.container.settings.setLastScope(scope("Eta")) }
        launch()
        compose.waitUntil(10_000) { articleAxis(scope("Eta")) != null }
        inPage(scope("Eta"), "article_list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val axis = articleAxis(scope("Eta"))!!
        assertTrue(axis > 100f)
        runBlocking { repo.setCategoryPinned(repo.ensureLocalAccount(), id("Alpha"), true) }
        compose.waitForIdle()
        Thread.sleep(800)
        compose.waitForIdle()
        assertEquals("the selection is still Eta", scope("Eta"), savedScope())
        compose.onNodeWithTag("tab_${scope("Eta").encode()}").assertIsSelected()
        assertEquals("and its place in the list", axis, articleAxis(scope("Eta"))!!, 1f)
        // A swipe goes to the neighbour of Eta in the new order (Gamma follows Eta).
        compose.onNodeWithTag("category_pager").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { savedScope() == scope("Gamma") }
        assertEquals(0, readCount())
    }

    /** P1-T21: the category being shown loses its last feed: it stays shown (empty), is not replaced; deleting it returns to All. */
    @Test
    fun anEmptiedCategoryStaysShownAndADeletedOneFallsBackToAll() {
        runBlocking { TestData.container.settings.setLastScope(scope("Theta")) }
        launch()
        compose.waitUntil(10_000) { articleAxis(scope("Theta")) != null }
        val account = runBlocking { repo.ensureLocalAccount() }
        val feed = runBlocking {
            val dao = TestData.container.database.subscriptionDao()
            val membership = dao.memberships(account).first { it.categoryId == id("Theta") }
            dao.feed(membership.feedId)!!
        }
        runBlocking { repo.unsubscribe(feed.id) }
        compose.waitForIdle()
        Thread.sleep(800)
        compose.waitForIdle()
        assertEquals("not replaced by another category", scope("Theta"), savedScope())
        waitTag("empty_message")
        runBlocking { repo.deleteCategory(id("Theta")) }
        compose.waitUntil(10_000) { savedScope() == ArticleScope.All }
        assertTrue(runBlocking { TestData.container.database.subscriptionDao().categoryPreferences(account).none { it.categoryId == id("Theta") } })
    }
}
