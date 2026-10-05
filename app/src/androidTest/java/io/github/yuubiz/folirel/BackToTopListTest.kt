package io.github.yuubiz.folirel

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.model.ListMode
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back to the top on the real list (3,000 articles of varied height): the arrow and the second tap on the selected tab
 * must end on the first article, also when articles arrive at the head the way a refresh delivers them, after the
 * movement and while it runs. (These are the end-position cases of the former banner test; how the movement looks on its
 * way is in [UxrScrollTraceTest].)
 */
@RunWith(AndroidJUnit4::class)
class BackToTopListTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var h: ListHarness

    @Before
    fun quietApp() {
        TestData.reset()
        h = ListHarness(compose)
    }

    private fun firstArticleGap(firstId: String): Float {
        val list = compose.onNodeWithTag("article_list").fetchSemanticsNode().positionInRoot.y
        return compose.onNodeWithTag("article_$firstId").fetchSemanticsNode().positionInRoot.y - list
    }

    private fun assertAtTop(label: String, firstId: String) {
        compose.waitUntil(5_000) { h.displayed(firstId) }
        val gap = firstArticleGap(firstId)
        assertTrue("$label: $firstId is the first article but sits ${gap}px from the top of the list", gap in 0f..24f * 4)
    }

    private fun farDownThenArrivals(mode: ListMode): String {
        h.show(mode, (0 until BIG).map(h::variedRow))
        compose.onNodeWithTag("article_list").performScrollToIndex(BIG - 400)
        compose.waitForIdle()
        val arrived = (-5..-1).map { h.variedRow(it) }
        h.items = arrived + h.items
        h.newIds = arrived.map { it.id }.toSet()
        compose.waitForIdle()
        return arrived.first().id
    }

    private fun buttonAfterArrivals(mode: ListMode) {
        val first = farDownThenArrivals(mode)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("scroll_to_top")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("scroll_to_top").performClick()
        compose.waitForIdle()
        assertAtTop("$mode button", first)
    }

    private fun tabTapAfterArrivals(mode: ListMode) {
        val first = farDownThenArrivals(mode)
        h.topTick++
        compose.waitForIdle()
        assertAtTop("$mode tab", first)
    }

    /** More articles arrive while the movement back to the top is running. */
    private fun whileArticlesArrive(mode: ListMode) {
        h.show(mode, (0 until BIG).map(h::variedRow))
        compose.onNodeWithTag("article_list").performScrollToIndex(BIG - 400)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            h.topTick++
            compose.mainClock.advanceTimeBy(150)
            val arrived = (-5..-1).map { h.variedRow(it) }
            h.items = arrived + h.items
            h.newIds = arrived.map { it.id }.toSet()
            compose.mainClock.advanceTimeBy(100)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertAtTop("$mode during arrivals", "a-5")
    }

    /** A refresh delivers feed after feed: the list is replaced again and again while the way back to the top is moving. */
    private fun whileManyBatchesArrive(mode: ListMode) {
        h.show(mode, (0 until BIG).map(h::variedRow))
        compose.onNodeWithTag("article_list").performScrollToIndex(BIG - 400)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        var next = -1
        try {
            h.topTick++
            repeat(12) {
                compose.mainClock.advanceTimeBy(48)
                val arrived = (next - 3 until next + 1).map { h.variedRow(it) }
                next -= 4
                h.items = arrived + h.items
                h.newIds = h.newIds + arrived.map { it.id }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertAtTop("$mode many batches", h.items.first().id)
    }

    @Test fun whileManyBatchesArriveInTheListLayout() = whileManyBatchesArrive(ListMode.LIST)
    @Test fun whileManyBatchesArriveInTheCompactListLayout() = whileManyBatchesArrive(ListMode.COMPACT_LIST)
    @Test fun whileManyBatchesArriveInTheMagazineLayout() = whileManyBatchesArrive(ListMode.MAGAZINE)

    @Test fun buttonAfterArrivalsInTheListLayout() = buttonAfterArrivals(ListMode.LIST)
    @Test fun buttonAfterArrivalsInTheCompactListLayout() = buttonAfterArrivals(ListMode.COMPACT_LIST)
    @Test fun buttonAfterArrivalsInTheMagazineLayout() = buttonAfterArrivals(ListMode.MAGAZINE)
    @Test fun tabTapAfterArrivalsInTheListLayout() = tabTapAfterArrivals(ListMode.LIST)
    @Test fun tabTapAfterArrivalsInTheCompactListLayout() = tabTapAfterArrivals(ListMode.COMPACT_LIST)
    @Test fun tabTapAfterArrivalsInTheMagazineLayout() = tabTapAfterArrivals(ListMode.MAGAZINE)
    @Test fun whileArticlesArriveInTheListLayout() = whileArticlesArrive(ListMode.LIST)
    @Test fun whileArticlesArriveInTheCompactListLayout() = whileArticlesArrive(ListMode.COMPACT_LIST)
    @Test fun whileArticlesArriveInTheMagazineLayout() = whileArticlesArrive(ListMode.MAGAZINE)

    private companion object { const val BIG = 3000 }
}
