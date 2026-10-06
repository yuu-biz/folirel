package io.github.yuubiz.folirel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 * The real thing for the home screen flows: feeds served by a local server, fetched and parsed by the real LocalBackend, saved by
 * the real FeedStore into the app Room database, driven by the real RefreshCoordinator, seen through the real HomeViewModel and
 * shown by the real HomeScreen (pager, tabs, list, banner, notices). Only the network policy differs from the app: loopback is
 * allowed here, which production never does.
 */
abstract class HomeFlowTestBase {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    protected val server = MockWebServer()
    protected val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    protected val all = ArticleScope.All

    /** What the feeds on the server hold: id → title, newest last; set by the test before a refresh. */
    protected val oldTitles = (0 until 90).map { "旧記事 $it" }
    protected var oneNew: List<String> = emptyList()
    protected var twoNew: List<String> = emptyList()
    protected var badStatus = 200
    protected var gateOne: CountDownLatch? = null
    protected var gateTwo: CountDownLatch? = null
    protected val requestsToTwo = AtomicInteger()
    protected val allowHttp = java.util.concurrent.atomic.AtomicBoolean(true)
    protected val network = kotlinx.coroutines.flow.MutableStateFlow(NetworkState(connected = true, metered = false))
    protected var subscriptionsOpened = 0
    protected val feedDetailsOpened = mutableListOf<String>()

    protected lateinit var coordinator: RefreshCoordinator
    protected lateinit var vm: HomeViewModel
    protected lateinit var feedOne: String

