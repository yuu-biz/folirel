package io.github.yuubiz.folirel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * P1-T16 on the real app (MainActivity, organizer screen, real repository and Room): what happens when the write of an order fails, and
 * when the reader goes on pressing before the last write has been stored.
 *
 * A write failure is made the way a real one happens: a trigger in the app's own database makes every write to `category_preferences`
 * abort, so the failure comes out of SQLite through the repository and the view model (nothing in the app is replaced). The fast presses
 * are issued on the UI thread without a frame in between (the clock is stopped), which is what a quick finger does before the stored
 * order has come back to the screen.
 */
@RunWith(AndroidJUnit4::class)
class P1OrgRaceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var byPath: Map<String, String>
    private val repo get() = TestData.container.subscriptions
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun seed() {
        TestData.reset()
        val names = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta")
        byPath = TestData.seedPaths(names.map { listOf(it) }, perCategory = 5)
        runBlocking { TestData.container.settings.setListMode(ListMode.LIST) }
        launch()
    }

    @After
    fun tearDown() {
        runCatching { dropTriggers() }
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitTag("category_pager")
        compose.onNodeWithTag("home_more").performClick()
        compose.onNodeWithTag("menu_organize_tabs").performClick()
        waitTag("tab_order_list")
        compose.waitForIdle()
    }

    private fun waitTag(tag: String, timeout: Long = 10_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun id(name: String) = byPath.getValue(name)

    private fun stored(): List<String> = runBlocking {
        val account = repo.ensureLocalAccount()
        repo.observeStructure(account).first().tabCategories.map { it.display }
    }

    /** The rows of the organizer as the reader sees them, top to bottom (the list is short enough to be composed whole). */
    private fun shown(): List<String> {
        val idToName = byPath.entries.associate { (name, id) -> id to name }
        return compose.onAllNodes(hasTestTag("tab_order_list")).fetchSemanticsNodes().first().children
            .filter { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tab_order_item_") == true }
            .sortedBy { it.positionInRoot.y }
            .map { idToName.getValue(it.config[SemanticsProperties.TestTag].removePrefix("tab_order_item_")) }
    }

    private fun pinnedStored(): Set<String> = runBlocking {
        val account = repo.ensureLocalAccount()
        val s = repo.observeStructure(account).first()
        s.categories.filter { s.preferences[it.id]?.isPinned == true }.map { it.display }.toSet()
    }

    private fun failWrites() {
        val db = TestData.container.database.openHelper.writableDatabase
        for (op in listOf("INSERT", "UPDATE")) {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS p1_fail_${op.lowercase()} BEFORE $op ON category_preferences BEGIN SELECT RAISE(ABORT, 'forced write failure'); END")
        }
    }

    private fun dropTriggers() {
        val db = TestData.container.database.openHelper.writableDatabase
        for (op in listOf("insert", "update")) db.execSQL("DROP TRIGGER IF EXISTS p1_fail_$op")
    }

    private fun failureMessageShown() =
        compose.onAllNodes(hasText(context.getString(R.string.msg_update_failed))).fetchSemanticsNodes().isNotEmpty()

    /** An accessibility action of a row ("Move up"), taken now and run later on the UI thread like a press. */
    private fun rowAction(name: String, label: String): () -> Unit {
        val node = compose.onNodeWithTag("tab_order_item_${id(name)}").fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
        return { action.action() }
    }

    /** The click of a button, taken now and run later. */
    private fun click(tag: String): () -> Unit {
        val action = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        return { action() }
    }

    /**
     * Presses in one block on the UI thread, with no frame and no result of the database in between: a finger that is faster than the
     * screen. Every press sees what the screen showed when the block began, as a real quick press does.
     */
    private fun burst(vararg presses: () -> Unit) {
        compose.runOnUiThread { presses.forEach { it() } }
    }

    private fun waitUntilStored(timeout: Long = 5_000, condition: () -> Boolean) = compose.waitUntil(timeout) { condition() }

    // ---- write failures

    /** A drag whose write fails: the error is shown, the stored order is unchanged and the screen goes back to it. */
    @Test
    fun aFailedWriteAfterADragShowsTheErrorAndPutsTheOldOrderBack() {
        val before = stored()
        assertEquals(before, shown())
        failWrites()
        val row = compose.onNodeWithTag("tab_order_item_${id("Gamma")}").fetchSemanticsNode().size.height.toFloat()
        compose.onNodeWithTag("tab_order_handle_${id("Gamma")}").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -row * 0.25f)) }
            up()
        }
        compose.waitUntil(10_000) { failureMessageShown() }
        compose.waitForIdle()
        assertEquals("nothing was stored", before, stored())
        compose.waitUntil(5_000) { shown() == before }
        assertEquals("the screen shows what is stored", before, shown())
        // The failure left nothing stuck: with writes working again the same drag is stored.
        dropTriggers()
        compose.onNodeWithTag("tab_order_handle_${id("Gamma")}").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -row * 0.25f)) }
            up()
        }
        compose.waitUntil(5_000) { stored() != before }
        assertEquals(stored(), shown())
    }

    /** Pin and up/down whose write fails: the error is shown and the screen keeps agreeing with the stored values. */
    @Test
    fun failedPinAndMoveShowTheErrorAndChangeNothingOnTheScreen() {
        val before = stored()
        failWrites()
        compose.onNodeWithTag("tab_order_pin_${id("Delta")}").performClick()
        compose.waitUntil(10_000) { failureMessageShown() }
        compose.waitForIdle()
        assertEquals(emptySet<String>(), pinnedStored())
        assertEquals(before, shown())
        burst(rowAction("Epsilon", "Move up"))
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals("nothing moved", before, stored())
        assertEquals(before, shown())
        dropTriggers()
        compose.onNodeWithTag("tab_order_pin_${id("Delta")}").performClick()
        waitUntilStored { pinnedStored() == setOf("Delta") }
        assertEquals(stored(), shown())
    }

    // ---- presses before the last write is stored

    /** Three "Move up" presses in a row, before the screen has the first result: all three count. */
    @Test
    fun repeatedMovesBeforeTheFirstIsStoredAllCount() {
        val before = stored()
        val group = before.toMutableList()
        val up = rowAction("Epsilon", "Move up")
        burst(up, up, up)
        val expected = group.also { it.add(it.indexOf("Epsilon") - 3, it.removeAt(it.indexOf("Epsilon"))) }
        waitUntilStored { stored() == expected }
        compose.waitForIdle()
        assertEquals(expected, stored())
        assertEquals(expected, shown())
    }

    /** Moves of two different rows in a row: the second is not computed from the order the first already replaced. */
    @Test
    fun movesOfTwoRowsBeforeTheFirstIsStoredAreBothKept() {
        val before = stored()
        val expected = before.toMutableList().also {
            it.add(it.indexOf("Epsilon") - 1, it.removeAt(it.indexOf("Epsilon")))
            it.add(it.indexOf("Gamma") - 1, it.removeAt(it.indexOf("Gamma")))
        }
        burst(rowAction("Epsilon", "Move up"), rowAction("Gamma", "Move up"))
        waitUntilStored { stored() == expected }
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals("both moves are in the stored order", expected, stored())
        assertEquals(expected, shown())
    }

    /** A pin and a move of other rows in a row, and two pins: nothing is dropped, and none is undone by an older result. */
    @Test
    fun pinsAndMovesInARowAreAllApplied() {
        val before = stored()
        burst(click("tab_order_pin_${id("Alpha")}"), click("tab_order_pin_${id("Beta")}"), rowAction("Epsilon", "Move up"))
        waitUntilStored { pinnedStored() == setOf("Alpha", "Beta") }
        // Epsilon is one place above where it was among the others; Alpha and Beta are in front.
        val others = before.filter { it != "Alpha" && it != "Beta" }.toMutableList()
        others.add(others.indexOf("Epsilon") - 1, others.removeAt(others.indexOf("Epsilon")))
        val expected = listOf("Alpha", "Beta") + others
        waitUntilStored { stored() == expected }
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals(expected, stored())
        assertEquals(expected, shown())
    }

    /** A drag started right after a press whose write is still on its way: both are in the stored order afterwards. */
    @Test
    fun aDragRightAfterAMoveKeepsTheMove() {
        val before = stored()
        val row = compose.onNodeWithTag("tab_order_item_${id("Gamma")}").fetchSemanticsNode().size.height.toFloat()
        // Epsilon up one place; at once Gamma up one place with the handle.
        burst(rowAction("Epsilon", "Move up"))
        compose.onNodeWithTag("tab_order_handle_${id("Gamma")}").performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(0f, -row * 0.3f)) }
            up()
        }
        val expected = before.toMutableList().also {
            it.add(it.indexOf("Epsilon") - 1, it.removeAt(it.indexOf("Epsilon")))
            it.add(it.indexOf("Gamma") - 1, it.removeAt(it.indexOf("Gamma")))
        }
        waitUntilStored { stored() == expected }
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals(expected, stored())
        assertEquals(expected, shown())
    }
}
