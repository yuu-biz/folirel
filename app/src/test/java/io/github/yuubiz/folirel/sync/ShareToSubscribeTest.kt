package io.github.yuubiz.folirel.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.app.DocumentIo
import io.github.yuubiz.folirel.backend.local.HttpsUpgradeChecker
import io.github.yuubiz.folirel.backend.local.SmallResourceFetcher
import io.github.yuubiz.folirel.backend.local.discovery.FeedDiscovery
import io.github.yuubiz.folirel.backend.local.discovery.FeedProbe
import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.model.CandidateVia
import io.github.yuubiz.folirel.model.ShareDiscovery
import io.github.yuubiz.folirel.model.ShareProblem
import io.github.yuubiz.folirel.model.ShareProvenance
import io.github.yuubiz.folirel.network.AddressPolicy
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.testutil.cancelAndJoinAll
import io.github.yuubiz.folirel.testutil.literalUrl
import io.github.yuubiz.folirel.testutil.scopeJob
import io.github.yuubiz.folirel.ui.share.ShareState
import io.github.yuubiz.folirel.ui.subscriptions.SubscriptionEvent
import io.github.yuubiz.folirel.ui.subscriptions.SubscriptionsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Share to Subscribe against the real stack (OkHttp network policy, FeedProbe, FeedParser, Room, SubscriptionsViewModel, a real refresh)
 * and a local MockWebServer. P3B-T09..T17 (discovery), T19..T24 (subscription semantics), T35 (moved-feed search shares the core).
 * Assertions are on what the server received and on the database, not on mocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShareToSubscribeTest {
    private val server: MockWebServer = TestEnv.startServer()
    private val env = TestEnv()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = ConcurrentHashMap<String, AtomicInteger>()
    private val routes = ConcurrentHashMap<String, () -> MockResponse>()
    private val extraServers = CopyOnWriteArrayList<MockWebServer>()
    private val viewModels = CopyOnWriteArrayList<SubscriptionsViewModel>()

    private val probe get() = FeedProbe(SmallResourceFetcher(env.httpClient), env.fetcher)
    private val service get() = ShareDiscoveryService(probe, env.subscriptions)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                calls.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                return routes[path]?.invoke() ?: MockResponse.Builder().code(404).build()
            }
        }
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        cancelAndJoinAll(viewModels.map { it.scopeJob } + bg.coroutineContext.job)
        extraServers.forEach { it.close() }
        env.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun url(path: String) = server.url(path).toString()
    private fun count(path: String) = calls[path]?.get() ?: 0

    private fun html(body: String): () -> MockResponse = { MockResponse.Builder().code(200).setHeader("Content-Type", "text/html; charset=utf-8").body(body).build() }

    private fun rss(title: String, vararg guids: String): () -> MockResponse = {
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(
            "<rss version=\"2.0\"><channel><title>$title</title><link>https://site.example/</link>" +
                guids.joinToString("") { "<item><guid>$it</guid><title>t$it</title><link>https://site.example/$it</link><pubDate>Sat, 03 Oct 2026 00:00:00 GMT</pubDate></item>" } +
                "</channel></rss>",
        ).build()
    }

    private fun atom(title: String): () -> MockResponse = {
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/atom+xml").body(
            "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>$title</title><id>urn:x</id><updated>2026-10-03T00:00:00Z</updated>" +
                "<link rel=\"alternate\" href=\"https://site.example/\"/>" +
                "<entry><id>urn:e1</id><title>e1</title><link href=\"https://site.example/e1\"/><updated>2026-10-03T00:00:00Z</updated></entry></feed>",
        ).build()
    }

    private fun feedLink(href: String, type: String = "application/rss+xml", title: String? = null) =
        "<link rel=\"alternate\" type=\"$type\" href=\"$href\"" + (title?.let { " title=\"$it\"" } ?: "") + ">"

    private fun page(vararg links: String) = html("<html><head><title>site</title>${links.joinToString("")}</head><body>hi</body></html>")

    private fun redirect(code: Int, location: String): () -> MockResponse = { MockResponse.Builder().code(code).setHeader("Location", location).build() }

    private suspend fun feedRows() = env.db.subscriptionDao().feeds(env.subscriptions.ensureLocalAccount())

    private fun found(result: ShareDiscovery) = result as ShareDiscovery.Found
    private fun problem(result: ShareDiscovery) = (result as ShareDiscovery.Failed).problem

    // --- Discovery (T09..T17) ---

    @Test
    fun directRssIsACandidateWithItsFacts() = runBlocking<Unit> {
        routes["/direct.xml"] = rss("Direct", "a", "b", "c")
        val c = found(service.discover(url("/direct.xml"))).candidates.single()
        assertEquals(url("/direct.xml"), c.url)
        assertEquals("Direct", c.feedTitle)
        assertEquals("https://site.example/", c.siteUrl)
        assertEquals(3, c.entryCount)
        assertNull(c.existing)
        assertEquals("nothing is registered by looking", 0, feedRows().size)
    }

    @Test
    fun directAtomIsACandidate() = runBlocking<Unit> {
        routes["/atom.xml"] = atom("Atom Blog")
        val c = found(service.discover(url("/atom.xml"))).candidates.single()
        assertEquals("Atom Blog", c.feedTitle)
        assertEquals(1, c.entryCount)
        assertEquals(0, feedRows().size)
    }

    @Test
    fun aPageWithOneDeclaredFeedGivesThatFeed() = runBlocking<Unit> {
        routes["/post/1"] = page(feedLink("/rss.xml", title = "Posts"))
        routes["/rss.xml"] = rss("The Blog", "p1")
        val result = found(service.discover(url("/post/1")))
        assertEquals(url("/post/1"), result.pageUrl)
        val c = result.candidates.single()
        assertEquals(url("/rss.xml"), c.url)
        assertEquals("Posts", c.titleOnPage)
        assertEquals(0, feedRows().size)
    }

    @Test
    fun aPageWithSeveralFeedsKeepsOnlyTheVerifiedOnesInPageOrder() = runBlocking<Unit> {
        routes["/"] = page(
            feedLink("/all.xml", title = "All"),
            feedLink("/comments.xml", "application/atom+xml", "Comments"),
            feedLink("/missing.xml"),
            feedLink("/notfeed.xml"),
        )
        routes["/all.xml"] = rss("All posts", "a")
        routes["/comments.xml"] = atom("Comments")
        routes["/notfeed.xml"] = html("<html><body>not a feed</body></html>")
        val candidates = found(service.discover(url("/"))).candidates
        assertEquals(listOf(url("/all.xml"), url("/comments.xml")), candidates.map { it.url })
        assertEquals(0, feedRows().size)
    }

    @Test
    fun aPageWithoutFeedLinksIsReportedAsNoFeed() = runBlocking<Unit> {
        routes["/"] = html("<html><head><title>nothing</title></head><body>hello</body></html>")
        assertEquals(ShareProblem.NO_FEED, problem(service.discover(url("/"))))
        assertEquals(0, feedRows().size)
    }

    @Test
    fun anHtmlPageAtADeclaredFeedAddressIsNeverACandidate() = runBlocking<Unit> {
        routes["/"] = page(feedLink("/fake.xml"))
        routes["/fake.xml"] = html("<html><head><title>login</title></head><body>please sign in</body></html>")
        assertEquals(ShareProblem.FEEDS_UNREADABLE, problem(service.discover(url("/"))))
        // An HTML page that merely looks like a feed address is a page; it has no feed, and the root fallback finds only the unreadable link.
        routes["/page.xml"] = html("<html><body>just a page</body></html>")
        assertEquals(ShareProblem.FEEDS_UNREADABLE, problem(service.discover(url("/page.xml"))))
        routes["/"] = html("<html><body>no links</body></html>")
        assertEquals(ShareProblem.NO_FEED, problem(service.discover(url("/page.xml"))))
    }

    @Test
    fun aRedirectInsideACandidateGivesTheFinalAddress() = runBlocking<Unit> {
        routes["/"] = page(feedLink("/old.xml"))
        routes["/old.xml"] = redirect(301, url("/new.xml"))
        routes["/new.xml"] = rss("Moved", "m")
        val c = found(service.discover(url("/"))).candidates.single()
        assertEquals("the address to subscribe to is the final one", url("/new.xml"), c.url)
        assertEquals(url("/new.xml"), c.subscribeUrl)
    }

    @Test
    fun aRedirectOfTheSharedAddressItselfIsFollowed() = runBlocking<Unit> {
        routes["/short"] = redirect(302, url("/long/page"))
        routes["/long/page"] = page(feedLink("rss.xml"))
        routes["/long/rss.xml"] = rss("Resolved against the final page", "r")
        val result = found(service.discover(url("/short")))
        assertEquals(url("/long/page"), result.pageUrl)
        assertEquals(url("/long/rss.xml"), result.candidates.single().url)
    }

    @Test
    fun atMostEightDeclaredFeedsAreLookedAt() = runBlocking<Unit> {
        routes["/"] = page(*(1..12).map { feedLink("/f$it.xml") }.toTypedArray())
        (1..12).forEach { routes["/f$it.xml"] = rss("Feed $it", "x$it") }
        assertEquals(FeedProbe.MAX_FEEDS, found(service.discover(url("/"))).candidates.size)
        assertEquals(0, count("/f9.xml"))
    }

    @Test
    fun anUntrustedCertificateIsATlsProblemAndIsNeverAccepted() = runBlocking<Unit> {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val https = MockWebServer()
        https.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        https.start(InetAddress.getByName("127.0.0.1"), 0)
        extraServers += https
        https.enqueue(MockResponse.Builder().code(200).body("<rss version=\"2.0\"><channel><title>x</title></channel></rss>").build())
        assertEquals(ShareProblem.TLS, problem(service.discover(https.literalUrl("/feed.xml").toString())))
        assertEquals("the request never got past the handshake", 0, https.requestCount)
        assertEquals(0, feedRows().size)
    }

    @Test
    fun anUnreachableHostIsANetworkProblem() = runBlocking<Unit> {
        val dead = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val deadUrl = dead.literalUrl("/feed.xml").toString()
        dead.close()
        assertEquals(ShareProblem.NETWORK, problem(service.discover(deadUrl)))
    }

    @Test
    fun anErrorStatusAndAnUnsafeAddressAndAMalformedAddressAreEachTheirOwnProblem() = runBlocking<Unit> {
        assertEquals(ShareProblem.PAGE_STATUS, problem(service.discover(url("/nothing-here"))))
        assertEquals(ShareProblem.INVALID_URL, problem(service.discover("not a url")))
        assertEquals(ShareProblem.INVALID_URL, problem(service.discover("javascript:alert(1)")))
        assertEquals(ShareProblem.INVALID_URL, problem(service.discover("https://user:pw@example.com/")))
        // The address policy of the app (no loopback / private targets) is the network client's, not relaxed for sharing.
        val strict = TestEnv(addressPolicy = AddressPolicy.PUBLIC_ONLY)
        try {
            val blocked = ShareDiscoveryService(FeedProbe(SmallResourceFetcher(strict.httpClient), strict.fetcher), strict.subscriptions)
            assertEquals(ShareProblem.BLOCKED, problem(blocked.discover(url("/direct.xml"))))
        } finally {
            strict.close()
        }
        assertEquals(0, count("/direct.xml"))
    }

    @Test
    fun anHttpAddressWhileHttpIsNotAllowedSendsNothing() = runBlocking<Unit> {
        routes["/direct.xml"] = rss("Plain", "a")
        env.allowHttp.set(false)
        assertEquals(ShareProblem.HTTP_NOT_ALLOWED, problem(service.discover(url("/direct.xml"))))
        assertEquals(0, count("/direct.xml"))
        assertEquals(0, feedRows().size)
        // The setting is the same one feeds use: once HTTP is allowed the same address works.
        env.allowHttp.set(true)
        assertEquals(1, found(service.discover(url("/direct.xml"))).candidates.size)
    }

    @Test
    fun anOversizedPageDoesNotCrashAndStillYieldsItsFeedLinksFromTheHead() = runBlocking<Unit> {
        // A page above the page limit is cut, not refused: the links of the head are still read.
        routes["/big"] = html("<html><head>${feedLink("/rss.xml")}</head><body>" + "x".repeat(600 * 1024) + "</body></html>")
        routes["/rss.xml"] = rss("Big", "b")
        assertEquals(url("/rss.xml"), found(service.discover(url("/big"))).candidates.single().url)
    }

    @Test
    fun theMovedFeedSearchStillSharesTheSameCore() = runBlocking<Unit> {
        // T35: FeedDiscovery is built on the same FeedProbe; the full behaviour is covered by FeedDiscoveryTest.
        routes["/"] = page(feedLink("/old-feed.xml"), feedLink("/new.xml"))
        routes["/old-feed.xml"] = rss("My Blog", "a")
        routes["/new.xml"] = rss("My Blog", "a")
        val known = FeedDiscovery.Known(url("/old-feed.xml"), url("/"), "My Blog", setOf("id:a"), emptyList())
        val result = FeedDiscovery(probe).discover(known)
        assertEquals(listOf(url("/new.xml")), result.candidates.map { it.url })
        assertEquals(1, result.candidates.single().sharedEntries)
        assertEquals(CandidateVia.SITE_PAGE, result.candidates.single().via)
    }

    // --- Same-origin root fallback (0.9.3 P1) and one fetch per address (P2) ---

    private fun otherServer(vararg r: Pair<String, () -> MockResponse>): MockWebServer {
        val map = r.toMap()
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.computeIfAbsent("other:" + request.url.encodedPath) { AtomicInteger() }.incrementAndGet()
                return map[request.url.encodedPath]?.invoke() ?: MockResponse.Builder().code(404).build()
            }
        }
        s.start(InetAddress.getByName("127.0.0.1"), 0)
        extraServers += s
        return s
    }

    private fun article(vararg links: String) = page(*links)

    @Test
    fun anArticleWithoutAFeedFindsTheFeedOfTheSiteRootExactlyOnce() = runBlocking<Unit> {
        routes["/docs/a/1.html"] = html("<html><head><title>article</title></head><body>no feed link here</body></html>")
        routes["/"] = page(feedLink("/data/rss/1.0/wf/feed.rdf", title = "Site feed"))
        routes["/data/rss/1.0/wf/feed.rdf"] = rss("Site feed", "r1", "r2")
        val c = found(service.discover(url("/docs/a/1.html"))).candidates.single()
        assertEquals(url("/data/rss/1.0/wf/feed.rdf"), c.url)
        assertEquals(ShareProvenance.SITE_ROOT, c.provenance)
        // shared HTML 1 + root HTML 1 + the candidate 1; nothing else.
        assertEquals(1, count("/docs/a/1.html"))
        assertEquals(1, count("/"))
        assertEquals(1, count("/data/rss/1.0/wf/feed.rdf"))
        assertEquals(3, calls.values.sumOf { it.get() })
        assertEquals(0, feedRows().size)
    }

    @Test
    fun aValidFeedOfTheSharedPageMeansTheRootIsNotRead() = runBlocking<Unit> {
        routes["/docs/a/1.html"] = article(feedLink("/own.xml"))
        routes["/own.xml"] = rss("Own", "o")
        routes["/"] = page(feedLink("/site.xml"))
        routes["/site.xml"] = rss("Site", "s")
        val c = found(service.discover(url("/docs/a/1.html"))).candidates.single()
        assertEquals(ShareProvenance.SHARED_PAGE, c.provenance)
        assertEquals(0, count("/"))
        assertEquals(0, count("/site.xml"))
    }

    @Test
    fun aDirectFeedNeverReadsTheRoot() = runBlocking<Unit> {
        routes["/data/feed.rdf"] = rss("Direct", "d")
        routes["/"] = page(feedLink("/site.xml"))
        val c = found(service.discover(url("/data/feed.rdf"))).candidates.single()
        assertEquals(ShareProvenance.DIRECT_FEED, c.provenance)
        assertEquals(0, count("/"))
    }

    @Test
    fun feedLinksOfTheSharedPageThatAreAllBrokenStillAllowTheRootFallback() = runBlocking<Unit> {
        routes["/docs/a/1.html"] = article(feedLink("/gone.xml"), feedLink("/login.xml"))
        routes["/login.xml"] = html("<html><body>sign in</body></html>")
        routes["/"] = page(feedLink("/site.xml"))
        routes["/site.xml"] = rss("Site", "s")
        val c = found(service.discover(url("/docs/a/1.html"))).candidates.single()
        assertEquals(url("/site.xml"), c.url)
        assertEquals(ShareProvenance.SITE_ROOT, c.provenance)
        assertEquals(1, count("/gone.xml"))
        assertEquals(1, count("/login.xml"))
    }

    @Test
    fun aSharedRootThatHasNoFeedIsReadOnceNotTwice() = runBlocking<Unit> {
        routes["/"] = html("<html><head><title>top</title></head><body>no feed</body></html>")
        assertEquals(ShareProblem.NO_FEED, problem(service.discover(url("/"))))
        assertEquals(1, count("/"))
        // The root with a query is not "the root page itself": the plain root is read once as the fallback.
        assertEquals(ShareProblem.NO_FEED, problem(service.discover(url("/?page=2"))))
        assertEquals(1 + 2, count("/"))
    }

    @Test
    fun aRootThatHasNoFeedEndsTheSearchAndNothingDeeperIsTried() = runBlocking<Unit> {
        routes["/docs/a/1.html"] = article()
        routes["/"] = html("<html><head><title>top</title></head><body><a href=\"/feed\">feed</a><a href=\"/rss\">rss</a></body></html>")
        assertEquals(ShareProblem.NO_FEED, problem(service.discover(url("/docs/a/1.html"))))
        // Only the two pages; no guessed addresses (/feed, /rss, /atom.xml ...), no sitemap, no other page of the site.
        assertEquals(setOf("/docs/a/1.html", "/"), calls.keys.toSet())
        assertEquals(2, calls.values.sumOf { it.get() })
    }

    @Test
    fun theRootOfAFinalPageAfterARedirectIsTheRootOfTheFinalOrigin() = runBlocking<Unit> {
        val final = otherServer(
            "/article" to html("<html><head><title>a</title></head><body>no feed</body></html>"),
            "/" to page(feedLink("/f.xml")),
            "/f.xml" to rss("Final site", "f"),
        )
        routes["/short"] = redirect(302, final.literalUrl("/article").toString())
        routes["/"] = page(feedLink("/wrong.xml"))
        val c = found(service.discover(url("/short"))).candidates.single()
        assertEquals(final.literalUrl("/f.xml").toString(), c.url)
        assertEquals(ShareProvenance.SITE_ROOT, c.provenance)
        assertEquals("the root of the first origin was not read", 0, count("/"))
        assertEquals(1, count("other:/"))
    }

    @Test
    fun aRootThatLeadsToAnotherOriginGivesNothing() = runBlocking<Unit> {
        val elsewhere = otherServer("/" to page(feedLink("/f.xml")), "/f.xml" to rss("Elsewhere", "e"))
        routes["/docs/a/1.html"] = article()
        routes["/"] = redirect(302, elsewhere.literalUrl("/").toString())
        assertEquals(ShareProblem.NO_FEED, problem(service.discover(url("/docs/a/1.html"))))
        assertEquals("the feed of the other origin is not looked at", 0, count("other:/f.xml"))
    }

    @Test
    fun theRootFallbackKeepsTheHttpRule() = runBlocking<Unit> {
        routes["/docs/a/1.html"] = article()
        routes["/"] = page(feedLink("/site.xml"))
        routes["/site.xml"] = rss("Site", "s")
        env.allowHttp.set(false)
        assertEquals(ShareProblem.HTTP_NOT_ALLOWED, problem(service.discover(url("/docs/a/1.html"))))
        assertEquals(0, calls.values.sumOf { it.get() })
    }

    @Test
    fun aFeedAddressedByThePageAndByTheRootIsOneCandidateAndFetchedOnce() = runBlocking<Unit> {
        routes["/docs/a/1.html"] = article(feedLink("/broken.xml"))
        routes["/"] = page(feedLink("/broken.xml"), feedLink("/alias.xml"), feedLink("/good.xml"))
        routes["/alias.xml"] = redirect(301, url("/good.xml"))
        routes["/good.xml"] = rss("Good", "g")
        val candidates = found(service.discover(url("/docs/a/1.html"))).candidates
        assertEquals("two links of the root end at one feed", listOf(url("/good.xml")), candidates.map { it.url })
        assertEquals("the address the page already failed on is not asked again", 1, count("/broken.xml"))
    }

    @Test
    fun directRssAndAtomAreFetchedOnceEach() = runBlocking<Unit> {
        routes["/direct.xml"] = rss("Direct", "a", "b")
        routes["/atom.xml"] = atom("Atom")
        assertEquals(2, found(service.discover(url("/direct.xml"))).candidates.single().entryCount)
        assertEquals(1, count("/direct.xml"))
        assertEquals("Atom", found(service.discover(url("/atom.xml"))).candidates.single().feedTitle)
        assertEquals(1, count("/atom.xml"))
    }

    @Test
    fun aRedirectToAFeedIsOneChainAndNoSecondCheck() = runBlocking<Unit> {
        routes["/old"] = redirect(301, url("/new.xml"))
        routes["/new.xml"] = rss("Moved", "m")
        val c = found(service.discover(url("/old"))).candidates.single()
        assertEquals(url("/new.xml"), c.url)
        assertEquals(1, count("/old"))
        assertEquals(1, count("/new.xml"))
    }

    @Test
    fun anHtmlPageWithDeclaredFeedsIsOneRequestPlusOnePerCandidate() = runBlocking<Unit> {
        routes["/post"] = page(feedLink("/a.xml"), feedLink("/b.xml"))
        routes["/a.xml"] = rss("A", "a")
        routes["/b.xml"] = rss("B", "b")
        assertEquals(2, found(service.discover(url("/post"))).candidates.size)
        assertEquals(1, count("/post"))
        assertEquals(1, count("/a.xml"))
        assertEquals(1, count("/b.xml"))
        assertEquals(0, count("/"))
    }

    @Test
    fun aDirectFeedLargerThanAPageIsStillReadInFull() = runBlocking<Unit> {
        // The first read is the only one, so it must not cut a feed at the page limit (512 KiB).
        val filler = "x".repeat(150 * 1024)
        routes["/big.xml"] = {
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(
                "<rss version=\"2.0\"><channel><title>Big</title>" +
                    (1..6).joinToString("") { "<item><guid>g$it</guid><title>t$it</title><description>$filler</description></item>" } +
                    "</channel></rss>",
            ).build()
        }
        val c = found(service.discover(url("/big.xml"))).candidates.single()
        assertEquals(6, c.entryCount)
        assertEquals(1, count("/big.xml"))
    }

    // --- Subscription semantics through the real view model (T19..T24) ---

    private fun viewModel(): SubscriptionsViewModel {
        val coordinator = RefreshCoordinator(env.backend, { env.subscriptions.ensureLocalAccount() }, { true }, {}, bg, env.clock)
        val upgrade = HttpsUpgradeCoordinator(HttpsUpgradeChecker(env.fetcher, env.fetcher, { true }), env.subscriptions, coordinator, bg)
        return SubscriptionsViewModel(
            env.subscriptions, coordinator, upgrade,
            FeedDiscoveryService(FeedDiscovery(probe), env.subscriptions), service,
            DocumentIo(TestEnv.context()), { env.allowHttp.get() }, env.clock,
        ).also { viewModels += it }
    }

    /** Suspends until [flow] has a value that satisfies [predicate]: driven by the state changing, with a deadline only to fail a test that would hang. */
    private suspend fun <T> await(what: String, flow: Flow<T>, predicate: (T) -> Boolean): T =
        withTimeoutOrNull(30_000) { flow.first(predicate) } ?: error("not reached: $what")

    private suspend fun SubscriptionsViewModel.shareAndWait(text: String): ShareState.Candidates {
        share.setReady(true)
        share.receive(text)
        await("candidates", share.state) { it is ShareState.Candidates || it is ShareState.Failed }
        return share.state.value as ShareState.Candidates
    }

    /** The refresh that follows an add has finished (a finished request is recorded; nothing is recorded before one starts). */
    private suspend fun SubscriptionsViewModel.awaitRefreshFinished() =
        await("refresh finished", refreshState) { s -> s.requests.isNotEmpty() && s.requests.none { it.running } }

    @Test
    fun nothingIsRegisteredUntilTheUserConfirmsAndThenTheFeedIsAddedWithOneRefresh() = runBlocking<Unit> {
        routes["/post"] = page(feedLink("/rss.xml"))
        routes["/rss.xml"] = rss("The Blog", "a", "b")
        val vm = viewModel()
        val shown = vm.shareAndWait("読んでみて\n${url("/post")}")
        assertEquals(1, shown.candidates.size)
        // T19: found and shown, nothing stored, nothing fetched for a refresh.
        assertEquals(0, feedRows().size)
        assertEquals("the feed was fetched once, to verify it", 1, count("/rss.xml"))

        vm.share.subscribe(shown.candidates.single())
        // T20
        await("done", vm.share.state) { it is ShareState.Done }
        val added = (vm.share.state.value as ShareState.Done).result as AddFeedResult.Added
        val rows = feedRows()
        assertEquals(1, rows.size)
        assertEquals(added.feedId, rows.single().id)
        assertEquals(url("/rss.xml"), rows.single().subscriptionUrl)
        assertTrue(rows.single().isSubscribed)
        val event = withTimeout(5_000) { vm.eventFlow.first() }
        assertTrue(event is SubscriptionEvent.FeedAdded && event.result is AddFeedResult.Added && !event.httpPending)
        // T24: the add path's own refresh fetches the new feed exactly once (the check by discovery + that refresh), and it is the only request.
        vm.awaitRefreshFinished()
        assertEquals(1, vm.refreshState.value.requests.size)
        assertEquals(2, env.db.articleDao().articlesOfFeed(added.feedId).size)
        assertEquals(2, count("/rss.xml"))
        assertEquals(1, feedRows().size)
    }

    @Test
    fun unsubscribingOneFeedOffersAnUndoThatSubscribesExactlyThatFeedAgain() = runBlocking<Unit> {
        routes["/rss.xml"] = rss("The Blog", "a")
        routes["/other.xml"] = rss("Other", "o")
        val account = env.subscriptions.ensureLocalAccount()
        val id = (env.subscriptions.addFeed(account, url("/rss.xml")) as AddFeedResult.Added).feedId
        val otherId = (env.subscriptions.addFeed(account, url("/other.xml")) as AddFeedResult.Added).feedId
        env.subscriptions.unsubscribe(otherId)
        val vm = viewModel()

        vm.unsubscribe(id).join()
        val event = withTimeout(5_000) { vm.eventFlow.first() }
        assertEquals(SubscriptionEvent.BulkUnsubscribed(listOf(id), skippedRecovered = 0), event)
        assertFalse(feedRows().single { it.id == id }.isSubscribed)

        vm.undoBulkUnsubscribe((event as SubscriptionEvent.BulkUnsubscribed).ids).join()
        assertTrue(feedRows().single { it.id == id }.isSubscribed)
        assertFalse("a feed the action did not change stays as it was", feedRows().single { it.id == otherId }.isSubscribed)
        // Unsubscribing a feed that already is changes nothing, so there is nothing to undo.
        vm.unsubscribe(otherId).join()
        assertEquals(SubscriptionEvent.BulkUnsubscribed(emptyList(), skippedRecovered = 0), withTimeout(5_000) { vm.eventFlow.first() })
    }

    @Test
    fun aFeedThatIsAlreadySubscribedIsShownAsSuchAndNoDuplicateIsMade() = runBlocking<Unit> {
        routes["/rss.xml"] = rss("The Blog", "a")
        val account = env.subscriptions.ensureLocalAccount()
        val existingId = (env.subscriptions.addFeed(account, url("/rss.xml")) as AddFeedResult.Added).feedId
        val vm = viewModel()
        val shown = vm.shareAndWait(url("/rss.xml"))
        val c = shown.candidates.single()
        assertEquals(existingId, c.existing?.feedId)
        assertTrue(c.existing!!.subscribed)
        vm.share.subscribe(c)
        assertTrue("no registration is started", vm.share.state.value is ShareState.Candidates)
        assertEquals(1, feedRows().size)
    }

    @Test
    fun aFeedKnownUnderItsRedirectSourceIsRecognisedToo() = runBlocking<Unit> {
        // Registered under the address the page declares; the final address after the redirect differs: still the same feed, no copy.
        routes["/"] = page(feedLink("/old.xml"))
        routes["/old.xml"] = redirect(301, url("/new.xml"))
        routes["/new.xml"] = rss("Moved", "m")
        val account = env.subscriptions.ensureLocalAccount()
        val existingId = (env.subscriptions.addFeed(account, url("/old.xml")) as AddFeedResult.Added).feedId
        val vm = viewModel()
        val c = vm.shareAndWait(url("/")).candidates.single()
        assertEquals(existingId, c.existing?.feedId)
        assertEquals(url("/old.xml"), c.subscribeUrl)
    }

    @Test
    fun anUnsubscribedFeedNeedsAnExplicitResubscribeAndKeepsItsRow() = runBlocking<Unit> {
        routes["/rss.xml"] = rss("The Blog", "a")
        val account = env.subscriptions.ensureLocalAccount()
        val id = (env.subscriptions.addFeed(account, url("/rss.xml")) as AddFeedResult.Added).feedId
        val category = (env.subscriptions.createCategory(account, null, "News") as io.github.yuubiz.folirel.data.repository.SubscriptionRepository.CategoryResult.Ok).categoryId
        env.subscriptions.setFeedCategories(id, setOf(category))
        env.subscriptions.unsubscribe(id)

        val vm = viewModel()
        val c = vm.shareAndWait(url("/rss.xml")).candidates.single()
        assertEquals(id, c.existing?.feedId)
        assertFalse(c.existing!!.subscribed)
        assertFalse("seeing the candidate does not subscribe", feedRows().single().isSubscribed)

        vm.share.subscribe(c) // T22: the explicit "Subscribe again"
        await("done", vm.share.state) { it is ShareState.Done }
        assertEquals(AddFeedResult.Resubscribed(id), (vm.share.state.value as ShareState.Done).result)
        val rows = feedRows()
        assertEquals("the same row, no copy", listOf(id), rows.map { it.id })
        assertTrue(rows.single().isSubscribed)
        assertEquals("categories are kept", setOf(category), env.db.subscriptionDao().memberships(account).filter { it.feedId == id }.map { it.categoryId }.toSet())
        vm.awaitRefreshFinished()
    }

    @Test
    fun cancelAfterTheCandidatesWereShownChangesNothing() = runBlocking<Unit> {
        routes["/rss.xml"] = rss("The Blog", "a")
        val before = feedRows()
        val vm = viewModel()
        vm.shareAndWait(url("/rss.xml"))
        // A shared feed address is read once while it is checked (P2); the bytes of that response are the candidate.
        val checked = count("/rss.xml")
        assertEquals(1, checked)
        vm.share.cancel()
        assertEquals(ShareState.Idle, vm.share.state.value)
        assertEquals(before, feedRows())
        assertTrue("no refresh was started either", vm.refreshState.value.requests.isEmpty())
        assertEquals(checked, count("/rss.xml"))
    }

    @Test
    fun anHttpFeedIsRegisteredLikeAddFeedDoesAndIsMarkedPendingWhenHttpIsOff() = runBlocking<Unit> {
        // Discovery needs HTTP to verify an http:// feed, so with HTTP off nothing is shown or registered (T17/T23).
        routes["/rss.xml"] = rss("Plain", "a")
        env.allowHttp.set(false)
        val vm = viewModel()
        vm.share.setReady(true)
        vm.share.receive(url("/rss.xml"))
        await("problem", vm.share.state) { it is ShareState.Failed }
        assertEquals(ShareProblem.HTTP_NOT_ALLOWED, (vm.share.state.value as ShareState.Failed).problem)
        assertEquals(0, feedRows().size)
        assertEquals(0, count("/rss.xml"))
    }
}
