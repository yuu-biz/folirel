package io.github.yuubiz.folirel

import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.backend.local.FeedFetcher
import io.github.yuubiz.folirel.backend.local.LocalBackend
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.data.repository.FeedStore
import io.github.yuubiz.folirel.data.repository.IdGenerator
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.network.AddressPolicy
import io.github.yuubiz.folirel.network.HttpClients
import io.github.yuubiz.folirel.network.NetworkGuard
import io.github.yuubiz.folirel.network.NetworkState
import io.github.yuubiz.folirel.network.NetworkStatus
import io.github.yuubiz.folirel.sync.RefreshCoordinator
import io.github.yuubiz.folirel.ui.home.HomeNavigation
import io.github.yuubiz.folirel.ui.home.HomeScreen
import io.github.yuubiz.folirel.ui.home.HomeViewModel
import io.github.yuubiz.folirel.ui.theme.FolirelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * UXR-07 to UXR-11 and UXR-21 through the real screen (see [HomeFlowTestBase]): what a finished update leaves on the home
 * screen for a few seconds, what stays, and that none of it moves the articles.
 */
@RunWith(AndroidJUnit4::class)
class HomeNoticeFlowTest : HomeFlowTestBase() {
    /**
     * UXR-07/08/21: a partly failed update leaves a notice that goes after its time (also across a recreate of the screen,
     * which neither shows it again once gone nor lets it last longer), never moves the articles when it comes and goes, and
     * leaves the warning on the subscriptions entry, which stays.
     */
    @Test
    fun aFailedUpdateLeavesAnoticeThatEndsOnItsOwnAndAWarningThatStays() {
        firstFetchThenShow()
        addBadFeed()
        badStatus = 500
        val topBefore = firstArticleTop()
        refresh()
        val finishedAt = coordinator.state.value.requests.last().finishedAt!!
        until(5_000) { existsAnywhere("refresh_notice") }
        assertEquals("the notice does not move the articles", topBefore, firstArticleTop(), 0.5f)
        // Rotation / recreation: the same notice, with the time it had left; not a new one.
        Thread.sleep(1_500)
        host()
        until(5_000) { !existsAnywhere("refresh_notice") }
        val shownFor = System.currentTimeMillis() - finishedAt
        assertTrue("the notice lasted $shownFor ms in all: about its 5 s, not 5 s after the recreate", shownFor in 4_500..6_800)
        assertEquals("nor does its going move the articles", topBefore, firstArticleTop(), 0.5f)
        // What stays: the small warning on the subscriptions entry, from the stored state of the failing feed.
        until(5_000) { existsAnywhere("attention_badge") }
        // Back to this screen: the finished request does not make a second notice.
        host()
        Thread.sleep(500)
        assertTrue("no second notice for the same request", !existsAnywhere("refresh_notice"))
    }

    /** The cross closes the notice at once; the retry button asks for the unfinished feeds again and closes it. */
    @Test
    fun theNoticeCanBeClosedAndRetried() {
        firstFetchThenShow()
        addBadFeed()
        badStatus = 500
        refresh()
        until(5_000) { existsAnywhere("refresh_notice") }
        compose.onNodeWithTag("refresh_notice_dismiss").performClick()
        until(3_000) { !existsAnywhere("refresh_notice") }
        assertTrue("the warning stays after the notice is closed", existsAnywhere("attention_badge"))
        // A second failing update, and the notice's retry button asks for the unfinished feeds again.
        refresh()
        until(5_000) { existsAnywhere("refresh_notice") }
        assertTrue(existsAnywhere("retry_unfinished"))
        compose.onNodeWithTag("retry_unfinished", useUnmergedTree = true).performClick()
        until(15_000) { coordinator.state.value.requests.any { it.scope == RefreshScope.Pending } }
    }

    /** UXR-09: a new update that finishes with a problem while an older notice is still showing replaces it, and the old timer does not remove it. */
    @Test
    fun aNewerNoticeIsNotRemovedByTheOlderOnesTimer() {
        firstFetchThenShow()
        addBadFeed()
        badStatus = 500
        refresh()
        until(5_000) { existsAnywhere("refresh_notice") }
        Thread.sleep(3_500) // most of the first notice's time has gone
        refresh() // a second update, failing again: a second request, a second notice
        val second = coordinator.state.value.requests.last()
        Thread.sleep(2_500) // past the end of the first notice's own time
        assertTrue("the first timer ran out but the second notice is still up", existsAnywhere("refresh_notice"))
        until(8_000) { !existsAnywhere("refresh_notice") }
        val shownFor = System.currentTimeMillis() - second.finishedAt!!
        assertTrue("the second notice lasted its own 5 s ($shownFor ms)", shownFor in 4_500..7_000)
    }

