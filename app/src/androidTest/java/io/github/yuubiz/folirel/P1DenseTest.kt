package io.github.yuubiz.folirel

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1-DENSE on the real home screen (view model, Room, real coordinator): the header is two rows, the display panel is the one place
 * for range, order and layout, and what the 0.4.2 notices did does not change when the panel or the search are used.
 */
@RunWith(AndroidJUnit4::class)
class P1DenseTest : HomeFlowTestBase() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(res: Int) = context.getString(res)

    private fun rect(tag: String): Rect {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val p = node.positionInRoot
        return Rect(p.x, p.y, p.x + node.size.width, p.y + node.size.height)
    }

    private val density get() = compose.density.density

    private fun subtitle(): String =
        compose.onNodeWithTag("scope_subtitle", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun openPanel() {
        compose.onNodeWithTag("display_open").performClick()
        until(5_000) { existsAnywhere("display_panel") }
        compose.waitForIdle()
    }

    private fun closePanel() {
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        until(5_000) { !existsAnywhere("display_panel") }
        compose.waitForIdle()
    }

    /** P1-T09: two rows in the normal case (the bar and the tabs), no filter row; the touch targets stay 48 dp. */
    @Test
    fun theHeaderIsTheBarAndTheTabsOnly() {
        firstFetchThenShow()
        val strip = rect("tab_strip")
        val pager = rect("category_pager")
        assertEquals("the articles start where the tabs end: no row of chips between", strip.bottom, pager.top, 1f)
        assertTrue("tabs are at least 48dp high (${strip.height / density})", strip.height >= 48f * density - 1f)
        assertTrue("and about 48dp (${strip.height / density})", strip.height <= 56f * density)
        val bar = rect("scope_title")
        val insetTop = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val headerDp = (pager.top - insetTop) / density
        android.util.Log.i("P1DENSE", "header ${headerDp}dp (bar + tabs), tabs ${strip.height / density}dp, status bar ${insetTop / density}dp, title ${bar.height / density}dp")
        assertTrue("the header is about 104 to 112 dp, was $headerDp", headerDp <= 112.5f)
        // The touch area (what a finger can hit), which Material makes at least 48dp for an icon button of 40dp.
        for (tag in listOf("display_open", "home_more", "scope_title")) {
            val r = compose.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
            assertTrue("$tag is at least 48dp high (${r.height / density})", r.height >= 48f * density - 1f)
            assertTrue("$tag is at least 48dp wide (${r.width / density})", r.width >= 48f * density - 1f)
        }
    }

    /** P1-T11: range and order chosen in the panel change the list, the shown state and what "mark all as read" would cover. */
    @Test
    fun rangeAndOrderAreChosenInThePanelAndShownUnderTheTitle() {
        firstFetchThenShow()
        assertEquals("${text(R.string.filter_all)} · ${text(R.string.sort_newest)}", subtitle())
        val before = vm.selection.value
        openPanel()
        closePanel()
        compose.waitForIdle()
        assertEquals("opening and closing the panel changes nothing", before, vm.selection.value)
        openPanel()
        compose.onNodeWithTag("filter_UNREAD").performClick()
        compose.onNodeWithTag("sort_OLDEST").performClick()
        compose.waitForIdle()
        assertEquals(ReadFilter.UNREAD, vm.selection.value.filter)
        assertEquals(SortOrder.OLDEST, vm.selection.value.sort)
        closePanel()
        assertEquals("${text(R.string.filter_unread)} · ${text(R.string.sort_oldest)}", subtitle())
        // Mark-all asks about what the shown state covers: the unread articles of this scope, in the confirmation.
        compose.onNodeWithTag("home_more").performClick()
        compose.onNode(androidx.compose.ui.test.hasText(text(R.string.menu_mark_all_read))).performClick()
        until(5_000) { existsAnywhere("mark_all_count") }
        val count = compose.onNodeWithTag("mark_all_count").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        assertTrue("the confirmation counts the unread ones: $count", count.contains("95"))
        assertEquals("choosing a range marks nothing", 0, readCount())
    }

    /** P1-T12: a notice that is up while the panel is opened and closed keeps its own time and does not come back; the list does not move. */
    @Test
    fun thePanelDoesNotRestartOrExtendANoticeAndDoesNotMoveTheList() {
        firstFetchThenShow()
        addBadFeed()
        badStatus = 500
        scrolledDown()
        val top = firstArticleTop()
        refresh()
        until(5_000) { existsAnywhere("refresh_notice") }
        openPanel()
        Thread.sleep(6_500) // longer than the notice lives
        closePanel()
        assertTrue("the notice ended by its own time while the panel was open and did not come back", !existsAnywhere("refresh_notice"))
        assertEquals("the list did not move", top, firstArticleTop(), 0.5f)
        // A search is not a reason for the notice to return either.
        compose.onNodeWithTag("search_open").performClick()
        compose.waitForIdle()
        assertTrue(!existsAnywhere("refresh_notice"))
        assertEquals(0, readCount())
    }

    /** P1-T10: narrow (320 dp), normal (411 dp) and wide (733 dp) windows, normal and 200 % text: the way to the categories, the panel and the search are reachable. */
    @Test
    fun everythingIsReachableAtNarrowAndWideWithLargeText() {
        firstFetchThenShow()
        for ((width, scale) in listOf(320 to 1.0f, 320 to 2.0f, 411 to 1.0f, 411 to 2.0f, 733 to 1.0f, 733 to 2.0f)) {
            host(width, scale)
            until(10_000) { existsAnywhere("scope_title") }
            val label = "${width}dp x$scale"
            compose.onNodeWithTag("scope_title").assertIsDisplayed()
            compose.onNodeWithTag("display_open").assertIsDisplayed()
            compose.onNodeWithTag("home_more").assertIsDisplayed()
            val title = rect("scope_title")
            val display = rect("display_open")
            val more = rect("home_more")
            assertTrue("$label: the title does not run into the buttons ($title / $display)", title.right <= display.left + 1f && display.right <= more.left + 1f)
            assertTrue("$label: the title is visible, not squeezed to nothing (${title.width / density}dp)", title.width >= 48f * density)
            // Search: on the bar when there is room, otherwise in the menu: reached either way, and it opens the field.
            if (existsAnywhere("search_open")) {
                compose.onNodeWithTag("search_open").performClick()
            } else {
                compose.onNodeWithTag("home_more").performClick()
                compose.onNodeWithTag("menu_search").performClick()
            }
            until(5_000) { existsAnywhere("search_field") }
            compose.onNodeWithTag("search_field").assertIsDisplayed()
            compose.onNodeWithTag("search_field").performTextInput("記")
            compose.waitForIdle()
            vm.setSearchActive(false)
            compose.waitForIdle()
            // The panel: every option can be reached (it scrolls) and pressed.
            openPanel()
            for (tag in listOf("filter_ALL", "filter_UNREAD", "filter_SAVED", "sort_NEWEST", "sort_OLDEST", "layout_LIST", "layout_COMPACT_LIST", "layout_MAGAZINE")) {
                runCatching { compose.onNodeWithTag(tag).performScrollTo() }
                compose.onNodeWithTag(tag).assertIsDisplayed()
            }
            closePanel()
        }
    }
}
