package io.github.yuubiz.folirel

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.ui.home.BentoPlan
import io.github.yuubiz.folirel.ui.home.CardGridLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Masonry and Bento layouts on the real article list: lanes and tiles as planned, nothing overlapping, pictures that do not move
 * anything when they arrive, the way back to the top, and the article kept in view when the layout changes.
 */
@RunWith(AndroidJUnit4::class)
class TileLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var m: MagazineHost
    private lateinit var h: ListHarness

    @Before
    fun setUp() {
        TestData.reset()
        m = MagazineHost(compose)
        h = ListHarness(compose)
    }

    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(1_200)
        compose.waitForIdle()
    }

    private fun articles(n: Int) = (0 until n).map { i ->
        m.article(
            i,
            when (i % 3) { 0 -> "短い見出し $i"; 1 -> "二行になる見出しの例です。少し長めの文章で $i"; else -> "三行になる長い見出しの例です。まとめサイトの記事タイトルはこのくらい長いことがよくあります $i" },
            when (i % 4) { 0 -> ""; 1 -> "続きを読む"; 2 -> "短い概要です。"; else -> "通常の概要の文章です。記事の内容を一、二行で説明しています。".repeat(2) },
            image = if (i % 5 == 4) null else m.pictures[i % m.pictures.size],
        )
    }

    private fun overlaps(a: Rect, b: Rect) = a.left < b.right - 1f && b.left < a.right - 1f && a.top < b.bottom - 1f && b.top < a.bottom - 1f

    private val listHeight get() = compose.onNodeWithTag("article_list").fetchSemanticsNode().size.height

    @Test
    fun masonryPutsCardsOfDifferentHeightsIntoTwoLanesWithoutMovingWhenPicturesArrive() {
        m.show(articles(30), mode = ListMode.MASONRY, columns = 0)
        val early = m.cards()
        settle()
        val cards = m.cards()
        val lanes = cards.values.map { (it.left / 10).toInt() }.toSet()
        assertEquals("two lanes on a phone: ${cards.values.map { it.left }}", 2, lanes.size)
        assertTrue("cards have different heights", cards.values.map { it.height.toInt() / 4 }.toSet().size >= 3)
        for ((a, ra) in cards) for ((b, rb) in cards) if (a < b) assertTrue("$a and $b overlap", !overlaps(ra, rb))
        for (id in early.keys.intersect(cards.keys)) assertEquals("$id kept its height while its picture arrived", early.getValue(id).height, cards.getValue(id).height, 0.5f)
        // Text ends above the source row.
        for ((id, card) in cards.filterValues { it.bottom < listHeight }) for (s in m.partsOf(id, "card_summary")) assertTrue("$id", s.bottom <= card.bottom - 44f * m.density)
    }

    @Test
    fun bentoTilesFollowThePlanFillTheWidthAndNeverOverlap() {
        val items = articles(40)
        m.show(items, mode = ListMode.BENTO, columns = 0)
        settle()
        val cards = m.cards()
        val width = compose.onNodeWithTag("article_list").fetchSemanticsNode().size.width
        val cellW = BentoPlan.cellWidthDp(m.dp(width.toFloat()), 2)
        val cellH = BentoPlan.cellHeightDp(cellW, 21f)
        val blocks = BentoPlan.blocks(items.map { it.id }, 2, fromEnd = true) // the host lists newest first
        for (block in blocks.take(3)) for (tile in block.tiles) {
            val r = cards["md${tile.article}"] ?: continue
            val w = tile.shape.colSpan * cellW + (tile.shape.colSpan - 1) * CardGridLayout.GAP_DP
            val hh = tile.shape.rowSpan * cellH + (tile.shape.rowSpan - 1) * CardGridLayout.GAP_DP
            assertEquals("md${tile.article} (${tile.shape}) width", w, m.dp(r.width), 1.5f)
            assertEquals("md${tile.article} (${tile.shape}) height", hh, m.dp(r.height), 1.5f)
        }
        for ((a, ra) in cards) for ((b, rb) in cards) if (a < b) assertTrue("$a and $b overlap", !overlaps(ra, rb))
        for (r in cards.values) assertTrue("inside the list's width", r.left >= -1f && r.right <= width + 1f)
        // Text ends above the source row, and every tile keeps a 48 dp menu.
        val more = compose.onAllNodes(hasContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_more))).fetchSemanticsNodes()
        for ((id, card) in cards.filterValues { it.bottom < listHeight }) {
            for (s in m.partsOf(id, "card_summary")) assertTrue("$id summary inside", s.bottom <= card.bottom - 44f * m.density)
            val button = more.firstOrNull { it.positionInRoot.y in card.top..card.bottom && it.positionInRoot.x in card.left..card.right }
            assertTrue("$id has its menu", button != null && button.touchBoundsInRoot.height >= 48f * m.density - 1f)
        }
    }

    @Test
    fun bentoAtTwiceTheTextSizeStaysReadable() {
        m.show(articles(20), mode = ListMode.BENTO, columns = 2, fontScale = 2f)
        settle()
        val cards = m.cards()
        // 200 % text: the tile layouts fall back to one column (two do not fit), and the cells grow with the text.
        val widths = cards.values.map { m.dp(it.width).toInt() / 4 }.toSet()
        assertEquals("one column: $widths", 1, widths.size)
        for ((id, card) in cards.filterValues { it.bottom < listHeight }) for (s in m.partsOf(id, "card_summary")) assertTrue("$id", s.bottom <= card.bottom - 44f * m.density)
    }

    /**
     * New articles at the head (newest first). Bento: every tile on screen stays where it was, with the same size (blocks are packed
     * from the oldest article). Masonry: the article being read stays in view; the lanes under it may be dealt again (each card goes
     * under the shortest lane counted from the first article), as Magazine rows are.
     */
    @Test
    fun tilesOnScreenStayPutWhenArticlesArriveAtTheHead() {
        val pictures = h.makeImages()
        for (mode in listOf(ListMode.MASONRY, ListMode.BENTO)) {
            h.listKey = "arrive-$mode"
            h.show(mode, (0 until 120).map { h.variedRow(it, pictures) }, showImages = true)
            compose.onNodeWithTag("article_list").performScrollToIndex(if (mode == ListMode.BENTO) 20 else 50)
            settle()
            val before = h.sample()
            for (added in 1..3) {
                val arrived = (-added downTo -added).map { h.variedRow(it, pictures) }
                h.items = arrived + h.items
                h.newIds = h.newIds + arrived.map { it.id }
                settle()
            }
            val after = h.sample()
            val seen = before.tops.keys.filter { before.tops.getValue(it) in 0f..before.listHeight - before.heights.getValue(it) }
            assertTrue("$mode: cards were in view", seen.size >= 3)
            val reading = seen.minBy { before.tops.getValue(it) }
            assertTrue("$mode: still reading $reading", (after.tops[reading] ?: -999f) in -1f..after.listHeight)
            if (mode == ListMode.MASONRY) continue
            for (id in seen) {
                assertEquals("$mode: $id stays at its place", before.tops.getValue(id), after.tops[id] ?: -999f, 2f)
                assertEquals("$mode: $id keeps its size", before.heights.getValue(id), after.heights[id] ?: -999f, 2f)
            }
        }
    }

    /** The new layouts are stored by name (Settings and per category) and read back, and the stored names are the documented ones. */
    @Test
    fun theNewLayoutsAreStoredAndReadBack() = kotlinx.coroutines.runBlocking<Unit> {
        val byPath = TestData.seedPaths(listOf(listOf("Alpha"), listOf("Beta")), perCategory = 2)
        val c = TestData.container
        val account = c.subscriptions.ensureLocalAccount()
        for (mode in listOf(ListMode.MASONRY, ListMode.BENTO)) {
            c.settings.setListMode(mode)
            assertEquals(mode, c.settings.current().listMode)
        }
        c.subscriptions.setCategoryListLayout(account, byPath.getValue("Alpha"), ListMode.MASONRY)
        c.subscriptions.setCategoryListLayout(account, byPath.getValue("Beta"), ListMode.BENTO)
        val stored = c.database.subscriptionDao().categoryPreferences(account).associate { it.categoryId to it.listModeOverride }
        assertEquals("MASONRY", stored[byPath.getValue("Alpha")])
        assertEquals("BENTO", stored[byPath.getValue("Beta")])
        c.settings.setListMode(ListMode.LIST)
    }

    /** The way back to the top and the reading place across layouts, through the list's own controls. */
    @Test
    fun backToTopAndTheReadingPlaceWorkInBothTileLayouts() {
        val pictures = h.makeImages()
        val rows = (0 until 300).map { h.variedRow(it, pictures) }
        for (mode in listOf(ListMode.MASONRY, ListMode.BENTO)) {
            h.listKey = "list-$mode"
            h.show(mode, rows, showImages = true)
            compose.onNodeWithTag("article_list").performScrollToIndex(if (mode == ListMode.BENTO) 60 else 150)
            compose.waitForIdle()
            Thread.sleep(500)
            assertTrue("$mode: far down", !h.displayed("a0"))
            h.topTick++
            compose.waitUntil(10_000) { h.displayed("a0") }
            compose.waitForIdle()
            Thread.sleep(500)
            val top = h.sample()
            assertTrue("$mode: the first article is at the top (${top.tops["a0"]})", (top.tops["a0"] ?: 999f) in 0f..(24f * m.density))
        }
        // The reading place: Bento far down, then Masonry and List show the same article near the top.
        h.listKey = "keep"
        h.show(ListMode.BENTO, rows, showImages = true)
        compose.onNodeWithTag("article_list").performScrollToIndex(30)
        compose.waitForIdle()
        Thread.sleep(500)
        val first = h.sample().tops.entries.filter { it.value >= -4f }.minByOrNull { it.value }!!.key
        val index = first.removePrefix("a").toInt()
        for (mode in listOf(ListMode.MASONRY, ListMode.LIST, ListMode.BENTO)) {
            h.show(mode, rows, showImages = true)
            compose.waitForIdle()
            Thread.sleep(500)
            val near = (index - 3..index + 3).any { h.displayed("a$it") }
            assertTrue("$mode: an article near $first is still in view", near)
        }
    }
}
