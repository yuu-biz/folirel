package io.github.yuubiz.folirel.ui.home

import io.github.yuubiz.folirel.model.ArticleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-NAV geometry on the JVM: where the tab row is for a position of the pager (P1-T01/T03/T04 arithmetic). */
class TabFollowTest {
    /** Ten tabs, each 100 wide, centres 50, 150, ...; a 300 wide viewport; the content is 1000 wide so 700 can be scrolled. */
    private val spans = (0 until 10).map { TitleSpan(center = 50f + 100f * it, width = 60f) }
    private val viewport = 300f
    private val max = 700f

    @Test
    fun interpolatesBetweenTheTwoNeighbouringTitles() {
        val mid = TabFollow.interpolate(spans, 2.5f)!!
        assertEquals(300f, mid.center, 0.001f)
        val quarter = TabFollow.interpolate(listOf(TitleSpan(50f, 40f), TitleSpan(250f, 80f)), 0.25f)!!
        assertEquals(100f, quarter.center, 0.001f)
        assertEquals(50f, quarter.width, 0.001f)
    }

    @Test
    fun endsOfTheRowStopAtWhatCanBeScrolled() {
        assertEquals(0f, TabFollow.centred(50f, viewport, max), 0.001f)
        assertEquals(700f, TabFollow.centred(950f, viewport, max), 0.001f)
        assertEquals(350f, TabFollow.centred(500f, viewport, max), 0.001f)
    }

    @Test
    fun aRowThatFitsIsNeverMoved() {
        for (position in listOf(0f, 0.4f, 1f, 2.5f)) assertEquals(0f, TabFollow.centred(50f + 100f * position, 1200f, 0f), 0f)
    }

    @Test
    fun unmeasuredTitlesGiveNoPosition() {
        assertEquals(50f, TabFollow.interpolate(listOf(TitleSpan(50f, 10f), null), 0.5f)!!.center, 0f) // the neighbour is not known yet: stay on this title
        assertNull(TabFollow.interpolate(listOf<TitleSpan?>(null, TitleSpan(150f, 10f)), 0.5f))
        assertNull(TabFollow.interpolate(emptyList(), 0f))
    }

    @Test
    fun followingIsContinuousAcrossAWholeDrag() {
        val follower = TabFollower()
        follower.settleAt(4f)
        var scroll = TabFollow.centred(spans[4].center, viewport, max)
        var last = scroll
        var position = 4f
        while (position < 5f) {
            position += 0.05f
            scroll = follower.scrollFor(position, atRest = false, userBrowsing = false, scroll, spans, viewport, max)!!
            assertTrue("a step of ${scroll - last} px for 5% of a page", scroll - last in 4f..6f)
            last = scroll
        }
        assertEquals(TabFollow.centred(spans[5].center, viewport, max), scroll, 0.5f)
    }

    @Test
    fun theRowComesBackWithTheFingerAndSettlesOnTheFirstPage() {
        val follower = TabFollower()
        follower.settleAt(4f)
        val start = TabFollow.centred(spans[4].center, viewport, max)
        var scroll = start
        val out = listOf(4.25f, 4.5f, 4.75f)
        val back = listOf(4.5f, 4.25f, 4f)
        val trace = ArrayList<Float>()
        for (p in out + back) {
            scroll = follower.scrollFor(p, atRest = p == 4f, userBrowsing = false, scroll, spans, viewport, max)!!
            trace += scroll
        }
        // Symmetric: the same position gives the same scroll on the way out and on the way back.
        assertEquals(trace[1], trace[3], 0.001f)
        assertEquals(trace[0], trace[4], 0.001f)
        assertEquals(start, trace.last(), 0.001f)
    }

    @Test
    fun aRowTheReaderMovedByHandIsNotPulledBackAtTheStart() {
        val follower = TabFollower()
        follower.settleAt(4f)
        val browsed = 100f // the reader scrolled the row far away from the selected title (centred it would be 300)
        val first = follower.scrollFor(4.02f, atRest = false, userBrowsing = false, browsed, spans, viewport, max)!!
        // Two percent of a page move the row by a few px (the displacement fades over a page), not by the 200 px back to the centre.
        assertTrue("jumped from $browsed to $first", kotlin.math.abs(first - browsed) < 10f)
        var scroll = first
        var position = 4.02f
        while (position < 5f) {
            position = (position + 0.1f).coerceAtMost(5f)
            scroll = follower.scrollFor(position, atRest = position == 5f, userBrowsing = false, scroll, spans, viewport, max)!!
        }
        // After a whole page the displacement is gone: the new selection is in the middle.
        assertEquals(TabFollow.centred(spans[5].center, viewport, max), scroll, 0.5f)
    }

    @Test
    fun aFarJumpEndsCentredOnTheTarget() {
        val follower = TabFollower()
        follower.settleAt(0f)
        val scroll = follower.scrollFor(7f, atRest = true, userBrowsing = false, 0f, spans, viewport, max)!!
        assertEquals(TabFollow.centred(spans[7].center, viewport, max), scroll, 0.001f)
    }

    @Test
    fun theFingerOnTheRowKeepsItsPlaceWhileThePagerMoves() {
        val follower = TabFollower()
        follower.settleAt(4f)
        val scroll = follower.scrollFor(4.3f, atRest = false, userBrowsing = true, 123f, spans, viewport, max)!!
        assertEquals(123f, scroll, 0.5f)
    }

    // The settled page against the choice that is on its way.

    private val a = ArticleScope.Category("a")
    private val b = ArticleScope.Category("b")
    private val c = ArticleScope.Category("c")

    @Test
    fun aSwipeThatSettlesElsewhereIsCommitted() {
        val guard = SettleGuard(a)
        assertEquals(b, guard.onSettled(atPage = b, selected = a, drags = 1))
    }

    @Test
    fun theOldPageSettlingAfterATapDoesNotUndoTheTap() {
        val guard = SettleGuard(a)
        // a is the page the pager is on; b was tapped; the pager reports that it settled on a (the animation it was running ended).
        assertNull(guard.onSettled(atPage = a, selected = b, drags = 0))
    }

    @Test
    fun aDragAfterTheTapIsTheReaderAgain() {
        val guard = SettleGuard(a)
        assertEquals(c, guard.onSettled(atPage = c, selected = b, drags = 1))
    }

    @Test
    fun arrivingAtTheChoiceAgrees() {
        val guard = SettleGuard(a)
        assertNull(guard.onSettled(atPage = b, selected = b, drags = 0))
        // From now on b is what both agree on: a later stale report of a is again an ordinary swipe only if a drag happened.
        assertNull(guard.onSettled(atPage = a, selected = c, drags = 0))
    }
}
