package io.github.yuubiz.folirel

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.model.ArticleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1-T08 / P1-T12 with the new ways to choose a category: the picker sheet and the drawer are real selections like a tab, so the
 * movement back to the top (and the following) of the page left ends at that moment, and nothing is answered or read on the way.
 * The real HomeScreen, pager, view model and Room; the frames are run one by one.
 */
@RunWith(AndroidJUnit4::class)
class P1MoveTest : HomeFlowTestBase() {
    private val b = ArticleScope.Uncategorized

    private fun pageOf(scope: ArticleScope, tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_${scope.encode()}"))

    private fun axis(scope: ArticleScope): Float? =
        compose.onAllNodes(pageOf(scope, "article_list")).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()

    private fun frames(n: Int) = repeat(n) { Thread.sleep(3); compose.mainClock.advanceTimeByFrame() }

    private fun framesUntil(limit: Int, condition: () -> Boolean) {
        var n = 0
        while (!condition() && n++ < limit) frames(1)
        assertTrue("not reached in $limit frames", condition())
    }

    private fun existsTag(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun choosingThroughThePickerEndsTheMovementOfTheOldPage() {
        firstFetchThenShow()
        scrolledDown()
        val before = axis(all)!!
        compose.mainClock.autoAdvance = false
        val atSwitch: Float
        val during = ArrayList<Float>()
        try {
            compose.onNode(pageOf(all, "scroll_to_top")).performClick()
            frames(6)
            val moving = axis(all)!!
            assertTrue("the movement was under way ($before -> $moving)", moving < before)
            // The picker opens over the moving list and the reader chooses the other category there.
            compose.onNodeWithTag("scope_title").performClick()
            framesUntil(60) { existsTag("category_picker") && existsTag("picker_item_${b.encode()}") }
            // While the sheet is open the list may still move (it is not a choice yet); the choice ends it.
            compose.onNodeWithTag("picker_item_${b.encode()}").performClick()
            frames(1)
            atSwitch = axis(all) ?: moving
            repeat(10) {
                frames(1)
                axis(all)?.let { during += it }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        until(10_000) { vm.selection.value.scope == b }
        assertTrue("A was still composed while the pager slid: ${during.size} frames", during.size >= 3)
        assertTrue("the old page went on moving after the choice: $atSwitch -> $during", during.all { kotlin.math.abs(it - atSwitch) <= 1f })
        assertEquals("nothing was read", 0, readCount())
    }

    @Test
    fun choosingTheShownCategoryInThePickerDoesNotRestartAnything() {
        firstFetchThenShow()
        scrolledDown()
        val before = axis(all)!!
        compose.onNodeWithTag("scope_title").performClick()
        until(10_000) { existsAnywhere("picker_item_${all.encode()}") }
        compose.onNodeWithTag("picker_item_${all.encode()}").performClick()
        until(10_000) { !existsAnywhere("category_picker") }
        Thread.sleep(800)
        compose.waitForIdle()
        assertEquals("no movement to the top and no notice answered", before, axis(all)!!, 1f)
        assertEquals(all, vm.selection.value.scope)
        assertEquals(0, readCount())
    }
}
