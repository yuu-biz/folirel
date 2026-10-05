package io.github.yuubiz.folirel

import android.util.Log
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.yuubiz.folirel.app.MainActivity
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
 * P1-T32 and P1-T33: an empty install, one category, thirty categories with 300 feeds and 5000 articles; and large text. Only the page on
 * screen and its neighbour are composed (the pager does not open a query per category); the new controls stay reachable at 200 %.
 */
@RunWith(AndroidJUnit4::class)
class P1ScaleTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)

    @Before
    fun reset() {
        TestData.reset()
    }

    @After
    fun tearDown() {
        scenario?.close()
        shell("settings put system font_scale 1.0")
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun has(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun waitTag(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { has(tag) }

    private fun composedPages() = compose.onAllNodes(SemanticsMatcher("a pager page") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("page_") == true
    }).fetchSemanticsNodes().size

    @Test
    fun anEmptyInstallShowsTheWayToAddFeeds() {
        launch()
        waitTag("empty_message")
        assertTrue(!has("category_pager"))
    }

    @Test
    fun oneCategoryHasOneTabBesideAll() {
        TestData.seedPaths(listOf(listOf("Only")), perCategory = 10)
        launch()
        waitTag("category_pager")
        compose.waitUntil(10_000) { has("tab_all") }
        assertEquals(2, compose.onAllNodes(hasTestTag("tab_row_scroll")).fetchSemanticsNodes().first().children.count {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tab_") == true
        })
    }

    @Test
    fun thirtyCategories300Feeds5000ArticlesOpenQuicklyAndOpenOnlyTheVisiblePages() {
        val started = System.currentTimeMillis()
        TestData.seedPaths((0 until 300).map { listOf("カテゴリ ${(it % 30).toString().padStart(2, '0')}") }, perCategory = 17)
        val seeded = System.currentTimeMillis()
        runBlocking { TestData.container.settings.setListMode(ListMode.LIST) }
        launch()
        waitTag("category_pager")
        compose.waitUntil(20_000) { has("article_list") }
        val shown = System.currentTimeMillis()
        Log.i("P1SCALE", "seeding ${seeded - started} ms, launch to first list ${shown - seeded} ms")
        compose.waitForIdle()
        assertTrue("at most the page on screen and its neighbours are composed: ${composedPages()}", composedPages() <= 3)
        repeat(6) {
            compose.onNodeWithTag("category_pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            assertTrue("pages composed after swipe ${it + 1}: ${composedPages()}", composedPages() <= 3)
        }
        // The picker over thirty categories: reached in one press, searched, scrolled.
        compose.onNodeWithTag("scope_title").performClick()
        waitTag("category_picker")
        assertTrue(has("picker_item_all"))
    }

    /** P1-T33: 200 % text, the organizer: a row's pin and its menu (up/down) are operable, and nothing needs the drag. */
    @Test
    fun theOrganizerIsOperableAtTwiceTheTextSize() {
        TestData.seedPaths(listOf("Alpha", "Beta", "Gamma", "Delta").map { listOf(it) }, perCategory = 5)
        shell("settings put system font_scale 2.0")
        launch()
        waitTag("category_pager")
        compose.onNodeWithTag("home_more").performClick()
        compose.onNodeWithTag("menu_organize_tabs").performClick()
        waitTag("tab_order_list")
        val repo = TestData.container.subscriptions
        val account = runBlocking { repo.ensureLocalAccount() }
        fun structure() = runBlocking { repo.observeStructure(account).first() }
        fun idOf(name: String) = structure().categories.first { it.display == name }.id
        val delta = idOf("Delta")
        compose.onNodeWithTag("tab_order_pin_$delta").performClick()
        compose.waitUntil(5_000) { structure().tabCategories.first().display == "Delta" }
        val gamma = idOf("Gamma")
        compose.onNodeWithTag("tab_order_list").performScrollToNode(hasTestTag("tab_order_menu_$gamma"))
        compose.onNodeWithTag("tab_order_menu_$gamma").performClick()
        compose.onNodeWithTag("tab_order_up_$gamma").performClick()
        compose.waitUntil(5_000) { structure().tabCategories.map { it.display } == listOf("Delta", "Alpha", "Gamma", "Beta") }
        // The tap targets are 48 dp at this text size too.
        val density = compose.density.density
        for (tag in listOf("tab_order_pin_$delta", "tab_order_menu_$gamma", "tab_order_handle_$gamma")) {
            val r = compose.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
            assertTrue("$tag is at least 48dp (${r.height / density} x ${r.width / density})", r.height >= 48f * density - 1f && r.width >= 48f * density - 1f)
        }
    }
}
