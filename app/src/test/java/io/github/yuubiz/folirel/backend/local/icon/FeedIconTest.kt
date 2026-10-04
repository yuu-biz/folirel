package io.github.yuubiz.folirel.backend.local.icon

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.HtmlLinks
import io.github.yuubiz.folirel.backend.local.SmallResourceFetcher
import io.github.yuubiz.folirel.backend.local.parser.FeedParser
import io.github.yuubiz.folirel.data.db.IconStatus
import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.data.repository.AttemptInfo
import io.github.yuubiz.folirel.data.repository.BodyStats
import io.github.yuubiz.folirel.data.repository.FeedIconRepository
import io.github.yuubiz.folirel.data.repository.FetchMetadata
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.sync.FeedIconCoordinator
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * U08 / U09 (logic): where a feed's source icon comes from, in which order, how relative and redirected URLs
 * resolve, what is skipped or retried, and that lookups happen once per feed and never when images are off.
 * Image decoding is replaced by a stand-in here ("WxH:" in front of the bytes = a decodable image); real PNG/ICO
 * decoding is checked on a device (IconDecoderTest in androidTest).
 */
@RunWith(AndroidJUnit4::class)
class FeedIconTest {
    private val server: MockWebServer = TestEnv.startServer()
    private val env = TestEnv()
    private val paths = CopyOnWriteArrayList<String>()
    private var routes: Map<String, () -> MockResponse> = emptyMap()

    @After
    fun tearDown() {
        env.close()
        server.close()
    }

    private val decoder = IconDecoder { bytes ->
        val text = String(bytes, Charsets.ISO_8859_1)
        val m = Regex("^(\\d+)x(\\d+):").find(text) ?: return@IconDecoder null
        DecodedIcon(bytes, m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    private fun image(w: Int, h: Int) = { MockResponse.Builder().code(200).setHeader("Content-Type", "image/png").body(Buffer().writeUtf8("${w}x$h:data")).build() }

    private fun html(body: String, status: Int = 200) = { MockResponse.Builder().code(status).setHeader("Content-Type", "text/html; charset=utf-8").body(body).build() }

    private fun serve(vararg r: Pair<String, () -> MockResponse>) {
        routes = r.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.url.encodedPath
                return routes[request.url.encodedPath]?.invoke() ?: MockResponse.Builder().code(404).build()
            }
        }
    }

    private fun resolver() = FeedIconResolver(SmallResourceFetcher(env.httpClient), decoder)

    private fun url(path: String) = server.url(path).toString()

    private suspend fun resolve(declared: String? = null, site: String? = null, links: List<String> = emptyList()) =
        resolver().resolve(FeedIconResolver.Target(declared, site, links))

    private fun FeedIconResolver.Result.found() = this as FeedIconResolver.Result.Found

    @Test
    fun declaredIconWinsAndNothingElseIsRequested() = runBlocking<Unit> {
        serve("/feed-icon.png" to image(64, 64), "/" to html("<html><head><link rel=icon href=/other.png></head></html>"))
        val r = resolve(declared = url("/feed-icon.png"), site = url("/")).found()
        assertEquals("FEED", r.kind)
        assertEquals(listOf("/feed-icon.png"), paths.toList())
    }

    @Test
    fun siteLinkIconsAreRankedByDistanceFromFortyEightAndResolvedAgainstTheFinalUrlAndBase() = runBlocking<Unit> {
        serve(
            "/old/" to { MockResponse.Builder().code(301).setHeader("Location", "/blog/index.html").build() },
            "/blog/index.html" to html(
                """<html><head><base href="/assets/">
                   <link rel="icon" sizes="16x16" href="i16.png">
                   <link rel="apple-touch-icon" sizes="180x180" href="a180.png">
                   <link rel="shortcut icon" sizes="48x48" href="i48.png">
                   <link rel="mask-icon" href="mask.svg"><link rel="icon" type="image/svg+xml" href="x.svg">
                   </head></html>""",
            ),
            "/assets/i48.png" to image(48, 48),
        )
        val r = resolve(site = url("/old/")).found()
        assertEquals("HTML", r.kind)
        assertTrue(r.sourceUrl.endsWith("/assets/i48.png"))
        // The redirect was followed, the page read once, the best candidate fetched first; nothing else needed.
        assertEquals(listOf("/old/", "/blog/index.html", "/assets/i48.png"), paths.toList())
    }