    /** UXR-11: no connection is a small mark, not a band; and the way to the reasons stays when the notice is gone (HTTP held feeds). */
    @Test
    fun noConnectionIsASmallMarkAndHeldFeedsKeepAWayToTheirReason() {
        firstFetchThenShow()
        assertTrue("online: no mark", !existsAnywhere("offline_indicator"))
        val topOnline = firstArticleTop()
        network.value = NetworkState(connected = false, metered = false)
        until(5_000) { existsAnywhere("offline_indicator") }
        assertEquals("the mark takes no room from the articles", topOnline, firstArticleTop(), 0.5f)
        network.value = NetworkState(connected = true, metered = false)
        until(5_000) { !existsAnywhere("offline_indicator") }
        // HTTP held: the feed address is http:// and the reader has not allowed it.
        allowHttp.set(false)
        refresh()
        until(5_000) { existsAnywhere("refresh_notice") }
        compose.onNodeWithTag("refresh_notice_dismiss").performClick()
        until(3_000) { !existsAnywhere("refresh_notice") }
        assertTrue("the held feeds are still marked on the subscriptions entry", existsAnywhere("attention_badge"))
        // The mark leads to subscription management, where the reason and what to do are.
        compose.onNodeWithTag("home_more").performClick()
        assertTrue("the count is next to the entry", existsAnywhere("attention_count"))
        compose.onNodeWithTag("menu_subscriptions").performClick()
        assertEquals(1, subscriptionsOpened)
    }

    /** The notice's own "details" button opens subscription management too. */
    @Test
    fun theNoticeLeadsToTheDetails() {
        firstFetchThenShow()
        addBadFeed()
        badStatus = 500
        refresh()
        until(5_000) { existsAnywhere("refresh_notice") }
        compose.onNodeWithTag("refresh_notice_details", useUnmergedTree = true).performClick()
        until(3_000) { !existsAnywhere("refresh_notice") }
        assertEquals(1, subscriptionsOpened)
    }

    /**
     * UXR-21: a failed update that also brings new articles shows the notice and the banner together; going to the top with the
     * banner and the notice ending afterwards neither move the articles by themselves nor get in each other's way.
     */
    @Test
    fun theNoticeAndTheBannerTogetherDoNotMoveEachOtherOrTheArticles() {
        firstFetchThenShow()
        addBadFeed()
        badStatus = 500
        scrolledDown()
        oneNew = (0 until 30).map { "新着 $it" }
        refresh()
        until(10_000) { exists("new_articles_banner") }
        assertTrue("both are up", existsAnywhere("refresh_notice"))
        inAllPage("new_articles_banner").performClick()
        until(10_000) { showsTitle("新着 29") }
        Thread.sleep(800)
        compose.waitForIdle()
        val atTop = firstArticleTop()
        // The notice is still there or just gone; either way the list stays where the banner put it.
        until(10_000) { !existsAnywhere("refresh_notice") }
        compose.waitForIdle()
        assertEquals("the list did not move when the notice ended", atTop, firstArticleTop(), 0.5f)
        assertTrue("and the first article is at the top", firstArticleTop() in 0f..120f)
        assertEquals(0, readCount())
    }

    /**
     * A feed the update did not get to (the time budget ran out while it waited) is not a failure, but it was not fetched and
     * can be asked for again. The notice offers that for a few seconds; after it is gone the subscriptions entry still shows
     * that something is unfinished (the old permanent band did, and its retry must not be lost with it).
     */
    @Test
    fun aFeedTheUpdateDidNotReachKeepsAWayBackAfterTheNoticeIsGone() {
        useBudget(900)
        firstFetchThenShow()
        twoNew = listOf("遅い記事")
        gateTwo = CountDownLatch(1) // feed two does not answer within the budget
        refresh()
        until(5_000) { existsAnywhere("refresh_notice") }
        assertTrue("the notice offers to ask again", existsAnywhere("retry_unfinished"))
        assertTrue("it is not a failure: the badge waits for the update to be over", coordinator.state.value.requests.none { it.running })
        until(10_000) { !existsAnywhere("refresh_notice") }
        until(5_000) { existsAnywhere("attention_badge") }
    }
}
