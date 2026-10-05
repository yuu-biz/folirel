package io.github.yuubiz.folirel

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * H03 on a device, through the real article list (the composable the home screen uses) with the real LazyGrid /
 * LazyColumn: the new-article banner is a notice that one tap answers. The tap moves the list (to its top when the
 * newest are first, to the first new article when the oldest are first) and the notice is gone; the cross closes it
 * without moving; going back to the top answers it too. The list is hosted with a recording [ListHost]; nothing here
 * can mark an article read (the actions count every call).
 */
@RunWith(AndroidJUnit4::class)
class NewArticleBannerTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var h: ListHarness

    @Before
    fun quietApp() {
        // The app behind the replaced content must not start any network work.
        TestData.reset()
        h = ListHarness(compose)
    }

    private fun waitForBanner(shown: Boolean) = compose.waitUntil(5_000) { h.bannerShown() == shown }

    private fun isInView(id: String) = runCatching { compose.onNodeWithTag("article_$id").assertIsDisplayed() }.isSuccess

    private fun bannerText(): String =
        compose.onNodeWithTag("new_articles_banner").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private val ids120 = (150 until 270).map { "a$it" }

    /** 400 articles, the reader is part way down, 120 new ones sit far below the screen. */
    private fun showFarDown(mode: ListMode) {
        h.show(mode, (0 until 400).map { h.variedRow(it) })
        compose.onNodeWithTag("article_list").performScrollToIndex(100)
        compose.waitForIdle()
        h.newIds = ids120.toSet()
        compose.waitForIdle()
        waitForBanner(true)
    }

    /** UXR-12: one tap on the banner of a newest-first list goes to the top and ends the notice for all 120. */
    private fun oneTapGoesToTopAndEndsTheNotice(mode: ListMode) {
        showFarDown(mode)
        assertTrue("the banner says how many and where: ${bannerText()}", bannerText().contains("120"))
        assertTrue("it is to the top, not to the first new article: ${bannerText()}", !isInView("a0"))
        compose.onNodeWithTag("new_articles_banner").performClick()
        waitForBanner(false)
        compose.waitUntil(5_000) { h.displayed("a0") }
        compose.waitForIdle()
        assertTrue("$mode: the first article is on screen after one tap", isInView("a0"))
        assertEquals("$mode: all 120 were answered at once, not the ones that came on screen", ids120.toSet(), h.host.acknowledged.toSet())
        assertEquals(0, h.readCalls)
        // It stays gone: nothing brings a banner back for the 120 while the list rests.
        Thread.sleep(800)
        compose.waitForIdle()
        assertTrue("$mode: no banner returns", !h.bannerShown())
    }

    @Test fun oneTapInTheMagazineLayoutEndsTheNotice() = oneTapGoesToTopAndEndsTheNotice(ListMode.MAGAZINE)
    @Test fun oneTapInTheListLayoutEndsTheNotice() = oneTapGoesToTopAndEndsTheNotice(ListMode.LIST)
    @Test fun oneTapInTheCompactListLayoutEndsTheNotice() = oneTapGoesToTopAndEndsTheNotice(ListMode.COMPACT_LIST)

    /** UXR-13: some of the new ones are already on screen; the tap still answers all of them at once, not one screenful at a time. */
    @Test
    fun newArticlesAlreadyOnScreenDoNotTurnTheBannerIntoARemainder() {
        h.show(ListMode.COMPACT_LIST, (0 until 400).map { h.variedRow(it) })
        h.newIds = setOf("a1", "a2") + ids120
        compose.waitForIdle()
        waitForBanner(true)
        // Showing a1 and a2 did not count them down: the notice is still all 122.
        assertTrue(bannerText(), bannerText().contains("122"))
        assertTrue("nothing was answered by merely being on screen", h.host.acknowledged.isEmpty())
        compose.onNodeWithTag("new_articles_banner").performClick()
        waitForBanner(false)
        assertEquals(122, h.host.acknowledged.size)
    }

    /** UXR-14: the cross only closes the notice. */
    @Test
    fun theCrossClosesTheNoticeWithoutMovingOrChangingAnything() {
        showFarDown(ListMode.COMPACT_LIST)
        val before = h.sample()
        compose.onNodeWithTag("new_articles_dismiss").performClick()
        waitForBanner(false)
        compose.waitForIdle()
        Thread.sleep(500)
        val after = h.sample()
        assertEquals("the list did not move", before.tops.keys.first(), after.tops.keys.first())
        assertEquals(before.tops.values.first(), after.tops.values.first(), 0.5f)
        assertEquals(ids120.toSet(), h.host.acknowledged.toSet())
        assertEquals("no article changed", 0, h.readCalls)
        assertEquals("no article was opened", 0, h.openCalls)
        assertEquals("the articles are all still there", 400, h.items.size)
    }

    /** UXR-15: oldest first, the banner goes to the first new article of that order (once) and the order is kept. */
    @Test
    fun oldestFirstTheBannerGoesToTheFirstNewArticleOfThatOrder() {
        h.newestFirst = false
        // Oldest first: a399 ... a0; the new (newest) ones are at the end.
        h.show(ListMode.COMPACT_LIST, (0 until 400).map { h.variedRow(it) }.reversed())
        h.newIds = setOf("a5", "a4", "a3", "a2", "a1", "a0")
        compose.waitForIdle()
        waitForBanner(true)
        assertTrue("it goes to the new article's place, not to the top: ${bannerText()}", bannerText().contains("6"))
        compose.onNodeWithTag("new_articles_banner").performClick()
        compose.waitUntil(5_000) { h.displayed("a5") }
        waitForBanner(false)
        compose.waitForIdle()
        assertTrue("the first new article of this order is in view", isInView("a5"))
        assertEquals(6, h.host.acknowledged.size)
        assertEquals("the order was not changed", "a399", h.items.first().id)
    }

    /** UXR-16: only the new articles this list shows are answered; the others stay announced for their own lists. */
    @Test
    fun onlyTheNewArticlesOfThisListAreAnswered() {
        h.show(ListMode.COMPACT_LIST, (0 until 100).map { h.variedRow(it) })
        h.newIds = setOf("a50", "a60", "elsewhere-1", "elsewhere-2")
        compose.waitForIdle()
        waitForBanner(true)
        assertTrue("two of this list: ${bannerText()}", bannerText().contains("2"))
        compose.onNodeWithTag("new_articles_banner").performClick()
        waitForBanner(false)
        assertEquals(setOf("a50", "a60"), h.host.acknowledged.toSet())
        assertEquals("the other lists' articles are still announced", setOf("elsewhere-1", "elsewhere-2"), h.newIds)
    }

    /** UXR-17: three articles that really arrive after the tap are announced as new; the answered ones do not come back. */
    @Test
    fun articlesThatArriveAfterTheTapAreAnnouncedAsNew() {
        showFarDown(ListMode.COMPACT_LIST)
        compose.onNodeWithTag("new_articles_banner").performClick()
        waitForBanner(false)
        val late = (-3..-1).map { h.variedRow(it) }
        h.items = late + h.items
        h.newIds = h.newIds + late.map { it.id }
        compose.waitForIdle()
        waitForBanner(true)
        assertTrue("only the three: ${bannerText()}", bannerText().contains("3") && !bannerText().contains("120"))
    }

    /** UXR-18: new articles with old publication dates (not at the head) are still counted, and the banner says it goes to the top. */
    @Test
    fun newArticlesThatAreNotAtTheHeadAreStillCountedAndSayTheyGoToTheTop() {
        h.show(ListMode.LIST, (0 until 300).map { h.variedRow(it) })
        h.newIds = (290 until 300).map { "a$it" }.toSet()
        compose.waitForIdle()
        waitForBanner(true)
        assertTrue(bannerText(), bannerText().contains("10"))
        compose.onNodeWithTag("new_articles_banner").performClick()
        waitForBanner(false)
        assertEquals("nothing was dropped", 300, h.items.size)
        assertEquals(10, h.host.acknowledged.size)
    }

    /** Going back to the top with the arrow answers the notice of this list, too. */
    @Test
    fun theBackToTopArrowAnswersTheNoticeToo() {
        showFarDown(ListMode.COMPACT_LIST)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("scroll_to_top")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("scroll_to_top").performClick()
        waitForBanner(false)
        assertEquals(ids120.toSet(), h.host.acknowledged.toSet())
        compose.waitUntil(5_000) { h.displayed("a0") }
    }

    /** Tapping the selected tab again (the tick) answers it as well. */
    @Test
    fun theSecondTapOnTheTabAnswersTheNoticeToo() {
        showFarDown(ListMode.COMPACT_LIST)
        h.topTick++
        waitForBanner(false)
        assertEquals(ids120.toSet(), h.host.acknowledged.toSet())
        compose.waitUntil(5_000) { h.displayed("a0") }
    }

    /** Articles arriving at the head while the reader is further down never move the list; the notice only announces them. */
    private fun headArrival(mode: ListMode) {
        h.show(mode, (0 until 80).map { h.variedRow(it) })
        compose.onNodeWithTag("article_list").performScrollToIndex(40)
        compose.waitForIdle()
        // The article the reader has at the top of the screen: the first one that is really in view (a composed neighbour is not).
        val reading = (30..50).map { "a$it" }.first { id -> isInView(id) }
        val arrived = (-3..-1).map { h.variedRow(it) }
        h.items = arrived + h.items
        h.newIds = arrived.map { it.id }.toSet()
        compose.waitForIdle()
        assertTrue("$mode: still reading $reading", isInView(reading))
        waitForBanner(true)
        assertTrue("$mode: the new ones are off screen and announced, not answered", h.host.acknowledged.isEmpty())
        assertEquals(0, h.readCalls)
    }

    @Test fun articlesArrivingAtTheHeadKeepTheReadingPlaceInTheMagazineLayout() = headArrival(ListMode.MAGAZINE)
    @Test fun articlesArrivingAtTheHeadKeepTheReadingPlaceInTheCompactListLayout() = headArrival(ListMode.COMPACT_LIST)

    /** The notice and its label are in the system language of the device: both texts exist (a missing translation fails lint, this reads them). */
    @Test
    fun bannerTextsExist() {
        val resources = compose.activity.resources
        assertTrue(resources.getQuantityString(io.github.yuubiz.folirel.R.plurals.new_articles_banner_top, 120, 120).contains("120"))
        assertTrue(resources.getQuantityString(io.github.yuubiz.folirel.R.plurals.new_articles_banner_position, 120, 120).contains("120"))
        assertTrue(resources.getString(io.github.yuubiz.folirel.R.string.new_articles_dismiss).isNotBlank())
    }
}
