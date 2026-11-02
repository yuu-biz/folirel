package io.github.yuubiz.folirel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.data.preferences.SwipeActions
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.ReadFilter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F3 on a device: a swipe on a List row toggles read or saved and snaps back, a short swipe changes nothing, a row that the filter
 * then hides leaves with the Undo snackbar, and with swipe actions off a horizontal drag on a row pages between categories again.
 */
@RunWith(AndroidJUnit4::class)
class SwipeActionsUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var seeded: Map<String, List<String>>
    private val alpha get() = ArticleScope.Category(seeded.keys.elementAt(0))
    private val settings get() = TestData.container.settings

    @Before
    fun setUp() {
        TestData.reset()
        seeded = TestData.seedCategories(listOf("Alpha", "Beta"), perCategory = 20)
        runBlocking {
            settings.setSwipeActions(SwipeActions.RIGHT_READ_LEFT_SAVED)
            settings.setLastScope(alpha)
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodes(row(first())).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun first() = seeded.getValue(alpha.categoryId).first()

    private fun row(id: String) = hasTestTag("article_$id") and hasAnyAncestor(hasTestTag("page_${alpha.encode()}"))

    private fun until(timeoutMs: Long = 5_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs) { condition() }

    private fun string(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun swipingARowTogglesReadAndSavedAndAShortSwipeChangesNothing() {
        launch()
        val id = first()
        compose.onNode(row(id)).performTouchInput { swipeRight() }
        until { TestData.state(id).isRead }
        assertFalse(TestData.state(id).isSaved)
        compose.onNode(row(id)).performTouchInput { swipeRight() }
        until { !TestData.state(id).isRead }

        compose.onNode(row(id)).performTouchInput { swipeLeft() }
        until { TestData.state(id).isSaved }
        assertFalse("saving leaves read alone", TestData.state(id).isRead)
        compose.onNode(row(id)).performTouchInput { swipeLeft() }
        until { !TestData.state(id).isSaved }

        // Released before the threshold: the row snaps back and nothing is written.
        compose.onNode(row(id)).performTouchInput {
            down(center)
            moveBy(Offset(width * 0.1f, 0f))
            moveBy(Offset(width * 0.05f, 0f))
            up()
        }
        compose.waitForIdle()
        Thread.sleep(500)
        assertFalse(TestData.state(id).isRead)
        assertFalse(TestData.state(id).isSaved)
        compose.onNode(row(id)).assertExists()
        assertEquals("the swipe on a row does not page", alpha, runBlocking { settings.current().lastScope })
    }

    @Test
    fun aRowTheFilterHidesLeavesWithUndo() {
        runBlocking { settings.setLastFilter(ReadFilter.UNREAD) }
        launch()
        val id = first()
        compose.onNode(row(id)).performTouchInput { swipeRight() }
        until { TestData.state(id).isRead }
        until { compose.onAllNodes(row(id)).fetchSemanticsNodes().isEmpty() }
        val undo = hasText(string(R.string.action_undo))
        until { compose.onAllNodes(undo).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(undo).performClick()
        until { !TestData.state(id).isRead }
        until { compose.onAllNodes(row(id)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun withSwipeActionsOffADragOnARowPagesAgain() {
        runBlocking { settings.setSwipeActions(SwipeActions.OFF) }
        launch()
        val id = first()
        compose.onNode(row(id)).performTouchInput { swipeLeft() }
        until { runBlocking { settings.current().lastScope } != alpha }
        assertFalse(TestData.state(id).isRead)
        assertFalse(TestData.state(id).isSaved)
        assertEquals("to the next category", ArticleScope.Category(seeded.keys.elementAt(1)), runBlocking { settings.current().lastScope })
    }
}
