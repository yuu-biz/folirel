package io.github.yuubiz.folirel.ui.home

import io.github.yuubiz.folirel.data.preferences.CategoryNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RSP-T24..T28 and the width part of T31..T38: pane or drawer from the window's width, text size and the reader's choice. */
class NavigationLayoutTest {
    private fun pane(width: Float, scale: Float, choice: CategoryNavigation) = NavigationLayout.resolve(width, scale, choice).pane

    @Test
    fun autoIsTheDrawerOnCompactAndMediumAndAPaneOnExpandedWindows() {
        // RSP-T24 / T25 (and T31..T35 by width)
        for (width in listOf(320f, 411f, 599f, 600f, 733f, 839f)) assertFalse("auto at $width", pane(width, 1f, CategoryNavigation.AUTO))
        for (width in listOf(840f, 1000f, 1200f, 2000f)) assertTrue("auto at $width", pane(width, 1f, CategoryNavigation.AUTO))
    }

    @Test
    fun paneWhenPossibleNeedsAMediumWindowAndAReadableArticleArea() {
        // RSP-T26: from 600 dp, while the articles keep their width.
        assertFalse(pane(599f, 1f, CategoryNavigation.PANE))
        assertTrue(pane(600f, 1f, CategoryNavigation.PANE))
        assertTrue(pane(733f, 1f, CategoryNavigation.PANE))
        assertTrue(pane(1200f, 1f, CategoryNavigation.PANE))
        // 600 dp with larger text: the pane would leave the articles too narrow, so it is the drawer.
        assertFalse(pane(600f, 1.3f, CategoryNavigation.PANE))
        assertFalse(pane(733f, 1.3f, CategoryNavigation.PANE))
        assertTrue(pane(800f, 1.3f, CategoryNavigation.PANE))
    }

    @Test
    fun drawerOnlyIsTheDrawerAtEveryWidth() {
        // RSP-T27
        for (width in listOf(320f, 733f, 840f, 1200f, 3000f)) assertFalse(pane(width, 1f, CategoryNavigation.DRAWER))
    }

    @Test
    fun aCompactWindowAlwaysHasTheDrawerWhateverWasChosen() {
        // RSP-T28
        for (choice in CategoryNavigation.entries) for (width in listOf(240f, 360f, 411f, 599.9f)) {
            assertFalse("$choice at $width", pane(width, 1f, choice))
            assertNull(NavigationLayout.resolve(width, 1f, choice).paneWidthDp)
        }
    }

    @Test
    fun theAnswerFollowsTheWidthAlone() {
        // A fold, a rotation or a split-screen resize is only a new width: the same inputs always give the same answer.
        val widths = listOf(411f, 840f, 411f, 1200f, 733f, 1200f)
        val first = widths.map { NavigationLayout.resolve(it, 1f, CategoryNavigation.AUTO) }
        val second = widths.map { NavigationLayout.resolve(it, 1f, CategoryNavigation.AUTO) }
        assertEquals(first, second)
        assertEquals(listOf(false, true, false, true, false, true), first.map { it.pane })
    }

    @Test
    fun theWidthOfThePaneIsModestAndGrowsALittleWithTextButNeverTakesTheWindow() {
        assertEquals(240f, NavigationLayout.paneWidthDp(1f), 0.01f)
        assertEquals(240f, NavigationLayout.paneWidthDp(0.8f), 0.01f) // smaller text does not shrink it below the base
        assertTrue(NavigationLayout.paneWidthDp(1.15f) > 240f)
        assertEquals(NavigationLayout.MAX_PANE_DP, NavigationLayout.paneWidthDp(2f), 0.01f)
        for (width in listOf(600f, 733f, 840f, 1200f)) for (scale in listOf(1f, 1.3f, 2f)) {
            val result = NavigationLayout.resolve(width, scale, CategoryNavigation.PANE)
            val paneWidth = result.paneWidthDp ?: continue
            assertTrue(paneWidth in NavigationLayout.MIN_PANE_DP..NavigationLayout.MAX_PANE_DP)
            // The article area keeps at least the readable width for this text size (it never goes below 360 dp).
            assertTrue("$width/$scale: ${width - paneWidth}", width - paneWidth >= NavigationLayout.MIN_CONTENT_DP)
        }
        // 200 % text on a wide window still has a pane (the article area is one column there).
        assertTrue(pane(1200f, 2f, CategoryNavigation.AUTO))
    }

    /** The pane takes room from the articles: the columns follow the width that is left (RSP-T30 / the comparison in the report). */
    @Test
    fun columnsFollowTheWidthLeftBesideThePane() {
        val pane = NavigationLayout.paneWidthDp(1f)
        assertEquals(5, CardGridLayout.columns(1200f, 1f, 0).count)
        assertEquals(4, CardGridLayout.columns(1200f - pane, 1f, 0).count)
        assertEquals(3, CardGridLayout.columns(840f, 1f, 0).count)
        assertEquals(2, CardGridLayout.columns(840f - pane, 1f, 0).count)
    }
}
