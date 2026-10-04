package io.github.yuubiz.folirel

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.ListMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/**
 * Back to the top through the whole app (view model, Room, pager): from far down a long list, with articles arriving at
 * the head the way a refresh delivers them, the button and a second tap on the selected tab must end on the first
 * article, not a few articles short of it.
 */
@RunWith(AndroidJUnit4::class)
class BackToTopTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val all = ArticleScope.All

    private fun inAllPage(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_${all.encode()}")))

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_${all.encode()}"))).fetchSemanticsNodes().isNotEmpty()

    private fun prepare(mode: ListMode, total: Int) {
        TestData.reset()
        runBlocking { TestData.container.settings.setListMode(mode) }
        TestData.seedArticles(total)
        // The activity of the rule is already running: restart it so that it reads the seeded data and settings.
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { exists("article_list") }
        inAllPage("article_list").performScrollToIndex(total - 300)
        compose.waitForIdle()
        compose.waitUntil(5_000) { exists("scroll_to_top") }
    }

    private fun assertOnTheNewestArticle(label: String) {
        compose.waitForIdle()
        val title = TestData.newestArrivedTitle
        val listTop = inAllPage("article_list").fetchSemanticsNode().boundsInRoot.top
        val node = compose.onAllNodes(hasText(title, substring = false) and hasAnyAncestor(hasTestTag("page_${all.encode()}"))).fetchSemanticsNodes()
        val onScreen = compose.onAllNodes(hasAnyAncestor(hasTestTag("page_${all.encode()}"))).fetchSemanticsNodes()
            .mapNotNull { n -> (if (androidx.compose.ui.semantics.SemanticsProperties.Text in n.config) n.config[androidx.compose.ui.semantics.SemanticsProperties.Text] else null)?.joinToString("") }
            .filter { it.startsWith("新着") || it.startsWith("記事") }.take(6)
        assertTrue("$label: the newest article \"$title\" is on screen; the list shows $onScreen", node.isNotEmpty())
        val gap = node.minOf { it.boundsInRoot.top } - listTop
        assertTrue("$label: the newest article starts ${gap}px below the top of the list", gap in 0f..100f)
    }

    private fun run(mode: ListMode, viaTab: Boolean, arrivalsDuring: Boolean) {
        prepare(mode, 1500)
        val feeder = if (arrivalsDuring) thread { repeat(10) { Thread.sleep(60); TestData.arriveAtTheHead(3) } } else null
        if (!arrivalsDuring) TestData.arriveAtTheHead(5)
        if (viaTab) compose.onNodeWithTag("tab_${all.encode()}").performClick() else inAllPage("scroll_to_top").performClick()
        feeder?.join()
        compose.waitForIdle()
        Thread.sleep(500)
        assertOnTheNewestArticle("$mode viaTab=$viaTab during=$arrivalsDuring")
    }

    @Test fun buttonAfterArrivalsInTheListLayout() = run(ListMode.LIST, viaTab = false, arrivalsDuring = false)
    @Test fun buttonDuringArrivalsInTheListLayout() = run(ListMode.LIST, viaTab = false, arrivalsDuring = true)
    @Test fun buttonDuringArrivalsInTheCompactListLayout() = run(ListMode.COMPACT_LIST, viaTab = false, arrivalsDuring = true)
    @Test fun buttonDuringArrivalsInTheMagazineLayout() = run(ListMode.MAGAZINE, viaTab = false, arrivalsDuring = true)
    @Test fun tabDuringArrivalsInTheListLayout() = run(ListMode.LIST, viaTab = true, arrivalsDuring = true)
    @Test fun tabDuringArrivalsInTheMagazineLayout() = run(ListMode.MAGAZINE, viaTab = true, arrivalsDuring = true)

    /** Following arrivals is only for the moment right after the button; once the user touches the list it keeps their place. */
    @Test
    fun afterTheUserTouchesTheListArrivalsDoNotMoveIt() {
        prepare(ListMode.LIST, 1500)
        TestData.arriveAtTheHead(2)
        inAllPage("scroll_to_top").performClick()
        compose.waitForIdle()
        Thread.sleep(300)
        inAllPage("article_list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        Thread.sleep(300)
        TestData.arriveAtTheHead(3)
        compose.waitForIdle()
        Thread.sleep(600)
        val title = TestData.newestArrivedTitle
        val shown = compose.onAllNodes(hasText(title) and hasAnyAncestor(hasTestTag("page_${all.encode()}"))).fetchSemanticsNodes()
        assertTrue("the newest article stays above the screen after the user scrolled", shown.isEmpty())
    }

    private fun newestArrivedIsOnScreen(): Boolean =
        compose.onAllNodes(hasText(TestData.newestArrivedTitle) and hasAnyAncestor(hasTestTag("page_${all.encode()}"))).fetchSemanticsNodes().isNotEmpty()

    private fun backToTopAndSettle() {
        TestData.arriveAtTheHead(2)
        inAllPage("scroll_to_top").performClick()
        compose.waitForIdle()
        Thread.sleep(300)
    }

    /** Arrivals right after the button are followed; after [touch] they must not move the list. */
    private fun arrivalsAfter(label: String, touch: () -> Unit) {
        prepare(ListMode.LIST, 1500)
        backToTopAndSettle()
        touch()
        compose.waitForIdle()
        Thread.sleep(200)
        TestData.arriveAtTheHead(3)
        compose.waitForIdle()
        Thread.sleep(600)
        assertTrue("$label: the list did not follow the arrivals", !newestArrivedIsOnScreen())
    }

    /** A finger put down on the list without any drag (then lifted without a tap) ends the following. */
    @Test
    fun aFingerPutDownWithoutDraggingEndsTheFollowing() = arrivalsAfter("press") {
        inAllPage("article_list").performTouchInput { down(center); cancel() }
    }

    @Test
    fun aLongPressOnTheListEndsTheFollowing() = arrivalsAfter("long press") {
        inAllPage("article_list").performTouchInput { down(center); advanceEventTime(800); up() }
    }

    /** Using an article's menu ends the following, and the tap itself still reaches the menu button (nothing is swallowed). */
    @Test
    fun operatingAnArticleMenuEndsTheFollowingAndStillOpensTheMenu() = arrivalsAfter("menu") {
        compose.onAllNodes(hasContentDescription("More options") and hasAnyAncestor(hasTestTag("article_list")))[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Mark as read").assertExists()
    }

    /** Pressing the "back to top" button must not cancel the following it starts. */
    @Test
    fun theButtonItselfStartsTheFollowing() {
        prepare(ListMode.LIST, 1500)
        inAllPage("scroll_to_top").performClick()
        TestData.arriveAtTheHead(3)
        compose.waitForIdle()
        Thread.sleep(600)
        assertOnTheNewestArticle("button start")
    }
}
