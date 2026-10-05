package io.github.yuubiz.folirel

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.ui.home.ListAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * UXR-04 to UXR-06 on the real list, frame by frame: what happens to the movement back to the top when the reader acts.
 * A touch ends it at once and nothing resumes it; repeated requests are one movement; a list that is replaced (another
 * category, another filter) is not moved by the request made for the one before.
 */
@RunWith(AndroidJUnit4::class)
class BackToTopInterruptTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var h: ListHarness

    @Before
    fun quietApp() {
        TestData.reset()
        h = ListHarness(compose)
    }

    private fun prepare(mode: ListMode, from: Int = 2000) {
        val pictures = h.makeImages()
        h.show(mode, (0 until BIG).map { h.variedRow(it, pictures) }, showImages = true, cardColumns = 2)
        compose.onNodeWithTag("article_list").performScrollToIndex(from)
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
    }

    private fun touchList() = compose.onNodeWithTag("article_list").performTouchInput { down(center); cancel() }

    /** UXR-04: a finger put on the list stops the movement where it is, and articles that arrive afterwards do not start it again. */
    private fun aTouchEndsTheMovementForGood(mode: ListMode) {
        prepare(mode)
        var next = -1
        val trace = ScrollTrace.record("touch-$mode", compose, h, start = { h.topTick++ }, between = { i ->
            if (i == 8) touchList()
            if (i in listOf(20, 30)) {
                // A refresh delivering after the touch: the list must keep the place the reader stopped at.
                val arrived = (next - 2 until next + 1).map { h.variedRow(it) }
                next -= 3
                h.items = arrived + h.items
            }
        })
        val afterTouch = trace.speeds.drop(10)
        assertTrue("$mode: it moved at all before the touch: ${trace.summary()}", trace.speeds.take(9).any { it > 5f })
        assertTrue("$mode: nothing moved the list after the touch (largest ${afterTouch.maxOf { abs(it) }}px/frame): ${trace.summary()}", afterTouch.all { abs(it) <= 0.5f })
        assertTrue("$mode: it did not reach the top", !h.displayed(h.items.first().id) || h.sample().axis > 0f)
    }

    @Test fun aTouchEndsTheMovementInTheListLayout() = aTouchEndsTheMovementForGood(ListMode.LIST)
    @Test fun aTouchEndsTheMovementInTheMagazineLayout() = aTouchEndsTheMovementForGood(ListMode.MAGAZINE)

    /** UXR-05: the arrow and the tab pressed again and again are one movement: it does not start over, and it ends on the top. */
    private fun repeatedRequestsAreOneMovement(mode: ListMode) {
        prepare(mode)
        val trace = ScrollTrace.record("repeat-$mode", compose, h, start = { h.topTick++ }, between = { i ->
            if (i in listOf(2, 4, 6, 8, 10, 12)) h.topTick++
        })
        assertEquals("$mode: it started over: ${trace.summary()}", 0, trace.restarts)
        assertEquals("$mode: it stopped on its way: ${trace.summary()}", 0, trace.stallFrames)
        assertEquals(0, trace.reversals)
        compose.waitForIdle()
        val f = h.sample()
        assertTrue("$mode: ends on the first article", f.tops[h.items.first().id]?.let { it in 0f..96f } == true)
    }

    @Test fun repeatedRequestsInTheListLayout() = repeatedRequestsAreOneMovement(ListMode.LIST)
    @Test fun repeatedRequestsInTheMagazineLayout() = repeatedRequestsAreOneMovement(ListMode.MAGAZINE)

    /** UXR-06: the list is replaced while it moves (another category or filter): the new list stays where it starts. */
    @Test
    fun aReplacedListIsNotMovedByTheRequestMadeForTheOneBefore() {
        prepare(ListMode.LIST)
        val other = (0 until 2500).map { h.variedRow(it + 10_000) }
        h.host.anchors["other"] = ListAnchor("other", other[1500].id, other[1500].sortAt, 0)
        var startedAt = ""
        val trace = ScrollTrace.record("replace", compose, h, start = { h.topTick++ }, between = { i ->
            if (i == 6) {
                h.listKey = "other"
                h.items = other
            }
            if (i == 8) startedAt = h.sample().tops.keys.first()
        })
        compose.waitForIdle()
        val now = h.sample()
        assertEquals("the new list stayed on the article it started on (${trace.summary()})", other[1500].id, now.tops.keys.first())
        assertTrue("and it was shown at that place right after it replaced the other: $startedAt", startedAt == other[1500].id)
    }


    private fun arrowShown() = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("scroll_to_top")).fetchSemanticsNodes().isNotEmpty()

    /** A real press and release on the arrow (down and up through the pointer input of the list, not the click function and not the tick). */
    private fun pressArrow() {
        if (arrowShown()) compose.onNodeWithTag("scroll_to_top").performTouchInput { down(center); up() }
    }

    /** R01: the arrow pressed again and again while the list is moving is the same request: it joins, it does not stop the movement and start it over. */
    private fun realPressesOnTheArrowJoinTheMovement(mode: ListMode) {
        prepare(mode)
        val trace = ScrollTrace.record("arrow-$mode", compose, h, maxFrames = 300, start = { pressArrow() }, between = { i ->
            if (i in listOf(8, 12, 16, 20)) pressArrow()
        })
        assertEquals("$mode: the movement started over after a press on the arrow: ${trace.summary()}", 0, trace.restarts)
        assertEquals("$mode: the movement stopped on its way: ${trace.summary()}", 0, trace.stallFrames)
        assertEquals(0, trace.reversals)
        // One movement of the usual length: a restart at every press would add the time of a new curve each time.
        assertTrue("$mode: it took ${trace.durationMs} ms: ${trace.summary()}", trace.durationMs <= 700)
        compose.waitForIdle()
        assertTrue("$mode: ends on the first article", h.sample().tops[h.items.first().id]?.let { it in 0f..96f } == true)
    }

    @Test fun realPressesOnTheArrowInTheListLayout() = realPressesOnTheArrowJoinTheMovement(ListMode.LIST)
    @Test fun realPressesOnTheArrowInTheMagazineLayout() = realPressesOnTheArrowJoinTheMovement(ListMode.MAGAZINE)

    /** A press on a card still stops the movement (the user comes first), also in the layouts where the arrow is exempt. */
    @Test
    fun aPressOnACardStillEndsTheMovementAfterArrowPresses() {
        prepare(ListMode.LIST)
        val trace = ScrollTrace.record("card-after-arrow", compose, h, start = { pressArrow() }, between = { i ->
            if (i == 6) pressArrow()
            if (i == 10) touchList()
        })
        assertTrue("moved before the card was pressed: ${trace.summary()}", trace.speeds.take(10).any { it > 5f })
        assertTrue("nothing moved the list after the card press", trace.speeds.drop(12).all { abs(it) <= 0.5f })
    }

    private companion object { const val BIG = 3000 }
}