    protected val rfc822 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }

    protected fun rss(title: String, items: List<Pair<String, Long>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>$title</title><link>https://example.com/</link><description>d</description>")
        items.forEach { (t, time) ->
            append("<item><title>$t</title><link>https://example.com/${t.hashCode()}</link><guid isPermaLink=\"false\">$title-$t</guid><pubDate>${rfc822.format(time)}</pubDate><description>$t の概要</description></item>")
        }
        append("</channel></rss>")
    }

    protected val now = System.currentTimeMillis()

    /** Old articles are a week old; new ones are minutes old, so they are the newest of the list. */
    protected fun items(old: List<String>, fresh: List<String>): List<Pair<String, Long>> =
        old.mapIndexed { i, t -> t to now - 7 * 86_400_000L - i * 60_000L } + fresh.mapIndexed { i, t -> t to now - 3_600_000L + i * 1_000L }

    protected fun respond(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/rss+xml").body(body).build()

    @Before
    fun setUp() {
        TestData.reset()
        runBlocking { TestData.container.settings.setListMode(ListMode.MAGAZINE) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/one.xml" -> {
                    gateOne?.await(20, TimeUnit.SECONDS)
                    respond(rss("one", items(oldTitles, oneNew)))
                }
                "/two.xml" -> {
                    requestsToTwo.incrementAndGet()
                    gateTwo?.await(20, TimeUnit.SECONDS)
                    respond(rss("two", items(oldTitles.take(5).map { "二 $it" }, twoNew)))
                }
                "/bad.xml" -> MockResponse.Builder().code(badStatus).body("down").build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val c = TestData.container
        useBudget(LocalBackend.DEFAULT_BUDGET_MS)
        val account = runBlocking { c.subscriptions.ensureLocalAccount() }
        fun url(path: String) = server.url(path).toString()
        val opml = """<opml version="2.0"><body><outline text="one" xmlUrl="${url("/one.xml")}"/><outline text="two" xmlUrl="${url("/two.xml")}"/></body></opml>"""
        runBlocking { c.subscriptions.commitImport(account, OpmlParser.parse(opml.toByteArray())) }
        feedOne = runBlocking { c.database.subscriptionDao().feeds(account) }.first { it.subscriptionUrl.endsWith("/one.xml") }.id
    }

    /** The real backend and coordinator with the given wall-clock budget for one update (a short one leaves a held feed unfinished). */
    protected fun useBudget(budgetMillis: Long) {
        val c = TestData.container
        val loopbackAllowedForTests = AddressPolicy { it.isLoopbackAddress || AddressPolicy.PUBLIC_ONLY.isAllowed(it) }
        val client = HttpClients.feedClient(NetworkGuard(loopbackAllowedForTests, { true }), "FolirelTest/1")
        val backend = LocalBackend(
            store = FeedStore(c.database, c.clock, IdGenerator.Random),
            fetcher = FeedFetcher(client, c.clock),
            clock = c.clock,
            network = NetworkStatus { NetworkState(connected = true, metered = false) },
            allowHttp = { allowHttp.get() },
            budgetMillis = budgetMillis,
        )
        coordinator = RefreshCoordinator(
            backend = backend,
            accountId = { c.subscriptions.ensureLocalAccount() },
            automaticAllowed = { true },
            onAttempt = {},
            scope = bg,
            clock = c.clock,
        )
    }

    @After
    fun tearDown() {
        gateOne?.countDown()
        gateTwo?.countDown()
        server.close()
        bg.cancel()
    }

    /** The real home screen on the real view model, in a ComposeView of its own (MainActivity's own content is replaced). */
    protected fun host() = host(widthDp = null, fontScale = 1.0f)

    /** [host] with a window width (dp; null = the screen) and a text size (the system font scale is a property of the density). */
    protected fun host(widthDp: Int?, fontScale: Float) {
        val c = TestData.container
        compose.runOnUiThread {
            if (!::vm.isInitialized) {
                vm = HomeViewModel(c.articles, c.subscriptions, c.settings, coordinator, network, SavedStateHandle(), c.clock)
            }
            val view = ComposeView(compose.activity)
            compose.activity.setContentView(view)
            view.setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    FolirelTheme(ThemeMode.SYSTEM, 1.0f) {
                        Box(if (widthDp != null) Modifier.width(widthDp.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                            HomeScreen(
                                vm = vm,
                                imageLoader = c.imageLoader,
                                browser = c.browser,
                                nav = HomeNavigation(importOpml = {}, addFeed = {}, subscriptions = { subscriptionsOpened++ }, settings = {}, feedDetail = { feedDetailsOpened += it }),
                                httpsState = c.httpsUpgrade.state,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** [androidx.compose.ui.test.junit4.ComposeTestRule.waitUntil] that says which line of the test was waiting. */
    protected fun until(timeoutMillis: Long, condition: () -> Boolean) {
        val line = Throwable().stackTrace[1].lineNumber
        try {
            compose.waitUntil(timeoutMillis, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("timed out after $timeoutMillis ms waiting at HomeNoticeFlowTest.kt:$line", e)
        }
    }

    protected fun refresh(scope: RefreshScope = RefreshScope.All, reason: RefreshReason = RefreshReason.MANUAL) {
        runBlocking { coordinator.refresh(scope, reason) }
        compose.waitForIdle()
    }

    protected fun inAllPage(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_${all.encode()}")))

    protected fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_${all.encode()}"))).fetchSemanticsNodes().isNotEmpty()

    // Unmerged: a mark inside a button (the warning badge on the menu button) is a node of its own only there.
    protected fun existsAnywhere(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    protected fun showsTitle(title: String) =
        compose.onAllNodes(hasText(title, substring = true) and hasAnyAncestor(hasTestTag("page_${all.encode()}"))).fetchSemanticsNodes().isNotEmpty()

    protected fun bannerText(): String =
        inAllPage("new_articles_banner").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    protected fun readCount(): Int = runBlocking {
        val c = TestData.container
        val account = c.subscriptions.ensureLocalAccount()
        c.database.subscriptionDao().feeds(account).sumOf { f -> c.database.articleDao().articlesOfFeed(f.id).count { a -> c.database.articleDao().state(a.id)?.isRead == true } }
    }

    protected fun firstFetchThenShow() {
        refresh() // the first fetch of both feeds: their articles are the initial content, not new arrivals
        host()
        until(15_000) { exists("article_list") }
        assertTrue("the first fetch announces nothing", !exists("new_articles_banner"))
    }

    protected fun scrolledDown() {
        inAllPage("article_list").performScrollToIndex(40)
        compose.waitForIdle()
    }


    protected fun addBadFeed() {
        val c = TestData.container
        val account = runBlocking { c.subscriptions.ensureLocalAccount() }
        val opml = """<opml version="2.0"><body><outline text="bad" xmlUrl="${server.url("/bad.xml")}"/></body></opml>"""
        runBlocking { c.subscriptions.commitImport(account, OpmlParser.parse(opml.toByteArray())) }
    }

    protected fun firstArticleTop(): Float {
        val list = compose.onNodeWithTag("article_list").fetchSemanticsNode().positionInRoot.y
        val first = compose.onAllNodes(hasTestTag("article_list").not()).fetchSemanticsNodes()
            .filter { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("article_") == true }
            .minOf { it.positionInRoot.y }
        return first - list
    }

}
