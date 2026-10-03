package io.github.yuubiz.feeddeck.backend.local.discovery

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.feeddeck.backend.local.SmallResourceFetcher
import io.github.yuubiz.feeddeck.data.repository.AddFeedResult
import io.github.yuubiz.feeddeck.data.repository.AttemptInfo
import io.github.yuubiz.feeddeck.data.repository.BodyStats
import io.github.yuubiz.feeddeck.data.repository.FetchMetadata
import io.github.yuubiz.feeddeck.data.repository.SubscriptionRepository.MoveResult
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlParser
import io.github.yuubiz.feeddeck.model.CandidateVia
import io.github.yuubiz.feeddeck.model.DiscoveryResult
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.model.NormalizedEntry
import io.github.yuubiz.feeddeck.model.NormalizedFeed
import io.github.yuubiz.feeddeck.sync.FeedDiscoveryService
import io.github.yuubiz.feeddeck.testutil.TestEnv
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** U13 / U14: moved-feed discovery on the site, and applying a candidate only after approval. */
@RunWith(AndroidJUnit4::class)
class FeedDiscoveryTest {
    private val server: MockWebServer = TestEnv.startServer()
    private val env = TestEnv()
    private val paths = CopyOnWriteArrayList<String>()
    private var routes: Map<String, () -> MockResponse> = emptyMap()

    @After
    fun tearDown() {
        env.close()
        server.close()
    }

