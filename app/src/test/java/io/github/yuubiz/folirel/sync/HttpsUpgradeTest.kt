package io.github.yuubiz.folirel.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.backend.local.FeedFetcher
import io.github.yuubiz.folirel.backend.local.HttpsUpgradeChecker
import io.github.yuubiz.folirel.backend.local.LocalBackend
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.model.HttpsReason
import io.github.yuubiz.folirel.model.HttpsVerdict
import io.github.yuubiz.folirel.network.HttpClients
import io.github.yuubiz.folirel.network.NetworkGuard
import io.github.yuubiz.folirel.testutil.LOOPBACK_ALLOWED_FOR_TESTS
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.testutil.literalUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * User-consented HTTP → HTTPS migration: verification rules (real GET over verified TLS + RSS/Atom parse +
 * same feed, no downgrade), data kept (feed id, categories, articles, read/saved), no duplicates on refetch or
 * re-import, cancellation and "not checked" handling.
 */
@RunWith(AndroidJUnit4::class)
class HttpsUpgradeTest {
    private val http: MockWebServer = TestEnv.startServer()
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
    private val https: MockWebServer = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    /** HTTPS with a certificate the client does not trust. */
    private val untrusted: MockWebServer = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()).build().sslSocketFactory())
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val env = TestEnv()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The checker's client: never allows cleartext HTTP; trusts only the test certificate. */
    private fun strictClient(timeoutMillis: Long = 5_000): OkHttpClient =
        HttpClients.feedClient(NetworkGuard(LOOPBACK_ALLOWED_FOR_TESTS, { false }), "FolirelTest/1").newBuilder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis * 2, TimeUnit.MILLISECONDS)
            .build()

    /** Normal fetching after the switch: HTTP allowed (for the old URLs) and the test certificate trusted. */
    private val trustingHttpClient = env.httpClient.newBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    private val backend = LocalBackend(
        env.store,
        FeedFetcher(trustingHttpClient, env.clock),
        env.clock, env.network, { true },
    )

    private fun checker(timeoutMillis: Long = 5_000, httpAllowed: Boolean = false) = HttpsUpgradeChecker(
        FeedFetcher(strictClient(timeoutMillis), env.clock),
        httpFetcher = FeedFetcher(trustingHttpClient, env.clock),
        httpAllowed = { httpAllowed },
        candidateFor = { url ->
            val target = if (url.encodedPath.startsWith("/tls")) untrusted else https
            url.newBuilder().scheme("https").port(target.port).build()
        },
    )

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
        http.close()
        https.close()
        untrusted.close()
    }

    private fun rss(title: String, items: List<Pair<String, String>>) =
        """<rss version="2.0"><channel><title>$title</title>""" +
            items.joinToString("") { (guid, link) -> "<item><guid isPermaLink=\"false\">$guid</guid><title>$guid</title><link>$link</link></item>" } +
            "</channel></rss>"

    private fun linkOnly(title: String, links: List<String>) =
        """<rss version="2.0"><channel><title>$title</title>""" + links.joinToString("") { "<item><title>$it</title><link>$it</link></item>" } + "</channel></rss>"

    private fun ok(body: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(body).build()

    private fun httpUrl(path: String) = http.literalUrl(path).toString()

    private fun httpsUrl(path: String) = https.literalUrl(path).toString()

    private suspend fun account() = env.subscriptions.ensureLocalAccount()

    private suspend fun importOpml(xml: String) = env.subscriptions.commitImport(account(), OpmlParser.parse(xml.toByteArray()))

    private fun opml(vararg urls: String) =
        "<opml version=\"2.0\"><body><outline text=\"Tech\">" + urls.joinToString("") { "<outline text=\"f\" xmlUrl=\"$it\"/>" } + "</outline></body></opml>"

    private suspend fun runUpgrade(checker: HttpsUpgradeChecker, cancelAfterMillis: Long? = null): HttpsUpgradeState {
        val coordinator = RefreshCoordinator(backend, { account() }, { true }, {}, scope, env.clock)
        val upgrade = HttpsUpgradeCoordinator(checker, env.subscriptions, coordinator, scope)
        assertTrue(upgrade.start())
        if (cancelAfterMillis != null) {
            Thread.sleep(cancelAfterMillis)
            upgrade.cancel()
        }
        val end = System.currentTimeMillis() + 20_000
        while (!upgrade.state.value.finished) {
            check(System.currentTimeMillis() < end) { "upgrade did not finish" }
            Thread.sleep(20)
        }
        // Let the follow-up refresh of switched feeds finish.
        while (coordinator.state.value.running || (upgrade.state.value.count(HttpsVerdict.CHANGED) > 0 && coordinator.state.value.requests.isEmpty())) {
            check(System.currentTimeMillis() < end) { "refresh did not finish" }
            Thread.sleep(20)
        }
        return upgrade.state.value
    }

    @Test
    fun verifiedFeedMovesToHttpsKeepingIdCategoriesArticlesAndState() = runBlocking<Unit> {
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.url.encodedPath) {
                "/guid" -> ok(rss("Guid feed", listOf("g1" to "http://example.com/1", "g2" to "http://example.com/2")))
                else -> ok(linkOnly("Link feed", listOf("http://example.com/a", "http://example.com/b")))
            }
        }
        https.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.url.encodedPath) {
                "/guid" -> ok(rss("Guid feed", listOf("g1" to "https://example.com/1", "g2" to "https://example.com/2")))
                // Same articles, links now https:// (the entry key is the link): must not become new unread articles.
                else -> ok(linkOnly("Link feed", listOf("https://example.com/a", "https://example.com/b")))
            }
        }
        val oldOpml = opml(httpUrl("/guid"), httpUrl("/links"))
        importOpml(oldOpml)
        backend.refresh(account(), RefreshScope.All, RefreshReason.MANUAL) {}
        val feeds = env.db.subscriptionDao().feeds(account()).associateBy { it.subscriptionUrl }
        val guid = feeds.getValue(httpUrl("/guid"))
        val links = feeds.getValue(httpUrl("/links"))
        val read = env.db.articleDao().articlesOfFeed(links.id).first()
        val saved = env.db.articleDao().articlesOfFeed(guid.id).first()
        env.articles.setRead(read.id, true)
        env.articles.setSaved(saved.id, true)
        val categoriesBefore = env.db.subscriptionDao().memberships(account()).toSet()

        val result = runUpgrade(checker())
        assertEquals(2, result.count(HttpsVerdict.CHANGED))
        assertTrue(result.results.all { it.reason == HttpsReason.OK })
        for (feed in listOf(guid, links)) {
            val after = env.db.subscriptionDao().feed(feed.id)!!
            assertEquals("same feed row", feed.subscriptionUrl, after.subscriptionUrl)
            assertTrue(after.fetchUrl, after.fetchUrl.startsWith("https://"))
            assertEquals(2, env.db.articleDao().articlesOfFeed(feed.id).size)
        }
        assertEquals(categoriesBefore, env.db.subscriptionDao().memberships(account()).toSet())
        assertTrue("fetched over HTTPS after the switch", https.requestCount >= 4)
        assertTrue(env.db.articleDao().state(read.id)!!.isRead)
        assertTrue(env.db.articleDao().state(saved.id)!!.isSaved)
        assertEquals(0, env.articles.unreadIdsSnapshot(
            io.github.yuubiz.folirel.model.ArticleQuerySpec(account(), io.github.yuubiz.folirel.model.ArticleScope.Feed(links.id), io.github.yuubiz.folirel.model.ReadFilter.ALL, io.github.yuubiz.folirel.model.SortOrder.NEWEST),
        ).count { it == read.id })

        // Re-importing the same old OPML: no duplicate, fetch URL stays on HTTPS.
        val reimport = importOpml(oldOpml)
        assertEquals(0, reimport.createdFeeds)
        assertEquals(2, env.db.subscriptionDao().feeds(account()).size)
        assertTrue(env.db.subscriptionDao().feed(guid.id)!!.fetchUrl.startsWith("https://"))
        // Adding / importing the https:// URL itself also finds the same feed.
        val addHttps = env.subscriptions.addFeed(account(), env.db.subscriptionDao().feed(guid.id)!!.fetchUrl)
        assertEquals(io.github.yuubiz.folirel.data.repository.AddFeedResult.AlreadySubscribed(guid.id), addHttps)
        // Export uses the verified https:// URL.
        val exported = env.subscriptions.exportData(account()).first.map { it.subscriptionUrl }
        assertTrue(exported.all { it.startsWith("https://") })
    }

    @Test
    fun htmlTlsDowngradeAndDifferentFeedKeepTheHttpUrl() = runBlocking<Unit> {
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = ok(rss("Original", listOf("o1" to "http://example.com/o1")))
        }
        https.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/html" -> MockResponse.Builder().code(200).setHeader("Content-Type", "text/html").body("<!doctype html><html><body>hi</body></html>").build()
                "/down" -> MockResponse.Builder().code(301).setHeader("Location", httpUrl("/down")).build()
                "/moved" -> MockResponse.Builder().code(301).setHeader("Location", "/elsewhere").build()
                "/elsewhere" -> ok(rss("Another site", listOf("x1" to "https://other.example/x1")))
                "/gone" -> MockResponse.Builder().code(404).build()
                else -> ok(rss("Original", listOf("o1" to "https://example.com/o1")))
            }
        }
        importOpml(opml(httpUrl("/html"), httpUrl("/tls"), httpUrl("/down"), httpUrl("/moved"), httpUrl("/gone")))
        backend.refresh(account(), RefreshScope.All, RefreshReason.MANUAL) {}
        val before = env.db.subscriptionDao().feeds(account()).associate { it.id to it.fetchUrl }
        val downBefore = http.requestCount

        val result = runUpgrade(checker(httpAllowed = true))
        val reasons = result.results.associate { it.httpUrl.substringAfterLast('/') to it.reason }
        assertEquals(HttpsReason.HTML_PAGE, reasons["html"])
        assertEquals(HttpsReason.TLS_ERROR, reasons["tls"])
        assertEquals(HttpsReason.DOWNGRADE_TO_HTTP, reasons["down"])
        assertEquals(HttpsReason.DIFFERENT_FEED, reasons["moved"])
        assertEquals(HttpsReason.HTTP_STATUS, reasons["gone"])
        assertTrue(result.results.all { it.verdict == HttpsVerdict.UNCHANGED })
        assertEquals("every URL kept", before, env.db.subscriptionDao().feeds(account()).associate { it.id to it.fetchUrl })
        // HTTP was contacted only to compare the redirected feed ("moved"), never by following a downgrade.
        assertEquals(downBefore + 1, http.requestCount)
    }

    @Test
    fun crossSiteRedirectIsUnconfirmedWithoutHttpAndConfirmedByTheHttpDocument() = runBlocking<Unit> {
        // Never fetched before (HTTP was off): no history to compare with.
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(301).setHeader("Location", httpsUrl("/new/feed.xml")).build()
        }
        https.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/old/feed.xml" -> MockResponse.Builder().code(301).setHeader("Location", "/new/feed.xml").build()
                else -> ok(rss("Renamed news", listOf("n1" to "https://example.com/n1")))
            }
        }
        importOpml(opml(httpUrl("/old/feed.xml")))
        val unconfirmed = runUpgrade(checker(httpAllowed = false)).results.single()
        assertEquals(HttpsVerdict.UNCHECKED, unconfirmed.verdict)
        assertEquals(HttpsReason.REDIRECT_UNCONFIRMED, unconfirmed.reason)
        assertEquals(0, http.requestCount)
        assertTrue(env.db.subscriptionDao().feeds(account()).single().fetchUrl.startsWith("http://"))

        // With HTTP allowed, the HTTP URL itself redirects to the same HTTPS document: same feed.
        val confirmed = runUpgrade(checker(httpAllowed = true)).results.single()
        assertEquals(HttpsVerdict.CHANGED, confirmed.verdict)
        assertTrue(env.db.subscriptionDao().feeds(account()).single().fetchUrl.startsWith("https://"))
    }

    @Test
    fun timeoutIsNotCheckedAndCancelKeepsTheRest() = runBlocking<Unit> {
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = ok(rss("t", emptyList()))
        }
        https.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath.startsWith("/slow")) Thread.sleep(1_500)
                return ok(rss("t", emptyList()))
            }
        }
        importOpml(opml(httpUrl("/slow1")))
        val timedOut = runUpgrade(checker(timeoutMillis = 300))
        assertEquals(HttpsVerdict.UNCHECKED, timedOut.results.single().verdict)
        assertEquals(HttpsReason.TIMEOUT, timedOut.results.single().reason)
        assertTrue(env.db.subscriptionDao().feeds(account()).single().fetchUrl.startsWith("http://"))

        importOpml(opml(httpUrl("/slow2"), httpUrl("/slow3"), httpUrl("/slow4"), httpUrl("/slow5"), httpUrl("/slow6")))
        val cancelled = runUpgrade(checker(), cancelAfterMillis = 300)
        assertTrue(cancelled.cancelled)
        assertEquals(6, cancelled.results.size)
        assertTrue(cancelled.results.any { it.reason == HttpsReason.CANCELLED && it.verdict == HttpsVerdict.UNCHECKED })
        val urls = env.db.subscriptionDao().feeds(account()).map { it.fetchUrl }
        val changed = cancelled.results.filter { it.verdict == HttpsVerdict.CHANGED }.map { it.feedId }.toSet()
        for (feed in env.db.subscriptionDao().feeds(account())) {
            assertEquals(feed.id in changed, feed.fetchUrl.startsWith("https://"))
        }
        assertEquals(6, urls.size)
    }
}
