package io.github.yuubiz.folirel

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.ui.home.CardGridLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * MD-T01 to MD-T08 on the real article list (Magazine): a card is as tall as what it shows, a row as tall as its tallest card, a
 * "read more" label is not shown, pictures keep their place whether they load, fail or are missing, and nothing overlaps at 200 %
 * text. Emulator, debug build; the sizes are measured, not guessed.
 */
@RunWith(AndroidJUnit4::class)
class MdLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var m: MagazineHost
    private val shortTitle = "短い見出し"
    private val longTitle = "三行になる長い見出しの例です。まとめサイトの記事タイトルはこのくらい長いことがよくあります"
    private val normal = "通常の概要の文章です。記事の内容を一、二行で説明しています。ここまでが概要です。"

    @Before
    fun setUp() {
        TestData.reset()
        m = MagazineHost(compose)
    }

    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(1_200)
        compose.waitForIdle()
    }

    /**
     * One headline line (21 sp line height, a little more for the font's own spacing) + the gaps + the 48 dp source row, in dp, at text
     * size 1: the most a card with one headline line and no summary may take besides its picture. (0.5.1 reserved about 139 sp of text.)
     */
    private val oneLineCardTextDp = 8f + 24f + 2f + 48f + 4f

    /** MD-T01: two columns with pictures, short headlines, empty summaries: no reserved text area. */
    @Test
    fun shortHeadlinesWithoutSummaryMakeLowCards() {
        m.show((0 until 8).map { m.article(it, "$shortTitle $it", "") })
        settle()
        val cards = m.cards()
        assertTrue(cards.size >= 4)
        val picture = m.dp(m.partsOf("md0", "card_image").single().height)
        for ((id, r) in cards.filterValues { it.bottom < compose.onNodeWithTag("article_list").fetchSemanticsNode().size.height }) {
            val h = m.dp(r.height)
            assertTrue("$id is ${h}dp; picture ${picture}dp + one line of text at most ${oneLineCardTextDp}dp", h <= picture + oneLineCardTextDp)
        }
    }

    /** MD-T02: a summary that is only a "read more" label is not shown; ordinary text and text that contains the words are. */
    @Test
    fun aReadMoreLabelIsNotShownButRealTextIs() {
        val items = listOf(
            m.article(0, "$shortTitle 0", "続きを読む"),
            m.article(1, "$shortTitle 1", "続きを読む前に知っておくこと"),
            m.article(2, "$shortTitle 2", "Read more »"),
            m.article(3, "$shortTitle 3", "短い概要です。"),
        )
        m.show(items)
        settle()
        assertEquals(emptyList<String>(), m.summaryOf("md0"))
        assertEquals(listOf("続きを読む前に知っておくこと"), m.summaryOf("md1"))
        assertEquals(emptyList<String>(), m.summaryOf("md2"))
        assertEquals(listOf("短い概要です。"), m.summaryOf("md3"))
        // The data handed to the list is unchanged (display only).
        assertEquals("続きを読む", items[0].summaryText)
        // Without the label the card is as low as one without a summary.
        val cards = m.cards()
        assertEquals(m.dp(cards.getValue("md0").height), m.dp(cards.getValue("md1").height) - 0f, 30f)
    }

    /** MD-T03: a row is as tall as its tallest card (both cards end together), and the next row is not stretched by it. */
    @Test
    fun aRowIsAsTallAsItsTallestCardAndNoTaller() {
        m.show(
            listOf(
                m.article(0, longTitle, normal), m.article(1, shortTitle, ""),
                m.article(2, "$shortTitle 2", ""), m.article(3, "$shortTitle 3", ""),
            ),
        )
        settle()
        val c = m.cards()
        assertEquals("the cards of a row end together", c.getValue("md0").height, c.getValue("md1").height, 1f)
        assertTrue("the second row (short cards) is lower than the first: ${m.dp(c.getValue("md2").height)} vs ${m.dp(c.getValue("md0").height)}", c.getValue("md2").height < c.getValue("md0").height - 40f * m.density)
        // The short card's source row is at the bottom with the long card's: free space sits above it, not under it.
        val moreButtons = compose.onAllNodes(hasContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_more)), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.positionInRoot.y + it.size.height / 2f }.filter { it < c.getValue("md0").bottom && it > c.getValue("md0").top }
        assertTrue("both menus of the first row are at the same height: $moreButtons", moreButtons.size >= 2 && abs(moreButtons[0] - moreButtons[1]) <= 2f)
    }

    /** MD-T05: images off, no pictures, a picture on one side only: no blank area grows for text that is not there. */
    @Test
    fun withoutPicturesNoEmptyAreaIsReserved() {
        val items = (0 until 8).map { m.article(it, "$shortTitle $it", "") }
        m.show(items, showImages = false)
        settle()
        assertTrue("images off: no picture area", m.partsOf("md0", "card_image").isEmpty())
        for ((id, r) in m.cards().entries.take(4)) assertTrue("$id ${m.dp(r.height)}dp", m.dp(r.height) <= oneLineCardTextDp)
        // One side with a picture: the other side does not get an extra empty area beyond the row the picture makes.
        m.show(listOf(m.article(0, "$shortTitle 0", ""), m.article(1, "$shortTitle 1", "", image = null)), listKey = "half")
        settle()
        val c = m.cards()
        val picture = m.dp(m.partsOf("md0", "card_image").single().height)
        assertEquals(c.getValue("md0").height, c.getValue("md1").height, 1f)
        assertTrue("${m.dp(c.getValue("md1").height)}", m.dp(c.getValue("md1").height) <= picture + oneLineCardTextDp)
        // A card without a picture beside one with a picture uses the free height for more summary rather than a blank.
        m.show(listOf(m.article(0, "$shortTitle 0", ""), m.article(1, "$shortTitle 1", normal.repeat(3), image = null)), listKey = "fill")
        settle()
        val text = m.partsOf("md1", "card_summary").single()
        val card = m.cards().getValue("md1")
        assertTrue("the summary uses the free height: ${m.dp(text.height)}dp", m.dp(text.height) > 2 * 18f + 1f)
        assertTrue("and fits the card", text.bottom <= card.bottom - 40f * m.density)
    }

    /** MD-T06: a picture that fails, one that is missing, and pictures still loading: the card heights and the reading place do not change. */
    @Test
    fun picturesThatLoadFailOrAreMissingDoNotMoveAnything() {
        val items = (0 until 40).map { n ->
            val image = when (n % 3) {
                0 -> m.pictures[n % m.pictures.size]
                1 -> "file:///data/local/tmp/does-not-exist-$n.png" // a picture whose download fails
                else -> m.pictures[(n + 1) % m.pictures.size]
            }
            m.article(n, "$shortTitle $n", if (n % 2 == 0) "" else normal, image)
        }
        m.show(items)
        val early = m.cards() // before the pictures have arrived
        settle()
        val late = m.cards()
        for (id in early.keys.intersect(late.keys)) assertEquals("$id kept its height while its picture loaded or failed", early.getValue(id).height, late.getValue(id).height, 0.5f)
        // A failed picture keeps its area: the card is as tall as its neighbour with a picture that loaded.
        assertEquals(late.getValue("md0").height, late.getValue("md1").height, 1f)
        // The reading place: scrolled down, then the pictures arrive; the first article on screen stays where it was.
        compose.onNodeWithTag("article_list").performScrollToIndex(20)
        compose.waitForIdle()
        val before = m.cards().minByOrNull { it.value.top }!!
        Thread.sleep(1_500)
        compose.waitForIdle()
        val after = m.cards().getValue(before.key)
        assertEquals("the first article did not move while pictures loaded", before.value.top, after.top, 1f)
    }

    /** MD-T07: two columns (mixed) on a phone, three columns at 560 dp: tile pictures are about 2.3:1, a wide card's picture is small. */
    @Test
    fun picturesAreFlatterAndTheWideCardPictureIsSmall() {
        val items = (0 until 30).map { m.article(it, "$shortTitle $it", if (it % 2 == 0) "" else normal) }
        m.show(items, style = CardLayoutStyle.MIXED, columns = 2)
        settle()
        val cards = m.cards()
        val tile = cards.entries.first { m.dp(it.value.width) < 300f }
        val tileImage = m.partsOf(tile.key, "card_image").single()
        assertEquals("tile picture is 2.3:1", tileImage.width / CardGridLayout.TILE_IMAGE_RATIO, tileImage.height, 3f * m.density)
        val wide = cards.entries.firstOrNull { m.dp(it.value.width) > 300f }
        if (wide != null) {
            val thumb = m.partsOf(wide.key, "card_image").single()
            assertTrue("wide card picture is ${m.dp(thumb.width)}dp wide", m.dp(thumb.width) in 88f..140f)
            assertTrue("and no taller than its card", thumb.height <= wide.value.height + 1f)
        }
        m.show(items, style = CardLayoutStyle.MIXED, columns = 3, widthDp = 560, listKey = "three")
        settle()
        val image = m.partsOf(m.cards().keys.first(), "card_image").firstOrNull()
        assertTrue("three columns show pictures", image != null)
        assertTrue("the shared tile picture height is at most ${CardGridLayout.MAX_TILE_IMAGE_HEIGHT_DP}dp", m.dp(image!!.height) <= CardGridLayout.MAX_TILE_IMAGE_HEIGHT_DP + 1f)
    }

    /** MD-T08: 200 % text with long Japanese text: the column correction applies, the text ends above the source row, the buttons stay 48 dp. */
    @Test
    fun largeTextDoesNotOverlapOrLoseButtons() {
        val items = (0 until 12).map { m.article(it, longTitle, normal) }
        for (scale in listOf(1.3f, 2f)) {
            m.show(items, columns = 2, fontScale = scale, listKey = "font$scale")
            settle()
            // The merged tree: the button node itself (the icon inside it carries the description).
            val more = compose.onAllNodes(hasContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_more))).fetchSemanticsNodes()
            for ((id, card) in m.cards().filterValues { it.bottom < compose.onNodeWithTag("article_list").fetchSemanticsNode().size.height }) {
                for (summary in m.partsOf(id, "card_summary")) {
                    assertTrue("x$scale $id: the summary ends above the source row (${summary.bottom} vs ${card.bottom})", summary.bottom <= card.bottom - 44f * m.density)
                }
                val button = more.firstOrNull { it.positionInRoot.y in card.top..card.bottom && it.positionInRoot.x in card.left..card.right }
                assertTrue("x$scale $id: the menu button is on the card", button != null)
                val touch = button!!.touchBoundsInRoot
                assertTrue("x$scale $id: the menu is at least 48dp (${m.dp(touch.height)})", touch.height >= 48f * m.density - 1f)
            }
        }
    }
}