    @Test
    fun faviconIcoAtTheSiteRootIsTheLastResortBeforeTheInitial() = runBlocking<Unit> {
        serve("/blog/" to html("<html><head></head></html>"), "/favicon.ico" to image(32, 32))
        val r = resolve(site = url("/blog/")).found()
        assertEquals("FAVICON", r.kind)
        assertEquals(listOf("/blog/", "/favicon.ico"), paths.toList())
    }

    @Test
    fun aWideLogoIsOnlyUsedWhenNothingBetterExists() = runBlocking<Unit> {
        serve("/logo.png" to image(300, 50), "/" to html("<html><head><link rel=icon href=/fav.png></head></html>"), "/fav.png" to image(32, 32))
        assertEquals("HTML", resolve(declared = url("/logo.png"), site = url("/")).found().kind)

        paths.clear()
        serve("/logo.png" to image(300, 50), "/" to html("<html><head></head></html>"))
        val banner = resolve(declared = url("/logo.png"), site = url("/")).found()
        assertEquals("BANNER", banner.kind)
        assertTrue(banner.icon.isBanner)
    }

    @Test
    fun anHtmlPageInPlaceOfAnImageIsSkippedAndNothingFoundMeansNone() = runBlocking<Unit> {
        serve(
            "/" to html("<html><head><link rel=icon href=/fav.png></head></html>"),
            "/fav.png" to html("<html>not an image</html>"),
            "/favicon.ico" to html("<html>also not</html>"),
        )
        assertEquals(FeedIconResolver.Result.None, resolve(declared = url("/fav.png"), site = url("/")))
    }

    @Test
    fun missingSiteIsNoneButServerTroubleIsRetry() = runBlocking<Unit> {
        serve("/" to html("gone", status = 404))
        assertEquals(FeedIconResolver.Result.None, resolve(site = url("/")))
        serve("/" to html("busy", status = 503))
        assertEquals(FeedIconResolver.Result.Retry, resolve(site = url("/")))
        // Connection refused: a port nobody listens on.
        val dead = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        val deadUrl = dead.url("/").toString()
        dead.close()
        assertEquals(FeedIconResolver.Result.Retry, resolve(site = deadUrl))
    }

    @Test
    fun cleartextHttpIsNotUsedWhenNotAllowedAndIsRetriedLater() = runBlocking<Unit> {
        serve("/" to html("<html><head><link rel=icon href=/fav.png></head></html>"), "/fav.png" to image(32, 32))
        env.allowHttp.set(false)
        // The mock server is http:// : nothing is sent, and it is a "try again later", not "no icon".
        assertEquals(FeedIconResolver.Result.Retry, resolve(site = url("/")))
        assertTrue("no request left the app", paths.isEmpty())
        env.allowHttp.set(true)
        assertEquals("HTML", resolve(site = url("/")).found().kind)
    }

    @Test
    fun withoutADeclaredSiteTheArticleLinksNameTheSiteNotTheFeedHost() = runBlocking<Unit> {
        serve("/" to html("<html><head><link rel=icon href=/fav.png></head></html>"), "/fav.png" to image(32, 32))
        val links = listOf(url("/a"), url("/b"), "https://other.invalid/z")
        val r = resolve(site = null, links = links).found()
        assertEquals("HTML", r.kind)
        // No site and no articles: nothing is guessed from the feed address.
        paths.clear()
        assertEquals(FeedIconResolver.Result.None, resolve(site = null, links = emptyList()))
        assertTrue(paths.isEmpty())
    }

