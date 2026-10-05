package io.github.yuubiz.folirel

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.model.ArticleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R02 on the real home screen (pager, tabs, view model, Room): when another category is chosen, the movement back to the top
 * and the four seconds of following that the page of the category left belong to it, and both end at that moment, also while
 * that page is still composed (the pager keeps it until the slide has settled). Pages: All (A) and Uncategorized (B), the two
 * pages the two uncategorized feeds of [HomeFlowTestBase] give.
 */
@RunWith(AndroidJUnit4::class)
class HomePagerMoveTest : HomeFlowTestBase() {
    private val b = ArticleScope.Uncategorized

    private fun pageOf(scope: ArticleScope, tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_${scope.encode()}"))

    private fun axis(scope: ArticleScope): Float? =
        compose.onAllNodes(pageOf(scope, "article_list")).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()

    private fun frames(n: Int) = repeat(n) { Thread.sleep(3); compose.mainClock.advanceTimeByFrame() }

    private fun pressTab(scope: ArticleScope) {
        compose.onNode(hasTestTag("tab_${scope.encode()}")).performClick()
    }

    private fun pressArrowOfAllPage() {
        compose.onNode(pageOf(all, "scroll_to_top")).performClick()
    }

    private fun bannerCount(scope: ArticleScope): String? =
        compose.onAllNodes(pageOf(scope, "new_articles_banner")).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    /** The slide to B is run frame by frame; A is still composed while it slides, and must not go on moving. */
    @Test
    fun choosingAnotherCategoryEndsTheMovementOfTheOldPage() {
        firstFetchThenShow()
        scrolledDown()
        val before = axis(all)!!
        compose.mainClock.autoAdvance = false
        val atSwitch: Float
        val duringSlide = ArrayList<Float>()
        var released = 0f
        try {
            pressArrowOfAllPage()
            frames(6)
            val moving = axis(all)!!
            assertTrue("the movement was under way ($before -> $moving)", moving < before)
            pressTab(b)
            // The choice reaches the page with the next frame (state, recomposition, effect): that frame may still move it once.
            frames(1)
            atSwitch = axis(all) ?: moving
            repeat(10) {
                frames(1)
                axis(all)?.let { duringSlide += it }
            }
            // The movement was ended, so the articles it was holding are released: arrivals now reach the page (they sit above the reader).
            oneNew = (0 until 5).map { "解放後の新着 $it" }
            refresh()
            frames(4)
            released = axis(all) ?: 0f
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        until(10_000) { vm.selection.value.scope == b }
        assertTrue("the articles held by the ended movement were not released (axis $released, was $atSwitch)", released > atSwitch + 1_000f)
        assertTrue("page A was still composed while the slide ran (otherwise this checks nothing): ${duringSlide.size} frames", duringSlide.size >= 3)
        assertTrue("the old page went on moving after B was chosen: from $atSwitch to $duringSlide", duringSlide.all { kotlin.math.abs(it - atSwitch) <= 1f })
        assertTrue("B is on its own place, unmoved by A's movement", (axis(b) ?: 0f) >= 0f)
        // Back to A: the place where the switch cut the movement is kept; the movement does not resume.
        pressTab(all)
        until(10_000) { vm.selection.value.scope == all }
        Thread.sleep(800)
        compose.waitForIdle()
        val back = axis(all)!!
        assertTrue("back on A it stays where the switch left it ($atSwitch), not at the top ($back)", back > 1_000f)
    }

    /** The four seconds of following after the arrow end with the switch: articles that arrive on A afterwards do not move it. */
    @Test
    fun choosingAnotherCategoryEndsTheFollowingOfTheOldPage() {
        firstFetchThenShow()
        scrolledDown()
        pressArrowOfAllPage()
        until(10_000) { (axis(all) ?: 1f) == 0f } // the movement has arrived; the following is armed
        compose.mainClock.autoAdvance = false
        val axisAtSwitch: Float
        val after = ArrayList<Float>()
        try {
            pressTab(b)
            axisAtSwitch = axis(all) ?: 0f
            frames(2)
            // New articles arrive while A is still composed (its slide away has not settled).
            oneNew = (0 until 12).map { "追従後の新着 $it" }
            refresh()
            repeat(10) {
                frames(1)
                axis(all)?.let { after += it }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        until(10_000) { vm.selection.value.scope == b }
        assertTrue("A was still composed while the articles arrived: ${after.size} frames", after.size >= 3)
        // The articles arrived above A's first article; following would have kept the axis at 0, not left it with the arrival.
        assertTrue("A followed the arrival after B was chosen (it would stay at 0): $axisAtSwitch -> $after", after.takeLast(3).all { it > 1_000f })
        // The notice for the arrivals is the view model's, untouched by the page that was left: it is still announced for B.
        assertEquals("nothing acknowledged the arrivals", 12, vm.newArticleIds.value.size)
        // Back to A: its place is the one kept for it (the first article it had), the arrivals wait above it behind the notice.
        pressTab(all)
        until(10_000) { vm.selection.value.scope == all }
        Thread.sleep(800)
        compose.waitForIdle()
        assertTrue("back on A the arrivals are announced and the list did not jump to them: ${bannerCount(all)}", bannerCount(all)?.contains("12") == true)
        assertEquals("the switch answered nothing", 12, vm.newArticleIds.value.size)
        assertEquals(0, readCount())
    }
}
