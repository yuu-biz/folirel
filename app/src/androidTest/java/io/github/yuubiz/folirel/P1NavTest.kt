package io.github.yuubiz.folirel

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.ListMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * P1-NAV on the real thing: the real MainActivity (HomeScreen, pager, tab row, view model, Room) with 30 categories, driven by
 * real pointer events (down / move / up). The tab row and the underline are sampled frame by frame with the test's own clock; a
 * trace line per sample goes to logcat (tag `P1NAV`). Emulator, debug build: this says how the movement is built, not how a
 * phone feels.
 */
@RunWith(AndroidJUnit4::class)
class P1NavTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var ids: List<String>
    private lateinit var names: List<String>

    private val density get() = compose.density.density

    @Before
    fun seed() {
        TestData.reset()
        // Short, long, and equal names (two children called "共通" under different parents), 32 categories and the All tab.
        names = buildList {
            add("IT"); add("ss"); add("Matome2chNews"); add("非常に長いカテゴリ名を持つタブ その一")
            addAll((5..28).map { "カテゴリ ${it.toString().padStart(2, '0')}" })
        }
        val paths = names.map { listOf(it) } + listOf(listOf("親A", "共通"), listOf("親B", "共通"))
        val byPath = TestData.seedPaths(paths, perCategory = 6)
        // Tab order is the path order (no explicit order is saved): sort the same way the app does.
        val sorted = paths.sortedBy { it.joinToString("\u0000") }
        ids = sorted.map { byPath.getValue(it.joinToString(" / ")) }
        runBlocking { TestData.container.settings.setListMode(ListMode.LIST) }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun scopeOf(id: String) = ArticleScope.Category(id)

    private fun rect(tag: String): Rect {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val p = node.positionInRoot
        return Rect(p.x, p.y, p.x + node.size.width, p.y + node.size.height)
    }

    private fun page(scope: ArticleScope): Rect? =
        compose.onAllNodes(hasTestTag("page_${scope.encode()}")).fetchSemanticsNodes().firstOrNull()?.let {
            Rect(it.positionInRoot.x, it.positionInRoot.y, it.positionInRoot.x + it.size.width, it.positionInRoot.y + it.size.height)
        }

    /** The scroll of the tab row, from its own semantics. */
    private fun stripScroll(): Float =
        compose.onAllNodes(hasTestTag("tab_row_scroll")).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value?.invoke() ?: 0f

    private fun frames(n: Int) = repeat(n) { Thread.sleep(5); compose.mainClock.advanceTimeByFrame() }

    private fun readCount(): Int = runBlocking {
        val c = TestData.container
        val account = c.subscriptions.ensureLocalAccount()
        c.database.subscriptionDao().feeds(account).sumOf { f -> c.database.articleDao().articlesOfFeed(f.id).count { a -> c.database.articleDao().state(a.id)?.isRead == true } }
    }

    private fun savedScope() = runBlocking { TestData.container.settings.current().lastScope }

    /**
     * Started after the seed, not recreated: a recreated activity keeps its view models, and a home view model made before
     * [TestData.reset] still reads the account the reset deleted (no categories) and the scope chosen before the seed.
     */
    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun launchAt(index: Int) {
        runBlocking { TestData.container.settings.setLastScope(scopeOf(ids[index])) }
        launch()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("tab_${scopeOf(ids[index]).encode()}")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        Thread.sleep(600)
        compose.waitForIdle()
    }

    private class Sample(val fraction: Float, val scroll: Float, val titleCentre: Float, val stripCentre: Float, val barCentre: Float)

    /** One frame's picture: where the pager is (from its page), where the tab row is scrolled, where the titles and the underline are. */
    private fun sample(start: Int, label: String): Sample {
        val startPage = page(scopeOf(ids[start]))
        val pager = rect("category_pager")
        val fraction = if (startPage == null) 1f else ((pager.left - startPage.left) / pager.width).coerceIn(-1f, 2f)
        val next = (if (fraction >= 0) start + 1 else start - 1).coerceIn(0, ids.size - 1)
        val a = rect("tab_${scopeOf(ids[start]).encode()}")
        val b = rect("tab_${scopeOf(ids[next]).encode()}")
        val f = abs(fraction).coerceAtMost(1f)
        val centre = a.center.x + (b.center.x - a.center.x) * f
        val strip = rect("tab_strip")
        val bar = rect("tab_indicator")
        val s = Sample(fraction, stripScroll(), centre, strip.center.x, bar.center.x)
        Log.i("P1NAV", "$label fraction=${"%.3f".format(fraction)} scroll=${"%.1f".format(s.scroll)} titleCentre=${"%.1f".format(centre)} stripCentre=${"%.1f".format(strip.center.x)} barCentre=${"%.1f".format(bar.center.x)} barWidth=${"%.1f".format(bar.width)}")
        return s
    }

    /** P1-T01: the title row and the underline are at the same continuous place for 25 / 50 / 75 % of a drag that is not finished. */
    @Test
    fun rowAndUnderlineFollowAnUnfinishedDragAt25_50_75Percent() {
        launchAt(14)
        val start = 14
        val width = rect("category_pager").width
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("category_pager").performTouchInput { down(center) }
            var moved = 0f
            val rest = sample(start, "rest")
            for (share in listOf(0.25f, 0.5f, 0.75f)) {
                val step = width * share - moved
                moved += step
                compose.onNodeWithTag("category_pager").performTouchInput { moveBy(Offset(-step, 0f)) }
                frames(4)
                val s = sample(start, "drag-${(share * 100).toInt()}%")
                assertTrue("the pager is under way at $share (fraction ${s.fraction})", s.fraction > share - 0.15f && s.fraction < share + 0.05f)
                // Title and underline are the same place (the underline is centred on the interpolated title).
                assertEquals("underline against title at $share", s.titleCentre, s.barCentre, 2f * density)
                // The row follows: the interpolated title is in the middle of the row (the row is not at its end here).
                assertEquals("row against pager at $share", s.stripCentre, s.titleCentre, 6f * density)
                assertTrue("the row moved with the drag (was ${rest.scroll}, now ${s.scroll})", s.scroll > rest.scroll + 10f)
            }
            // The saved selection is the old one still: nothing is committed by a drag that is not finished.
            assertEquals(scopeOf(ids[start]), savedScope())
            compose.onNodeWithTag("category_pager").performTouchInput { up() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    /** P1-T03: the finger goes out and comes back before it is lifted: the row and the underline come back, nothing is committed. */
    @Test
    fun aDragThatComesBackLeavesTheRowWhereItWas() {
        launchAt(14)
        val start = 14
        val width = rect("category_pager").width
        val before = sample(start, "rest-before")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("category_pager").performTouchInput { down(center) }
            compose.onNodeWithTag("category_pager").performTouchInput { moveBy(Offset(-width * 0.5f, 0f)) }
            frames(4)
            val out = sample(start, "out")
            assertTrue("the row went with the drag", abs(out.scroll - before.scroll) > 10f)
            compose.onNodeWithTag("category_pager").performTouchInput { moveBy(Offset(width * 0.5f, 0f)) }
            frames(4)
            val back = sample(start, "back")
            assertEquals("the row is back", before.scroll, back.scroll, 4f * density)
            assertEquals("the underline is back", before.barCentre, back.barCentre, 2f * density)
            compose.onNodeWithTag("category_pager").performTouchInput { up() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals(scopeOf(ids[start]), savedScope())
    }

    /** P1-T04: the first and the last tab: the row stops at its ends, no gap is added for centring. */
    @Test
    fun theEndsOfTheRowStopAtTheEdges() {
        launchAt(0)
        compose.onNodeWithTag("tab_all").performClick()
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        val strip = rect("tab_strip")
        val first = rect("tab_${ArticleScope.All.encode()}")
        assertTrue("the first tab is at the left edge (left ${first.left}, row ${strip.left})", first.left - strip.left < 20f * density)
        val lastScope = scopeOf(ids.last())
        // The last tab is off screen: reached the way a reader does, by scrolling the row to it.
        compose.onNodeWithTag("tab_${lastScope.encode()}").performScrollTo()
        compose.onNodeWithTag("tab_${lastScope.encode()}").performClick()
        compose.waitUntil(10_000) { savedScope() == lastScope }
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        val last = rect("tab_${lastScope.encode()}")
        val strip2 = rect("tab_strip")
        assertTrue("the last tab ends at the right edge of the row (right ${last.right}, row ${strip2.right})", strip2.right - last.right < 20f * density)
    }

    /** P1-T05: the row alone is scrolled by hand: the category stays, and a drag of the pager afterwards continues from where the row is. */
    @Test
    fun scrollingTheRowAloneChoosesNothingAndTheFollowResumesWithoutAJump() {
        launchAt(14)
        val start = 14
        val width = rect("category_pager").width
        val centred = stripScroll()
        compose.onNodeWithTag("tab_row_scroll").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        Thread.sleep(700)
        compose.waitForIdle()
        val browsed = stripScroll()
        Log.i("P1NAV", "browse centred=$centred browsed=$browsed")
        assertTrue("the row moved by hand ($centred -> $browsed)", abs(browsed - centred) > 200f)
        assertEquals("the category did not change", scopeOf(ids[start]), savedScope())
        assertEquals("the page did not change", rect("category_pager").left, page(scopeOf(ids[start]))!!.left, 1f)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("category_pager").performTouchInput { down(center) }
            compose.onNodeWithTag("category_pager").performTouchInput { moveBy(Offset(-width * 0.12f, 0f)) }
            frames(4)
            val first = sample(start, "resume")
            val allowed = 0.2f * (abs(browsed - centred) + 400f)
            assertTrue("the row jumped from $browsed to ${first.scroll} when the pager began to move (allowed $allowed)", abs(first.scroll - browsed) < allowed)
            compose.onNodeWithTag("category_pager").performTouchInput { moveBy(Offset(width * 0.12f, 0f)) }
            frames(4)
            val back = sample(start, "resume-back")
            // The pager is back to within the touch slop (|fraction| of a page), and the displacement fades over a page: so the row is back
            // to within that share of the displacement.
            val slack = (abs(back.fraction) + 0.01f) * (abs(browsed - centred) + 400f)
            assertEquals("the row is where the reader left it when the pager is back", browsed, back.scroll, slack)
            assertEquals(scopeOf(ids[start]), savedScope())
            compose.onNodeWithTag("category_pager").performTouchInput { up() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    /** P1-T06: far categories chosen in quick succession: the last choice is where it ends, and the ones between are never selected. */
    @Test
    fun quickChoicesOfFarCategoriesEndOnTheLastOneAndTouchNothingBetween() {
        launchAt(0)
        val seen = java.util.concurrent.CopyOnWriteArrayList<ArticleScope>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            TestData.container.settings.settings.map { it.lastScope }.distinctUntilChanged().collect { seen += it }
        }
        try {
            compose.mainClock.autoAdvance = false
            try {
                for (i in listOf(25, 4, 20, 9)) {
                    compose.onNodeWithTag("tab_${scopeOf(ids[i]).encode()}").performSemanticsAction(SemanticsActions.OnClick)
                    frames(1)
                }
                frames(6)
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitUntil(10_000) { savedScope() == scopeOf(ids[9]) }
            compose.waitForIdle()
            Thread.sleep(800)
            compose.waitForIdle()
            assertEquals(scopeOf(ids[9]), savedScope())
            // Only the four chosen ones (and the start) ever were the selection; nothing the pager passed on its way.
            val chosen = listOf(0, 25, 4, 20, 9).map { scopeOf(ids[it]) }.toSet()
            assertTrue("selections seen: $seen", seen.all { it in chosen })
            val tab = rect("tab_${scopeOf(ids[9]).encode()}")
            val bar = rect("tab_indicator")
            assertEquals("underline under the chosen tab", tab.center.x, bar.center.x, 2f * density)
            assertEquals("nothing was marked read", 0, readCount())
        } finally {
            job.cancel()
        }
    }

    /** P1-T02: the touch area of every tab is at least 48 dp each way, for short, long and equal names alike. */
    @Test
    fun everyTabHasAFortyEightDpTouchArea() {
        launchAt(14)
        val nodes = compose.onAllNodes(hasTestTag("tab_row_scroll")).fetchSemanticsNodes().first().children
            .filter { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tab_") == true }
        assertEquals(ids.size + 2 + 1, nodes.size) // the listed paths, their two parent categories, and All
        for (node in nodes) {
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            // The size of the tab itself (tabs scrolled out of view have no visible touch bounds).
            assertTrue("$tag is at least 48dp high (${node.size.height / density})", node.size.height >= 48f * density - 1f)
            assertTrue("$tag is at least 48dp wide (${node.size.width / density})", node.size.width >= 48f * density - 1f)
        }
    }

    /** P1-T29: a saved category is never replaced by another one while the order is being read: the saved scope only ever has one value. */
    @Test
    fun aSavedCategoryIsNeverReplacedWhileTheOrderIsLoading() {
        val saved = scopeOf(ids[14])
        runBlocking { TestData.container.settings.setLastScope(saved) }
        val seen = java.util.concurrent.CopyOnWriteArrayList<ArticleScope>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            TestData.container.settings.settings.map { it.lastScope }.collect { seen += it }
        }
        try {
            launch()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("tab_${saved.encode()}")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            Thread.sleep(1_500)
            compose.onNodeWithTag("tab_${saved.encode()}").assertIsSelected()
            assertTrue("the saved scope took other values: $seen", seen.all { it == saved })
        } finally {
            job.cancel()
        }
    }
}