    @Test
    fun oversizedIconsAndPagesAreBounded() = runBlocking<Unit> {
        val big = { MockResponse.Builder().code(200).body(Buffer().writeUtf8("64x64:" + "x".repeat(300_000))).build() }
        serve("/big.png" to big, "/" to html("<html><head></head></html>"))
        assertEquals(FeedIconResolver.Result.None, resolve(declared = url("/big.png"), site = url("/")))
        // A page above the limit is cut, and its first part is still read.
        val head = "<html><head><link rel=icon href=/fav.png></head><body>" + "z".repeat(700_000) + "</body></html>"
        serve("/" to html(head), "/fav.png" to image(32, 32))
        assertEquals("HTML", resolve(site = url("/")).found().kind)
    }

    @Test
    fun htmlLinkParsingHandlesSizesAndFeedAlternates() {
        assertEquals(32, HtmlLinks.sizeOf("32x32 16x16"))
        assertNull(HtmlLinks.sizeOf("any"))
        assertNull(HtmlLinks.sizeOf(""))
        val doc = HtmlLinks.parse(
            """<link rel="alternate" type="application/atom+xml" title="Posts" href="/feed.atom">
               <link rel="alternate" type="application/rss+xml" href="https://example.net/rss">
               <link rel="alternate" type="text/html" href="/ja/">
               <link rel="alternate" type="application/rss+xml" href="javascript:alert(1)">""".toByteArray(),
            "utf-8",
            "https://example.com/blog/",
        )
        val feeds = HtmlLinks.feedLinks(doc)
        assertEquals(listOf("https://example.com/feed.atom", "https://example.net/rss"), feeds.map { it.url })
        assertEquals("Posts", feeds.first().title)
    }

