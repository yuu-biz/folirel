package io.github.yuubiz.folirel

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.model.ListMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UXR-01 to UXR-03: the way back to the top as a movement, not only as an end point. The real list is driven with the
 * test's own frame clock (one sample per 16 ms frame; pictures load for real between the frames) and the per-frame
 * position is analysed for stops and restarts, reversals, long skips and a late snap. Run on an emulator these say
 * how the movement is built; they say nothing about the frame rate of a real phone.
 */
@RunWith(AndroidJUnit4::class)
class UxrScrollTraceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var h: ListHarness

    @Before
    fun quietApp() {
        TestData.reset()
        h = ListHarness(compose)
    }

    private fun prepare(mode: ListMode, images: Boolean, from: Int, columns: Int = 0, widthDp: Int? = null) {
        val pictures = if (images) h.makeImages() else emptyList()
        h.show(mode, (0 until BIG).map { h.variedRow(it, pictures) }, showImages = images, cardColumns = columns, widthDp = widthDp)
        compose.onNodeWithTag("article_list").performScrollToIndex(from)
        compose.waitForIdle()
        // Let the pictures of the screen we start from arrive, so the start is a settled list.
        Thread.sleep(400)
        compose.waitForIdle()
    }

    private fun assertAtTop(label: String) {
        compose.waitForIdle()
        val f = h.sample()
        val first = h.items.first().id
        val gap = f.tops[first]
        assertTrue("$label: $first is the first article but the list shows ${f.tops.keys.take(3)} (axis ${f.axis})", gap != null)
        assertTrue("$label: $first sits ${gap}px from the top of the list", gap!! in 0f..(24f * compose.density.density))
    }

    private fun assertSmooth(t: ScrollTrace, maxMs: Int) {
        assertTrue("moved at all: ${t.summary()}", t.durationMs > 0)
        assertEquals("a stop inside the movement: ${t.summary()}", 0, t.stallFrames)
        assertEquals("the movement started again after nearly stopping: ${t.summary()}", 0, t.restarts)
        assertEquals("a frame moved away from the top: ${t.summary()}", 0, t.reversals)
        assertTrue("more than one long skip: ${t.summary()}", t.teleports <= 1)
        assertTrue("took ${t.durationMs} ms, expected at most $maxMs: ${t.summary()}", t.durationMs <= maxMs)
    }

    private fun backToTop(label: String, mode: ListMode, images: Boolean, from: Int, columns: Int = 0, widthDp: Int? = null) {
        prepare(mode, images, from, columns, widthDp)
        val trace = ScrollTrace.record(label, compose, h, start = { h.topTick++ })
        assertSmooth(trace, if (from < 50) MAX_NEAR_MS else MAX_FAR_MS)
        assertTrue("$label: no instant correction at the end: ${trace.summary()}", trace.finalSnapPx == 0f)
        assertAtTop(label)
    }

    @Test fun nearListWithImages() = backToTop("near-list", ListMode.LIST, true, NEAR)
    @Test fun nearCompactList() = backToTop("near-compact", ListMode.COMPACT_LIST, false, NEAR)
    @Test fun nearMagazineTwoColumnsWithImages() = backToTop("near-magazine2", ListMode.MAGAZINE, true, NEAR, columns = 2)
    @Test fun farListWithImages() = backToTop("far-list", ListMode.LIST, true, FAR)
    @Test fun farCompactList() = backToTop("far-compact", ListMode.COMPACT_LIST, false, FAR)
    @Test fun farMagazineTwoColumnsWithImages() = backToTop("far-magazine2", ListMode.MAGAZINE, true, FAR, columns = 2)
    @Test fun farMagazineThreeColumnsWithImages() = backToTop("far-magazine3", ListMode.MAGAZINE, true, FAR, columns = 3, widthDp = 560)

    /** Batches of articles arrive at the head while the list moves (a refresh delivers feed after feed). */
    private fun withArrivals(label: String, mode: ListMode, images: Boolean, columns: Int = 0) {
        val pictures = if (images) h.makeImages() else emptyList()
        prepare(mode, images, FAR, columns)
        var next = -1
        val trace = ScrollTrace.record(label, compose, h, start = { h.topTick++ }, between = { i ->
            if (i in listOf(4, 12, 20, 28)) {
                val arrived = (next - 2 until next + 1).map { h.variedRow(it, pictures) }
                next -= 3
                h.items = arrived + h.items
                h.newIds = h.newIds + arrived.map { it.id }
            }
        })
        assertSmooth(trace, MAX_FAR_MS + 400)
        assertAtTop(label)
        assertTrue("$label: the list ended on the newest article, not chasing: ${trace.summary()}", trace.durationMs < 2_500)
    }

    @Test fun arrivalsWhileMovingInTheListLayout() = withArrivals("arrive-list", ListMode.LIST, true)
    @Test fun arrivalsWhileMovingInTheMagazineLayout() = withArrivals("arrive-magazine", ListMode.MAGAZINE, true, columns = 2)

    private companion object {
        const val BIG = 3000
        const val NEAR = 12
        const val FAR = 2600
        const val MAX_NEAR_MS = 700
        const val MAX_FAR_MS = 900
    }
}
