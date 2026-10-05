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
 * UXR-12, UXR-14, UXR-17, UXR-19 through the real screen (see [HomeFlowTestBase]): a later fetch brings new articles, the banner
 * announces them and one tap answers it; a report that lists answered articles again does not bring them back.
 */
@RunWith(AndroidJUnit4::class)
class HomeBannerFlowTest : HomeFlowTestBase() {
    /** UXR-19 / UXR-12: from the fetch to the notice and back, in the real screen. One tap, one answer. */
    @Test
    fun anUpdateBringsABannerAndOneTapMovesToTheTopAndEndsTheNotice() {
        firstFetchThenShow()
        scrolledDown()
        oneNew = (0 until 40).map { "新着 $it" }
        refresh()
        until(10_000) { exists("new_articles_banner") }
        assertTrue("it counts the 40 new articles of the shown list: ${bannerText()}", bannerText().contains("40"))
        assertEquals("the notice is not an article state", 0, readCount())
        inAllPage("new_articles_banner").performClick()
        until(10_000) { !exists("new_articles_banner") }
        until(10_000) { showsTitle("新着 39") }
        Thread.sleep(700)
        compose.waitForIdle()
        assertTrue("one tap and the newest article is on screen", showsTitle("新着 39"))
        assertTrue("no banner comes back", !exists("new_articles_banner"))
        assertEquals("the view model has nothing left to announce", emptySet<String>(), vm.newArticleIds.value)
        assertEquals("opening, moving and answering marked nothing read", 0, readCount())
        // The same content again: nothing new, nothing announced.
        refresh()
        Thread.sleep(300)
        assertTrue("an update without new articles announces nothing", !exists("new_articles_banner"))
    }

    /**
     * UXR-17: two requests share the fetch of feed one; the smaller one finishes first and its articles are announced and
     * answered; the larger one finishes later and lists the same articles again. They do not come back, only what is really new.
     */
    @Test
    fun aLateReportThatListsTheAnsweredArticlesAgainDoesNotBringThemBack() {
        firstFetchThenShow()
        oneNew = (0 until 4).map { "共有 $it" }
        twoNew = (0 until 3).map { "遅れて $it" }
        gateOne = CountDownLatch(1)
        gateTwo = CountDownLatch(1)
        // The wide request first (it holds both feeds), then the narrow one that shares the fetch of feed one.
        bg.launch { coordinator.refresh(RefreshScope.All, RefreshReason.MANUAL) }
        until(10_000) { coordinator.state.value.running }
        bg.launch { coordinator.refresh(RefreshScope.Feeds(listOf(feedOne)), RefreshReason.MANUAL) }
        until(10_000) { coordinator.state.value.requests.count { it.running } == 2 }
        gateOne!!.countDown() // feed one answers: the narrow request ends, the wide one waits for feed two
        until(15_000) { exists("new_articles_banner") }
        assertTrue("the four shared articles: ${bannerText()}", bannerText().contains("4") && !bannerText().contains("7"))
        inAllPage("new_articles_banner").performClick()
        until(10_000) { !exists("new_articles_banner") }
        gateTwo!!.countDown() // feed two answers: the wide request ends and lists the four again, plus its own three
        until(15_000) { coordinator.state.value.requests.none { it.running } }
        val wide = coordinator.state.value.requests.last { it.scope == RefreshScope.All }
        assertTrue("the premise: the wide report lists the shared articles again (${wide.report?.newArticleIds?.size})", (wide.report?.newArticleIds?.size ?: 0) >= 7)
        until(10_000) { exists("new_articles_banner") }
        assertTrue("only the three that are really new: ${bannerText()}", bannerText().contains("3") && !bannerText().contains("7"))
    }

    /** UXR-14 through the screen: the cross closes the notice, nothing moves and nothing changes. */
    @Test
    fun theCrossClosesTheBannerWithoutMovingTheList() {
        firstFetchThenShow()
        scrolledDown()
        oneNew = (0 until 10).map { "新着 $it" }
        refresh()
        until(10_000) { exists("new_articles_banner") }
        // The articles that arrived are above the reader (the list keeps the article being read); the position is judged from here.
        val before = compose.onNodeWithTag("article_list").fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()
        inAllPage("new_articles_dismiss").performClick()
        until(5_000) { !exists("new_articles_banner") }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("article_list").fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()
        assertEquals("the list did not move", before, after)
        assertEquals(emptySet<String>(), vm.newArticleIds.value)
        assertEquals(0, readCount())
    }
}