    @Test
    fun feedParserReadsDeclaredIcons() {
        val atom = FeedParser.parse(
            """<feed xmlns="http://www.w3.org/2005/Atom"><title>t</title><logo>/logo.png</logo><icon>icon.png</icon></feed>""",
            "https://example.com/dir/feed.xml",
        )
        assertEquals("https://example.com/dir/icon.png", atom.iconUrl)
        val atomLogoOnly = FeedParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>t</title><logo>/logo.png</logo></feed>""", "https://example.com/feed.xml")
        assertEquals("https://example.com/logo.png", atomLogoOnly.iconUrl)
        val rss = FeedParser.parse(
            """<rss version="2.0"><channel><title>t</title><image><url>https://cdn.example.com/i.png</url><link>https://example.com/</link></image></channel></rss>""",
            "https://example.com/rss",
        )
        assertEquals("https://cdn.example.com/i.png", rss.iconUrl)
        assertNull(FeedParser.parse("""<rss version="2.0"><channel><title>t</title></channel></rss>""", "https://example.com/rss").iconUrl)
    }

    // U09: once per feed, never per article or redraw; images off means no lookup at all.

    private suspend fun subscribeWithArticles(articles: Int, siteUrl: String?, iconUrl: String? = null): String {
        val account = env.subscriptions.ensureLocalAccount()
        val feedId = (env.subscriptions.addFeed(account, "https://feeds.example.org/${paths.size}-${siteUrl.hashCode()}.xml") as AddFeedResult.Added).feedId
        val entries = (1..articles).map { NormalizedEntry("url:https://a.example/$it", "t$it", "https://a.example/$it", "", null, 1000L + it, null) }
        env.store.applySuccess(feedId, NormalizedFeed("f", siteUrl, entries, iconUrl = iconUrl), FetchMetadata(null, null, "https://x/", null), BodyStats(0, articles), AttemptInfo(200, false))
        return feedId
    }

    private fun coordinator(enabled: () -> Boolean = { true }) = FeedIconCoordinator(
        resolver = resolver(),
        repository = FeedIconRepository(env.db, env.clock),
        accountId = { env.subscriptions.ensureLocalAccount() },
        enabled = { enabled() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @Test
    fun theLookupRunsOncePerFeedAndNotPerArticleOrPass() = runBlocking<Unit> {
        serve("/" to html("<html><head><link rel=icon href=/fav.png></head></html>"), "/fav.png" to image(32, 32))
        val feedId = subscribeWithArticles(articles = 50, siteUrl = url("/"))
        val c = coordinator()
        assertEquals(1, c.runPasses())
        val requests = paths.size
        assertEquals("one page + one icon for 50 articles", 2, requests)
        assertEquals(IconStatus.OK.name, env.db.iconDao().icon(feedId)!!.status)
        assertEquals("nothing is due again", 0, c.runPasses())
        assertEquals(requests, paths.size)
        // Much later (past the re-check time) the lookup may run again, but not before.
        env.clock.advanceMinutes(60L * 24 * 29)
        assertEquals(0, c.runPasses())
        env.clock.advanceMinutes(60L * 24 * 2)
        assertEquals(1, c.runPasses())
    }

    @Test
    fun failedLookupsAreRetriedWithBackoffAndNeverTouchTheFeedFetchState() = runBlocking<Unit> {
        serve("/" to html("busy", status = 503))
        val feedId = subscribeWithArticles(1, url("/"))
        val before = env.db.subscriptionDao().fetchState(feedId)
        val c = coordinator()
        assertEquals(1, c.runPasses())
        assertEquals(IconStatus.FAILED.name, env.db.iconDao().icon(feedId)!!.status)
        assertEquals(0, c.runPasses()) // not before the retry time
        env.clock.advanceMinutes(61)
        assertEquals(1, c.runPasses())
        assertEquals(2, env.db.iconDao().icon(feedId)!!.failureCount)
        // The feed's own fetch state is unchanged by icon trouble.
        assertEquals(before, env.db.subscriptionDao().fetchState(feedId))
    }

    @Test
    fun noLookupAtAllWhileImagesAreOff() = runBlocking<Unit> {
        serve("/" to html("<html><head><link rel=icon href=/fav.png></head></html>"), "/fav.png" to image(32, 32))
        subscribeWithArticles(3, url("/"))
        val on = AtomicBoolean(false)
        val c = coordinator { on.get() }
        assertEquals(0, c.runPasses())
        assertTrue(paths.isEmpty())
        on.set(true)
        assertEquals(1, c.runPasses())
        assertFalse(paths.isEmpty())
    }

    /** A feed without a known site gets nothing at first; once it has fetched (site and articles known) it is tried again. */
    @Test
    fun aFeedThatLearnsItsSiteIsLookedUpAgainAtOnce() = runBlocking<Unit> {
        serve("/" to html("<html><head><link rel=icon href=/fav.png></head></html>"), "/fav.png" to image(32, 32))
        val account = env.subscriptions.ensureLocalAccount()
        val feedId = (env.subscriptions.addFeed(account, "https://feeds.example.org/late.xml") as AddFeedResult.Added).feedId
        val c = coordinator()
        assertEquals(1, c.runPasses())
        assertEquals(IconStatus.NONE.name, env.db.iconDao().icon(feedId)!!.status)
        assertEquals(0, c.runPasses())
        assertTrue(paths.isEmpty())
        // First successful fetch: it now names its site.
        env.store.applySuccess(feedId, NormalizedFeed("f", url("/"), emptyList()), FetchMetadata(null, null, "https://x/", null), BodyStats(0, 0), AttemptInfo(200, false))
        assertEquals(1, c.runPasses())
        assertEquals(IconStatus.OK.name, env.db.iconDao().icon(feedId)!!.status)
    }

    @Test
    fun aChangedDeclaredIconUrlStartsANewLookup() = runBlocking<Unit> {
        serve("/one.png" to image(32, 32), "/two.png" to image(40, 40))
        val feedId = subscribeWithArticles(1, null, iconUrl = url("/one.png"))
        val c = coordinator()
        c.runPasses()
        assertEquals(32, env.db.iconDao().icon(feedId)!!.width)
        // Same URL again: stays.
        env.store.applySuccess(feedId, NormalizedFeed("f", null, emptyList(), iconUrl = url("/one.png")), FetchMetadata(null, null, "https://x/", null))
        assertEquals(0, c.runPasses())
        // New URL: looked up at once, and the old icon is replaced.
        env.store.applySuccess(feedId, NormalizedFeed("f", null, emptyList(), iconUrl = url("/two.png")), FetchMetadata(null, null, "https://x/", null))
        assertEquals(1, c.runPasses())
        assertEquals(40, env.db.iconDao().icon(feedId)!!.width)
        assertEquals(url("/two.png"), env.db.iconDao().icon(feedId)!!.sourceUrl)
    }
}
