package io.github.yuubiz.folirel

import android.graphics.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.data.preferences.CategoryNavigation
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.ReadFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Phase 2.5 on the real thing (the real MainActivity, view model, Room and settings): the order of the categories on every surface,
 * the pane and the drawer by the window's real width, four and five columns, and what a fold, unfold or split-screen resize keeps.
 *
 * A window width is made with `wm size` / `wm density` (density 320, so dp = px / 2) and restored afterwards, so the run needs no
 * foldable: the app sees the same thing it sees when a foldable opens (a configuration change with a new width). The expectations
 * are written out here from the specification (width classes, rows that fill the window); they do not call the rules under test.
 * Emulator, debug build: this says what the app does with those widths, not how a real hinge or a real launcher behaves.
 */
@RunWith(AndroidJUnit4::class)
class ResponsiveNavTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val settings get() = TestData.container.settings
    private val repo get() = TestData.container.subscriptions
    private val density get() = compose.density.density

    @Before
    fun reset() {
        TestData.reset()
    }

    @After
    fun tearDown() {
        scenario?.close()
        shell("wm size reset")
        shell("wm density reset")
        shell("settings put system font_scale 1.0")
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun widthDp(): Int {
        var width = 0
        scenario!!.onActivity { width = it.resources.configuration.screenWidthDp }
        return width
    }

    /** The window gets this size (dp) while the app runs; returns when the activity has the new configuration and has drawn it. */
    private fun resizeTo(widthDp: Int, heightDp: Int = 800) {
        shell("wm density 320")
        shell("wm size ${widthDp * 2}x${heightDp * 2}")
        val deadline = System.currentTimeMillis() + 15_000
        while (scenario != null && widthDp() != widthDp && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals("the window has the wanted width", widthDp, widthDp())
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
    }

    private fun launchAt(widthDp: Int, heightDp: Int = 800) {
        scenario?.close()
        shell("wm density 320")
        shell("wm size ${widthDp * 2}x${heightDp * 2}")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        until { has("category_pager") }
        assertEquals(widthDp, widthDp())
    }

    private fun has(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun until(timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val line = Throwable().stackTrace[1].lineNumber
        try {
            compose.waitUntil(timeoutMillis, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("timed out after $timeoutMillis ms waiting at ResponsiveNavTest.kt:$line", e)
        }
    }

    /** The tags that start with [prefix], in the order they stand on screen (top to bottom, or left to right). */
    private fun tagsInOrder(prefix: String, vertical: Boolean): List<String> =
        compose.onAllNodes(SemanticsMatcher("tag $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true })
            .fetchSemanticsNodes()
            .sortedBy { if (vertical) it.positionInRoot.y else it.positionInRoot.x }
            .map { it.config.getOrNull(SemanticsProperties.TestTag)!! }

    private data class Seed(val ids: Map<String, String>) {
        operator fun get(display: String) = ids.getValue(display)
    }

    private fun seed(perCategory: Int = 40): Seed {
        val byPath = TestData.seedPaths(listOf(listOf("Alpha"), listOf("Beta"), listOf("Gamma"), listOf("Delta"), listOf("Parent", "Child")), perCategory)
        return Seed(byPath)
    }

    private fun account() = runBlocking { repo.ensureLocalAccount() }

    private fun tabCategoryTags() = tagsInOrder("tab_category:", vertical = false).map { it.removePrefix("tab_") }

    // --- A: one order on every surface -----------------------------------------------------------------------------------

    /** RSP-T01 / T03 / T04: reordered tabs, nested categories: tabs, picker and drawer show the same ids in the same order. */
    @Test
    fun tabsPickerAndDrawerShowTheOrganizedOrderOfNestedCategories() {
        val s = seed()
        val account = account()
        val child = s["Parent / Child"]
        val parent = s["Parent"]
        // Not the path order (Alpha, Beta, Delta, Gamma, Parent, Parent / Child): a child stands before its parent.
        assertTrue(runBlocking { repo.reorderCategoryTabs(account, false, listOf(s["Gamma"], child, s["Beta"], parent, s["Alpha"], s["Delta"])) })
        assertTrue(runBlocking { repo.setCategoryPinned(account, s["Delta"], true) })
        val expected = listOf(s["Delta"], s["Gamma"], child, s["Beta"], parent, s["Alpha"]).map { "category:$it" }
        launchAt(411)

        until { tabCategoryTags() == expected }
        assertEquals("tabs", expected, tabCategoryTags())

        compose.onNodeWithTag("drawer_open").performClick()
        until { has("home_drawer") }
        until { tagsInOrder("drawer_item_category:", vertical = true).size == expected.size }
        assertEquals("drawer", expected.map { "drawer_item_$it" }, tagsInOrder("drawer_item_category:", vertical = true))
        // The pinned shortcut stands above the list, as before.
        assertEquals(listOf("drawer_pinned_${s["Delta"]}"), tagsInOrder("drawer_pinned_", vertical = true))
        // RSP-T04: the feeds of a category open under that category, wherever it stands: open the child (it stands before its parent).
        compose.onNodeWithTag("drawer_item_category:$child").assertExists()
        UiDevice.getInstance(instrumentation).pressBack()
        compose.waitForIdle()

        compose.onNodeWithTag("scope_title").performClick()
        until { has("category_picker") }
        until { tagsInOrder("picker_item_category:", vertical = true).size == expected.size }
        assertEquals("picker", expected.map { "picker_item_$it" }, tagsInOrder("picker_item_category:", vertical = true))
    }

    /** RSP-T05 / T02 / A03: the pane has the same order, and a pin made while it is shown reaches it, the tabs and nothing needs a restart. */
    @Test
    fun thePaneUsesTheSameOrderAndFollowsAPinAtOnce() {
        val s = seed()
        val account = account()
        runBlocking { settings.setCategoryNavigation(CategoryNavigation.PANE) }
        assertTrue(runBlocking { repo.reorderCategoryTabs(account, false, listOf(s["Gamma"], s["Parent / Child"], s["Beta"], s["Parent"], s["Alpha"], s["Delta"])) })
        launchAt(733)
        until { has("home_pane") }
        val normal = listOf(s["Gamma"], s["Parent / Child"], s["Beta"], s["Parent"], s["Alpha"], s["Delta"])
        fun pane() = tagsInOrder("drawer_item_category:", vertical = true).map { it.removePrefix("drawer_item_") }
        assertEquals(normal.map { "category:$it" }, pane())
        assertEquals(normal.map { "category:$it" }, tabCategoryTags())
        assertFalse("with a pane there is no drawer", has("home_drawer"))

        // A pin made elsewhere: tabs and pane change together, no relaunch.
        assertTrue(runBlocking { repo.setCategoryPinned(account, s["Alpha"], true) })
        val pinned = (listOf(s["Alpha"]) + normal.filter { it != s["Alpha"] }).map { "category:$it" }
        until { pane() == pinned && tabCategoryTags() == pinned }
        assertTrue(runBlocking { repo.setCategoryPinned(account, s["Alpha"], false) })
        until { pane() == normal.map { "category:$it" } && tabCategoryTags() == normal.map { "category:$it" } }
    }

    // --- E: pane or drawer by the window's real width ---------------------------------------------------------------------

    /** RSP-T24..T30 and T31..T35: for each width and each choice, is there a pane, a menu button, tabs and a pager. */
    @Test
    fun paneAndMenuButtonFollowTheRealWidthAndTheChoice() {
        seed(perCategory = 10)
        val widths = listOf(411, 600, 733, 839, 840, 1200)
        launchAt(411)
        val problems = ArrayList<String>()
        for (choice in CategoryNavigation.entries) {
            runBlocking { settings.setCategoryNavigation(choice) }
            for (width in widths) {
                resizeTo(width)
                val wantPane = when (choice) {
                    CategoryNavigation.AUTO -> width >= 840
                    CategoryNavigation.PANE -> width >= 600
                    CategoryNavigation.DRAWER -> false
                }
                until { has("home_pane") == wantPane }
                val label = "$choice at $width dp"
                if (has("home_pane") != wantPane) problems += "$label: pane=${has("home_pane")}, wanted $wantPane"
                // The menu button exists exactly while there is no pane (a pane is the way to the categories there).
                if (has("drawer_open") == wantPane) problems += "$label: menu button=${has("drawer_open")} with pane=$wantPane"
                // Tabs and the pager stay in every case.
                if (!has("tab_strip") || !has("category_pager")) problems += "$label: tabs=${has("tab_strip")} pager=${has("category_pager")}"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** RSP-T30: beside the pane, a tap on a tab and a swipe in the pager both move between categories, and the pane shows the choice. */
    @Test
    fun tabsAndSwipeStillWorkBesideThePaneAndThePaneShowsTheChoice() {
        val s = seed(perCategory = 20)
        runBlocking { settings.setCategoryNavigation(CategoryNavigation.PANE) }
        launchAt(1000)
        until { has("home_pane") }
        val alpha = ArticleScope.Category(s["Alpha"])
        val beta = ArticleScope.Category(s["Beta"])
        compose.onNodeWithTag("tab_${alpha.encode()}").performClick()
        until { has("page_${alpha.encode()}") }
        compose.onNodeWithTag("tab_${alpha.encode()}").assertIsSelected()
        // Swipe left: the next category in tab order (Beta follows Alpha in path order).
        compose.onNodeWithTag("category_pager").performTouchInput { swipeLeft() }
        until { compose.onAllNodes(hasTestTag("tab_${beta.encode()}")).fetchSemanticsNodes().isNotEmpty() && runBlocking { settings.current().lastScope } == beta }
        // A choice made in the pane is the same selection.
        compose.onNodeWithTag("drawer_item_category:${s["Gamma"]}").performClick()
        until { runBlocking { settings.current().lastScope } == ArticleScope.Category(s["Gamma"]) }
        compose.onNodeWithTag("tab_category:${s["Gamma"]}").assertIsSelected()
    }

    /** RSP-T06 (pane path): a choice in the pane ends the "back to the top" movement of the page that was on screen. */
    @Test
    fun aChoiceInThePaneEndsTheMovementOfTheOldPage() {
        val s = seed(perCategory = 400)
        runBlocking { settings.setListMode(ListMode.LIST); settings.setCategoryNavigation(CategoryNavigation.PANE) }
        launchAt(1000)
        until { has("home_pane") }
        val all = ArticleScope.All.encode()
        val list = compose.onNode(hasTestTag("article_list") and hasAnyAncestor(hasTestTag("page_$all")))
        list.performScrollToIndex(900)
        compose.waitForIdle()
        until { has("scroll_to_top") }
        compose.onNodeWithTag("scroll_to_top").performTouchInput { down(center); up() }
        compose.onNodeWithTag("drawer_item_category:${s["Alpha"]}").performClick()
        until { runBlocking { settings.current().lastScope } == ArticleScope.Category(s["Alpha"]) }
        Thread.sleep(1_500)
        // Back to All: it is where the movement was cut, not at the top (the movement did not run on in the old page).
        compose.onNodeWithTag("drawer_item_all").performClick()
        until { has("page_$all") }
        compose.waitForIdle()
        // The "back to top" arrow is shown only far from the top: it is still there, so the list did not run on to the top.
        val farFromTop = has("scroll_to_top")
        assertTrue("the old page was not carried to the top by the movement that was cut (the arrow is shown while far from the top)", farFromTop)
    }

    // --- F: fold, unfold, split screen -------------------------------------------------------------------------------------

    private fun cards(pageTag: String): List<SemanticsNode> =
        compose.onAllNodes(
            SemanticsMatcher("article card") {
                val tag = it.config.getOrNull(SemanticsProperties.TestTag)
                tag != null && tag.startsWith("article_") && tag != "article_list"
            } and hasAnyAncestor(hasTestTag(pageTag)),
        ).fetchSemanticsNodes()

    private fun pageBounds(pageTag: String): androidx.compose.ui.geometry.Rect =
        compose.onNodeWithTag(pageTag).fetchSemanticsNode().boundsInRoot

    /** The first card that is wholly inside the page, at the top of it. */
    private fun anchorCard(pageTag: String): String {
        val page = pageBounds(pageTag)
        return cards(pageTag)
            .filter { it.boundsInRoot.top >= page.top - 1f && it.boundsInRoot.bottom <= page.bottom + 1f }
            .minWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
            .config.getOrNull(SemanticsProperties.TestTag)!!
    }

    private fun shown(pageTag: String, cardTag: String): Boolean {
        val page = pageBounds(pageTag)
        return cards(pageTag).any {
            it.config.getOrNull(SemanticsProperties.TestTag) == cardTag && it.boundsInRoot.bottom > page.top && it.boundsInRoot.top < page.bottom
        }
    }

    /**
     * RSP-T36 / T37 / F01: phone, then unfolded (a wide window with the pane), then split-screen narrow, then wide again: the chosen
     * category, the filter and sort, the search text and the article at the top of the list stay; nothing is marked read or saved.
     */
    @Test
    fun aResizeKeepsTheCategoryTheFilterTheSearchAndTheArticleInView() {
        val s = seed(perCategory = 80)
        runBlocking {
            settings.setListMode(ListMode.MAGAZINE)
            settings.setLastFilter(ReadFilter.ALL)
        }
        launchAt(411)
        val beta = ArticleScope.Category(s["Beta"])
        val page = "page_${beta.encode()}"
        compose.onNodeWithTag("tab_${beta.encode()}").performClick()
        until { has(page) && cards(page).isNotEmpty() }
        compose.onNode(hasTestTag("article_list") and hasAnyAncestor(hasTestTag(page))).performScrollToIndex(30)
        compose.waitForIdle()
        val subtitle = textOf("scope_subtitle")
        val titleText = textOf("scope_title")
        val before = readAndSavedCounts()
        var anchor = anchorCard(page)

        for (width in listOf(1200, 600, 840, 411, 733)) {
            resizeTo(width)
            until { has(page) && cards(page).isNotEmpty() }
            val label = "after the window became $width dp"
            assertEquals("$label: the category stays the chosen one", beta, runBlocking { settings.current().lastScope })
            compose.onNodeWithTag("tab_${beta.encode()}").assertIsSelected()
            assertEquals("$label: title", titleText, textOf("scope_title"))
            assertEquals("$label: filter and sort", subtitle, textOf("scope_subtitle"))
            assertTrue("$label: the article that was at the top is still in view ($anchor)", shown(page, anchor))
            assertEquals("$label: read and saved", before, readAndSavedCounts())
            anchor = anchorCard(page)
        }
    }

    private fun textOf(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().let { n ->
            (n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }) ?: n.children.joinToString("|") { c ->
                c.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            }
        }

    private fun readAndSavedCounts(): Pair<Int, Int> = runBlocking {
        val c = TestData.container
        val acc = c.subscriptions.ensureLocalAccount()
        val articles = c.database.subscriptionDao().feeds(acc).flatMap { f -> c.database.articleDao().articlesOfFeed(f.id) }
        articles.count { c.database.articleDao().state(it.id)?.isRead == true } to articles.count { c.database.articleDao().state(it.id)?.isSaved == true }
    }

    /** The search text lives through the resize (it belongs to the view model, not to the window). */
    @Test
    fun theSearchTextSurvivesAResize() {
        seed(perCategory = 30)
        launchAt(733)
        compose.onNodeWithTag("search_open").performClick()
        until { has("search_field") }
        compose.onNodeWithTag("search_field").performTextInput("Beta")
        compose.waitForIdle()
        resizeTo(1200)
        until { has("search_field") }
        assertTrue("the search text stays: ${textOf("search_field")}", textOf("search_field").contains("Beta") || compose.onNodeWithTag("search_field").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text == "Beta")
        resizeTo(411)
        until { has("search_field") }
        assertEquals("Beta", compose.onNodeWithTag("search_field").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    // --- B, C, D: columns on the real screen ------------------------------------------------------------------------------

    /** Cards in the page, as pixel rectangles. */
    private fun cardRects(pageTag: String): List<Rect> = cards(pageTag).map {
        val b = it.boundsInRoot
        Rect(Math.round(b.left), Math.round(b.top), Math.round(b.right), Math.round(b.bottom))
    }

    /**
     * At the top edge of every card (a little below it), the cards that cover that line run from the left edge of the page to the
     * right edge with one gap between neighbours: no empty column, no hole, in a row of Magazine cards or a block of Bento tiles.
     */
    private fun assertFullWidthRows(pageTag: String, label: String) {
        val page = pageBounds(pageTag)
        val edge = Math.round(8 * density)
        val gap = Math.round(8 * density)
        val rects = cardRects(pageTag).filter { it.top >= page.top - 1 && it.bottom <= page.bottom + 1 }
        assertTrue("$label: there are cards", rects.size >= 4)
        val checked = HashSet<Int>()
        for (probe in rects.map { it.top + Math.round(4 * density) }.filter { it < page.bottom - 4 }) {
            if (!checked.add(probe / 4)) continue
            val line = cardRects(pageTag).filter { it.top <= probe && it.bottom > probe }.sortedBy { it.left }
            assertTrue("$label: something covers the line at y=$probe", line.isNotEmpty())
            val tol = 2
            assertTrue("$label: y=$probe starts at the left edge (${line.first().left - page.left} px, wanted $edge)", abs(line.first().left - page.left - edge) <= tol)
            assertTrue("$label: y=$probe ends at the right edge (${page.right - line.last().right} px, wanted $edge)", abs(page.right - line.last().right - edge) <= tol)
            line.zipWithNext().forEach { (a, b) -> assertTrue("$label: y=$probe gap between neighbours ${b.left - a.right} px, wanted $gap", abs(b.left - a.right - gap) <= tol) }
        }
    }

    private fun columnsOf(pageTag: String): Int {
        val rects = cardRects(pageTag)
        return rects.map { it.left / 4 }.toSet().size
    }

    private fun setLayout(mode: ListMode, columns: Int, style: CardLayoutStyle = CardLayoutStyle.MIXED, navigation: CategoryNavigation = CategoryNavigation.DRAWER) = runBlocking {
        settings.setListMode(mode)
        settings.setCardColumns(columns)
        settings.setCardStyle(style)
        settings.setCategoryNavigation(navigation)
    }

    /** RSP-T14..T17 and T19..T21 on a real screen: every row of cards or tiles fills the window at 4 and 5 columns, in all three layouts. */
    @Test
    fun fourAndFiveColumnsFillTheWindowInMagazineMasonryAndBento() {
        seed(perCategory = 60)
        val all = "page_${ArticleScope.All.encode()}"
        launchAt(1200)
        for ((mode, style) in listOf(
            ListMode.MAGAZINE to CardLayoutStyle.EVEN, ListMode.MAGAZINE to CardLayoutStyle.MIXED, ListMode.BENTO to CardLayoutStyle.MIXED,
        )) for (columns in listOf(4, 5)) {
            setLayout(mode, columns, style)
            resizeTo(1200) // a change of settings reaches the open screen by itself; this only waits for it to be drawn
            until { cards(all).size >= 6 }
            Thread.sleep(500)
            compose.waitForIdle()
            assertFullWidthRows(all, "$mode/$style/$columns columns")
        }
        // Masonry: five lanes from edge to edge.
        for (columns in listOf(4, 5)) {
            setLayout(ListMode.MASONRY, columns)
            Thread.sleep(800)
            compose.waitForIdle()
            until { cards(all).size >= 6 }
            val page = pageBounds(all)
            val rects = cardRects(all)
            val lanes = rects.map { it.left }.distinct().sorted()
            assertEquals("masonry $columns columns: lanes", columns, lanes.size)
            assertTrue("masonry: starts at the left edge", abs(lanes.first() - page.left - 8 * density) <= 2)
            assertTrue("masonry: ends at the right edge", abs(page.right - rects.maxOf { it.right } - 8 * density) <= 2)
        }
    }

    /**
     * RSP-T10 / T11 / T18: five columns chosen; the window shrinks (reduced, not changed in the settings), grows again (five), and the
     * article in view stays through 3 -> 4 -> 5 columns and 5 -> 2.
     */
    @Test
    fun aChosenFiveColumnsIsReducedOnANarrowWindowAndComesBack() {
        seed(perCategory = 60)
        setLayout(ListMode.MAGAZINE, 5, CardLayoutStyle.EVEN)
        val all = "page_${ArticleScope.All.encode()}"
        launchAt(1200)
        until { cards(all).size >= 5 }
        assertEquals(5, columnsOf(all))
        compose.onNode(hasTestTag("article_list") and hasAnyAncestor(hasTestTag(all))).performScrollToIndex(40)
        compose.waitForIdle()
        var anchor = anchorCard(all)
        for ((width, columns) in listOf(411 to 2, 733 to 4, 1200 to 5, 600 to 3, 840 to 5)) {
            resizeTo(width)
            until { cards(all).isNotEmpty() }
            assertEquals("$width dp: columns", columns, columnsOf(all))
            assertEquals("the choice itself is never rewritten", 5, runBlocking { settings.current().cardColumns })
            assertTrue("$width dp: the article that was at the top is in view ($anchor)", shown(all, anchor))
            anchor = anchorCard(all)
        }
    }

    /** RSP-T38: 200 % text takes columns away and never shrinks the text; the pane stays usable. */
    @Test
    fun twiceTheTextSizeReducesColumnsAndKeepsThePaneUsable() {
        val s = seed(perCategory = 40)
        setLayout(ListMode.MAGAZINE, 0, navigation = CategoryNavigation.PANE)
        shell("settings put system font_scale 2.0")
        launchAt(1200)
        until { has("home_pane") }
        val all = "page_${ArticleScope.All.encode()}"
        until { cards(all).isNotEmpty() }
        assertTrue("at most two columns at 200 % text on 1200 dp (beside a pane): ${columnsOf(all)}", columnsOf(all) <= 2)
        // The pane's entries are at least 48 dp tall and a tap on one still selects the category.
        val item = compose.onNodeWithTag("drawer_item_category:${s["Alpha"]}").fetchSemanticsNode()
        assertTrue("pane entry height ${item.size.height / density} dp", item.size.height >= 48 * density - 1)
        compose.onNodeWithTag("drawer_item_category:${s["Alpha"]}").performClick()
        until { runBlocking { settings.current().lastScope } == ArticleScope.Category(s["Alpha"]) }
        compose.onNodeWithTag("tab_category:${s["Alpha"]}").assertIsSelected()
    }

    // --- settings screens --------------------------------------------------------------------------------------------------

    /** RSP-T08 / T09 / T39: the new choices are in Settings and in the category sheet, are stored, and the screen keeps them when it is recreated. */
    @Test
    fun theSettingsOfferTheNavigationAndFiveColumnsAndKeepThemAcrossARecreate() {
        val s = seed(perCategory = 10)
        launchAt(411)
        compose.onNodeWithTag("drawer_open").performClick()
        until { has("drawer_item_settings") }
        compose.onNodeWithTag("drawer_item_settings").performClick()
        until { has("settings_row_LAYOUT") }
        compose.onNodeWithTag("settings_row_LAYOUT").performClick()
        until { has("settings_nav_PANE") }
        compose.onNodeWithTag("settings_nav_PANE").performClick()
        until { runBlocking { settings.current().categoryNavigation } == CategoryNavigation.PANE }
        // Columns need a layout that has them.
        runBlocking { settings.setListMode(ListMode.MAGAZINE) }
        until { has("settings_columns_row") }
        runCatching { compose.onNodeWithTag("settings_columns_row").performScrollTo() }
        compose.onNodeWithTag("settings_columns_row").performClick()
        until { has("settings_columns_5") }
        assertTrue("the choices are Automatic, 1, 2, 3, 4, 5", (0..5).all { has("settings_columns_$it") })
        compose.onNodeWithTag("settings_columns_5").performClick()
        until { runBlocking { settings.current().cardColumns } == 5 }

        scenario!!.recreate()
        compose.waitForIdle()
        assertEquals(CategoryNavigation.PANE, runBlocking { settings.current().categoryNavigation })
        assertEquals(5, runBlocking { settings.current().cardColumns })
        // The category override offers the same six choices and keeps 4 (RSP-T09).
        runBlocking { settings.setCategoryNavigation(CategoryNavigation.DRAWER) }
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val alpha = ArticleScope.Category(s["Alpha"])
        until { has("tab_${alpha.encode()}") }
        compose.onNodeWithTag("tab_${alpha.encode()}").performClick()
        until { runBlocking { settings.current().lastScope } == alpha }
        compose.onNodeWithTag("display_open").performClick()
        until { has("layout_details") }
        compose.onNodeWithTag("layout_details").performClick()
        until { has("cl_cols_row") }
        compose.onNodeWithTag("cl_cols_row").performClick()
        until { has("cl_cols_4") }
        assertTrue("the category choices are Use the default, Automatic, 1..5", has("cl_cols_INHERIT") && (0..5).all { has("cl_cols_$it") })
        compose.onNodeWithTag("cl_cols_4").performClick()
        val account = account()
        until { runBlocking { repo.observeStructure(account).first() }.preferences[s["Alpha"]]?.cardColumns == 4 }
    }
}