    private fun serve(vararg r: Pair<String, () -> MockResponse>) {
        routes = r.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.url.encodedPath
                return routes[request.url.encodedPath]?.invoke() ?: MockResponse.Builder().code(404).build()
            }
        }
    }

    private fun url(path: String) = server.url(path).toString()

    private fun html(body: String) = { MockResponse.Builder().code(200).setHeader("Content-Type", "text/html; charset=utf-8").body(body).build() }

    private fun rss(title: String, vararg links: String) = {
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/rss+xml").body(
            "<rss version=\"2.0\"><channel><title>$title</title><link>https://site.example/</link>" +
                links.joinToString("") { "<item><guid>$it</guid><title>t</title><link>$it</link><pubDate>Sat, 03 Oct 2026 00:00:00 GMT</pubDate></item>" } +
                "</channel></rss>",
        ).build()
    }

    private val discovery get() = FeedDiscovery(SmallResourceFetcher(env.httpClient), env.fetcher)

    private fun known(site: String?, title: String? = "My Blog", keys: Set<String> = emptySet(), links: List<String> = emptyList()) =
        FeedDiscovery.Known(fetchUrl = url("/old-feed.xml"), siteUrl = site, feedTitle = title, entryKeys = keys, articleLinks = links)

    @Test
    fun listsFeedLinksOfTheSitePageWithFactsAndPicksNothing() = runBlocking<Unit> {
        serve(
            "/" to html(
                """<html><head>
                   <link rel="alternate" type="application/rss+xml" title="Posts" href="/new.xml">
                   <link rel="alternate" type="application/atom+xml" href="/broken.xml">
                   <link rel="alternate" type="application/rss+xml" href="/page.xml">
                   <link rel="alternate" type="application/rss+xml" href="/missing.xml">
                   </head></html>""",
            ),
            "/new.xml" to rss("My Blog", "https://site.example/a", "https://site.example/b"),
            "/broken.xml" to { MockResponse.Builder().code(200).body("<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>x</title>").build() },
            "/page.xml" to html("<html><body>This is a web page, not a feed</body></html>"),
        )
        val result = discovery.discover(known(url("/"), keys = setOf("url:http://site.example/a")))
        assertEquals(DiscoveryResult.PageStatus.READ, result.page)
        val byPath = result.candidates.associateBy { it.url.substringAfter(server.port.toString()) }
        assertEquals(4, result.candidates.size)
        val good = byPath.getValue("/new.xml")
        assertTrue(good.fetchable)
        assertEquals("My Blog", good.feedTitle)
        assertEquals(2, good.entryCount)
        assertEquals(1, good.sharedEntries) // a: the same article, http vs https alike
        assertTrue(good.sameTitle)
        assertEquals("Posts", good.titleOnPage)
        assertEquals(CandidateVia.SITE_PAGE, good.via)
        // Broken XML, an HTML page and a 404 are never usable, each with its reason.
        assertFalse(byPath.getValue("/broken.xml").fetchable)
        assertEquals(FetchError.FORMAT, byPath.getValue("/broken.xml").failure)
        assertFalse(byPath.getValue("/page.xml").fetchable)
        assertEquals(FetchError.NOT_A_FEED, byPath.getValue("/page.xml").failure)
        assertEquals(FetchError.NOT_FOUND, byPath.getValue("/missing.xml").failure)
        // The best candidate comes first, but nothing was applied or chosen.
        assertEquals("/new.xml", result.candidates.first().url.substringAfter(server.port.toString()))
    }

    @Test
    fun anUnrelatedSiteWithTheSameTitleIsOnlyWeakEvidence() = runBlocking<Unit> {
        serve(
            "/" to html("""<html><head><link rel="alternate" type="application/rss+xml" href="/f.xml"></head></html>"""),
            "/f.xml" to rss("My Blog", "https://elsewhere.example/1"),
        )
        val c = discovery.discover(known(url("/"), keys = setOf("url:https://site.example/a"))).candidates.single()
        assertTrue(c.sameTitle)
        assertEquals("no article in common: not treated as the same", 0, c.sharedEntries)
    }

    @Test
    fun theCurrentFeedAddressIsNotOfferedAgainAndPlainPagesYieldNothing() = runBlocking<Unit> {
        serve(
            "/" to html("""<html><head><link rel="alternate" type="application/rss+xml" href="/old-feed.xml"></head></html>"""),
            "/old-feed.xml" to rss("My Blog"),
        )
        val result = discovery.discover(known(url("/")))
        assertEquals(emptyList<Any>(), result.candidates)
        serve("/" to html("<html><head><title>nothing here</title></head></html>"))
        assertEquals(emptyList<Any>(), discovery.discover(known(url("/"))).candidates)
    }

    @Test
    fun anEnteredFeedAddressIsCheckedAsACandidateAndAnEnteredPageIsReadForLinks() = runBlocking<Unit> {
        serve(
            "/direct.xml" to rss("Direct", "https://site.example/z"),
            "/site/" to html("""<html><head><link rel="alternate" type="application/atom+xml" href="/direct.xml"></head></html>"""),
        )
        val direct = discovery.discover(known(null), enteredUrl = url("/direct.xml"))
        assertEquals(DiscoveryResult.PageStatus.FEED_ENTERED, direct.page)
        assertEquals(CandidateVia.ENTERED_URL, direct.candidates.single().via)
        assertTrue(direct.candidates.single().fetchable)
        val page = discovery.discover(known(null), enteredUrl = url("/site/"))
        assertEquals(DiscoveryResult.PageStatus.READ, page.page)
        assertTrue(page.candidates.single().fetchable)
    }

    @Test
    fun problemsAreReportedNotGuessed() = runBlocking<Unit> {
        assertEquals(DiscoveryResult.PageStatus.NO_SITE, discovery.discover(known(null)).page)
        serve("/" to { MockResponse.Builder().code(500).build() })
        assertEquals(DiscoveryResult.PageStatus.UNREADABLE, discovery.discover(known(url("/"))).page)
        assertEquals(DiscoveryResult.PageStatus.UNREADABLE, discovery.discover(known(url("/")), enteredUrl = "not a url").page)
        env.allowHttp.set(false)
        paths.clear()
        assertEquals(DiscoveryResult.PageStatus.HTTP_NOT_ALLOWED, discovery.discover(known(url("/"))).page)
        assertTrue("nothing was sent", paths.isEmpty())
        // Without a known site, the articles name it (never the feed host).
        env.allowHttp.set(true)
        serve("/" to html("""<html><head><link rel="alternate" type="application/rss+xml" href="/n.xml"></head></html>"""), "/n.xml" to rss("t"))
        val viaArticles = discovery.discover(known(null, links = listOf(url("/a"), url("/b"))))
        assertEquals(1, viaArticles.candidates.size)
    }

    /** A redirect loop on a candidate is unusable, not a hang. */
    @Test
    fun redirectLoopsAreNotAdopted() = runBlocking<Unit> {
        serve(
            "/" to html("""<html><head><link rel="alternate" type="application/rss+xml" href="/loop.xml"></head></html>"""),
            "/loop.xml" to { MockResponse.Builder().code(302).setHeader("Location", "/loop.xml").build() },
        )
        val c = discovery.discover(known(url("/"))).candidates.single()
        assertFalse(c.fetchable)
        assertEquals(FetchError.REDIRECT_LOOP, c.failure)
    }


    // V3-R05: what a page is follows from its root element, not from a leading "<?xml".

    private val bom = "﻿"
    private val alternate = """<link rel="alternate" type="application/rss+xml" href="/new.xml"/>"""

    private fun page(body: String, type: String) = { MockResponse.Builder().code(200).setHeader("Content-Type", type).body(body).build() }

    private suspend fun readPage(vararg route: Pair<String, () -> MockResponse>): DiscoveryResult {
        serve("/new.xml" to rss("My Blog", "https://site.example/a"), *route)
        return discovery.discover(known(url("/")))
    }

    @Test
    fun anXhtmlSiteWithAnXmlDeclarationIsReadForItsFeedLinks() = runBlocking<Unit> {
        val xhtml = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Blog</title>$alternate</head><body>hello</body></html>"""
        for (type in listOf("application/xhtml+xml; charset=utf-8", "text/html; charset=utf-8", "application/xml")) {
            val result = readPage("/" to page(xhtml, type))
            assertEquals(type, DiscoveryResult.PageStatus.READ, result.page)
            assertEquals(type, CandidateVia.SITE_PAGE, result.candidates.single().via)
            assertTrue(type, result.candidates.single().fetchable)
        }
    }

    @Test
    fun bomCommentsAndProcessingInstructionsBeforeTheRootDoNotChangeWhatItIs() = runBlocking<Unit> {
        val xhtml = bom + """<?xml version="1.0"?><!-- generated --><?xml-stylesheet href="s.css"?>
            <!-- <rss> in a comment is not the root -->
            <html xmlns="http://www.w3.org/1999/xhtml"><head>$alternate</head></html>"""
        assertEquals(DiscoveryResult.PageStatus.READ, readPage("/" to page(xhtml, "text/html")).page)
        val feed = bom + """<?xml version="1.0"?><!-- generated --><!-- <html> in a comment is not the root -->
            <rss version="2.0"><channel><title>My Blog</title></channel></rss>"""
        val entered = readPage("/" to page(feed, "text/html"))
        assertEquals("a feed served with a wrong type is still a feed", DiscoveryResult.PageStatus.FEED_ENTERED, entered.page)
    }

    @Test
    fun plainHtmlRealFeedsAndErrorPagesAreToldApart() = runBlocking<Unit> {
        val html = """<!DOCTYPE html><html><head>$alternate</head><body></body></html>"""
        assertEquals(DiscoveryResult.PageStatus.READ, readPage("/" to page(html, "text/html")).page)
        val feeds = mapOf(
            "rss" to """<?xml version="1.0"?><rss version="2.0"><channel><title>My Blog</title></channel></rss>""",
            "atom" to """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>My Blog</title></feed>""",
            "rdf" to """<?xml version="1.0"?><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/"><channel rdf:about="x"><title>My Blog</title></channel></rdf:RDF>""",
            "atom with prefix" to """<?xml version="1.0"?><a:feed xmlns:a="http://www.w3.org/2005/Atom"><a:title>My Blog</a:title></a:feed>""",
        )
        for ((name, body) in feeds) {
            for (type in listOf("application/xml", "text/html")) {
                val result = readPage("/" to page(body, type))
                assertEquals("$name as $type", DiscoveryResult.PageStatus.FEED_ENTERED, result.page)
                assertEquals("$name as $type", CandidateVia.ENTERED_URL, result.candidates.single().via)
            }
        }
        // An HTML error page with a 200: read as a page, no link, no candidate.
        val error = readPage("/" to page("<html><head><title>Not found</title></head><body>No such page</body></html>", "text/html"))
        assertEquals(DiscoveryResult.PageStatus.READ, error.page)
        assertEquals(emptyList<Any>(), error.candidates)
    }

    @Test
    fun candidatesRememberTheAddressTheyWereFoundFor() = runBlocking<Unit> {
        val result = readPage("/" to page("<html><head>$alternate</head></html>", "text/html"))
        assertEquals(url("/old-feed.xml"), result.sourceFetchUrl)
    }

    @Test
    fun aCandidateFoundForAnOlderAddressIsRefusedByTheRepository() = runBlocking<Unit> {
        val id = subscribe("https://a.example.com/f")
        // The discovery ran for a.example.com; meanwhile the feed moved on another way.
        assertEquals(MoveResult.Applied, env.subscriptions.applyUrlMove(id, "https://a.example.com/f", "https://b.example.com/f"))
        assertEquals(MoveResult.Stale, env.subscriptions.applyUrlMove(id, "https://a.example.com/f", "https://c.example.com/f"))
        assertEquals("https://b.example.com/f", env.db.subscriptionDao().feed(id)!!.fetchUrl)
    }

    // U14

    private suspend fun subscribe(fetchUrl: String): String =
        (env.subscriptions.addFeed(env.subscriptions.ensureLocalAccount(), fetchUrl) as AddFeedResult.Added).feedId

    @Test
    fun applyingAMoveKeepsIdentityStateAndOldOpmlMatches() = runBlocking<Unit> {
        val account = env.subscriptions.ensureLocalAccount()
        val old = "https://old.example.com/feed.xml"
        val new = "https://new.example.com/rss"
        val id = subscribe(old)
        env.store.applySuccess(
            id, NormalizedFeed("t", null, listOf(NormalizedEntry("url:https://a/1", "t", "https://a/1", "", null, 1L, null))),
            FetchMetadata(null, null, old, null), BodyStats(0, 1), AttemptInfo(200, false),
        )
        val article = env.db.articleDao().articlesOfFeed(id).single()
        env.articles.setSaved(article.id, true)
        env.store.recordFailure(id, FetchError.NOT_FOUND, null, AttemptInfo(404, false))
        assertEquals("the lookup alone changes nothing", old, env.db.subscriptionDao().feed(id)!!.fetchUrl)

        assertEquals(MoveResult.Applied, env.subscriptions.applyUrlMove(id, old, new))
        val feed = env.db.subscriptionDao().feed(id)!!
        assertEquals(new, feed.fetchUrl)
        assertEquals("the registered URL stays the OPML identity", old, feed.subscriptionUrl)
        assertEquals(id, feed.id)
        assertTrue(env.db.articleDao().state(article.id)!!.isSaved)
        // The failure of the old address no longer blocks the new one.
        val state = env.db.subscriptionDao().fetchState(id)!!
        assertNull(state.lastErrorCode)
        assertEquals(0, state.failureCount)
        assertEquals(1, env.db.subscriptionDao().urlMoves(account).size)

        // The old OPML, the new address and the export all point at this one feed: no duplicate.
        for (u in listOf(old, new)) {
            val doc = OpmlParser.parse("<opml version=\"2.0\"><body><outline text=\"f\" xmlUrl=\"$u\"/></body></opml>".toByteArray())
            val preview = env.subscriptions.previewImport(account, doc, allowHttp = false)
            assertEquals(u, 0, preview.newFeeds)
            assertEquals(u, 0, env.subscriptions.commitImport(account, doc).createdFeeds)
        }
        assertEquals(1, env.db.subscriptionDao().feeds(account).size)
        val (exported, _) = env.subscriptions.exportData(account)
        assertEquals("export names the current address", new, exported.single().subscriptionUrl)

        // A second move (new -> newer) keeps both earlier addresses mapped.
        val newer = "https://newest.example.com/atom"
        assertEquals(MoveResult.Applied, env.subscriptions.applyUrlMove(id, new, newer))
        val mid = OpmlParser.parse("<opml version=\"2.0\"><body><outline text=\"f\" xmlUrl=\"$new\"/></body></opml>".toByteArray())
        assertEquals(0, env.subscriptions.previewImport(account, mid, allowHttp = false).newFeeds)
    }

    @Test
    fun aMoveOntoAnotherSubscriptionOrAStaleOneIsRefusedWithoutChanges() = runBlocking<Unit> {
        val a = subscribe("https://a.example.com/f")
        val b = subscribe("https://b.example.com/f")
        val conflict = env.subscriptions.applyUrlMove(a, "https://a.example.com/f", "https://b.example.com/f")
        assertTrue(conflict is MoveResult.Conflict && conflict.feedId == b)
        assertEquals("https://a.example.com/f", env.db.subscriptionDao().feed(a)!!.fetchUrl)
        assertEquals(MoveResult.Stale, env.subscriptions.applyUrlMove(a, "https://not-current.example.com/f", "https://c.example.com/f"))
        assertEquals(MoveResult.Invalid, env.subscriptions.applyUrlMove(a, "https://a.example.com/f", "ftp://c.example.com/f"))
        assertEquals(MoveResult.Invalid, env.subscriptions.applyUrlMove(a, "https://a.example.com/f", "https://a.example.com/f"))
        env.subscriptions.unsubscribe(a)
        assertEquals(MoveResult.Stale, env.subscriptions.applyUrlMove(a, "https://a.example.com/f", "https://c.example.com/f"))
        assertEquals(0, env.db.subscriptionDao().urlMoves(env.subscriptions.ensureLocalAccount()).size)
    }

    @Test
    fun theServiceReadsTheSubscribedFeedsOwnSite() = runBlocking<Unit> {
        serve(
            "/" to html("""<html><head><link rel="alternate" type="application/rss+xml" href="/moved.xml"></head></html>"""),
            "/moved.xml" to rss("Old Title", "https://site.example/a"),
        )
        val id = subscribe("https://old.example.com/feed.xml")
        env.store.applySuccess(
            id,
            NormalizedFeed("Old Title", url("/"), listOf(NormalizedEntry("url:https://site.example/a", "t", "https://site.example/a", "", null, 1L, null))),
            FetchMetadata(null, null, "https://old.example.com/feed.xml", null), BodyStats(0, 1), AttemptInfo(200, false),
        )
        val service = FeedDiscoveryService(discovery, env.subscriptions)
        val result = service.run(id)!!
        val c = result.candidates.single()
        assertTrue(c.fetchable)
        assertEquals(1, c.sharedEntries)
        assertTrue(c.sameTitle)
        assertEquals("Old Title", service.searchName(id))
        assertNull(service.run("no-such-feed"))
    }
}
