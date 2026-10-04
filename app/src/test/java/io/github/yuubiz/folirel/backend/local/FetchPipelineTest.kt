package io.github.yuubiz.folirel.backend.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.data.db.AppDatabase
import io.github.yuubiz.folirel.model.ArticleQuerySpec
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import io.github.yuubiz.folirel.testutil.Fixtures
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** M1 vertical path through the real network stack: T11, T16, T18, T23 (format failure), persistence. */
@RunWith(AndroidJUnit4::class)
class FetchPipelineTest {
    private val server: MockWebServer = TestEnv.startServer()
    private val env = TestEnv()

    @After
    fun tearDown() {
        env.close()
        server.close()
    }

    private fun xml(path: String, contentType: String = "application/rss+xml") =
        MockResponse.Builder().code(200).setHeader("Content-Type", contentType).body(Buffer().write(Fixtures.bytes(path))).build()

    private fun opmlFor(vararg urls: String) = buildString {
        append("<opml version=\"2.0\"><body><outline text=\"Cat\">")
        urls.forEachIndexed { i, u -> append("<outline text=\"F$i\" xmlUrl=\"$u\"/>") }
        append("</outline></body></opml>")
    }.toByteArray()

    private fun spec(account: String, filter: ReadFilter = ReadFilter.ALL) =
        ArticleQuerySpec(account, ArticleScope.All, filter, SortOrder.NEWEST)

    @Test
    fun opmlImportThenFetchStoresParsedArticles() = runTest {
        val feedUrl = server.url("/alpha.xml").toString()
        server.enqueue(xml("feeds/rss2-basic.xml"))
        val account = env.subscriptions.ensureLocalAccount()
        val doc = OpmlParser.parse(opmlFor(feedUrl))
        env.subscriptions.previewImport(account, doc, allowHttp = true)
        assertEquals("preview must not touch the network", 0, server.requestCount)
        val imported = env.subscriptions.commitImport(account, doc)
        assertEquals(0, server.requestCount)

        val report = env.backend.refresh(account, RefreshScope.Feeds(imported.feedIdsToFetch), RefreshReason.IMPORT) {}
        assertEquals(1, report.updated)
        assertEquals(2, report.newArticles)
        assertTrue(report.isComplete)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.headers["User-Agent"]!!.startsWith("Folirel"))
        assertNull(request.headers["Cookie"])

        val rows = env.articles.rows(spec(account))
        assertEquals(2, rows.size)
        val first = rows.first { it.title == "日本語の見出し & RSS" }
        assertEquals("https://example.com/posts/1?x=1&x=2", first.linkUrl)
        assertEquals("https://example.com/thumb.jpg", first.imageUrl)
        assertEquals("段落 太字。", first.summaryText)
        assertEquals(Instant.parse("2026-10-03T00:00:00Z").toEpochMilli(), first.publishedAt)
        assertFalse(first.isRead)
        assertFalse(first.isSaved)
        // Undated entry falls back to firstSeenAt for ordering.
        val undated = rows.first { it.title == "日付とURLなし" }
        assertNull(undated.publishedAt)
        assertEquals(env.clock.millis(), undated.sortAt)
        assertEquals("架空の技術ニュース", env.db.subscriptionDao().feeds(account).single().feedTitle)
    }

    @Test
    fun updatedEntryKeepsIdentityAndManualState() = runTest {
        val feedUrl = server.url("/alpha.xml").toString()
        server.enqueue(xml("feeds/rss2-basic.xml"))
        server.enqueue(xml("feeds/rss2-updated.xml"))
        val account = env.subscriptions.ensureLocalAccount()
        val feedId = env.subscriptions.commitImport(account, OpmlParser.parse(opmlFor(feedUrl))).feedIdsToFetch.single()
        env.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {}
        val dao = env.db.articleDao()
        val before = dao.articlesOfFeed(feedId).first { it.entryKey == "id:alpha-001" }
        env.articles.setSaved(before.id, true)
        env.articles.setRead(before.id, true)

        env.clock.advanceMinutes(90)
        val report = env.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {}
        assertEquals(0, report.newArticles)
        val after = dao.articlesOfFeed(feedId).first { it.entryKey == "id:alpha-001" }
        assertEquals("更新された日本語の見出し", after.title)
        assertEquals("修正版の概要", after.summaryText)
        assertEquals(before.id, after.id)
        assertEquals(before.firstSeenAt, after.firstSeenAt)
        assertEquals(before.sortAt, after.sortAt) // publishedAt changed, sortAt does not
        assertEquals(Instant.parse("2026-10-03T01:00:00Z").toEpochMilli(), after.publishedAt)
        val state = dao.state(after.id)!!
        assertTrue(state.isSaved)
        assertTrue(state.isRead)
        // The entry missing from the second response is kept (no automatic deletion).
        assertEquals(2, dao.articlesOfFeed(feedId).size)
    }

    @Test
    fun sameGuidInAnotherFeedIsAnotherArticle() = runTest {
        server.enqueue(xml("feeds/rss2-basic.xml"))
        server.enqueue(xml("feeds/rss2-basic.xml"))
        val account = env.subscriptions.ensureLocalAccount()
        env.subscriptions.commitImport(account, OpmlParser.parse(opmlFor(server.url("/a.xml").toString(), server.url("/b.xml").toString())))
        env.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {}
        val alphaRows = env.articles.rows(spec(account)).filter { it.title == "日本語の見出し & RSS" }
        assertEquals(2, alphaRows.size)
        assertEquals(2, alphaRows.map { it.feedId }.toSet().size)
    }

    @Test
    fun futureDatesBeyond24hSortByReceiveTime() = runTest {
        val now = env.clock.instant()
        val near = now.plusSeconds(23 * 3600)
        val far = now.plusSeconds(25 * 3600)
        fun rfc(i: Instant) = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(i.atOffset(java.time.ZoneOffset.UTC))
        val body = """<rss version="2.0"><channel><title>t</title>
            <item><guid>near</guid><title>near</title><pubDate>${rfc(near)}</pubDate></item>
            <item><guid>far</guid><title>far</title><pubDate>${rfc(far)}</pubDate></item>
            <item><guid>old</guid><title>old</title><pubDate>Mon, 01 Jan 1990 00:00:00 GMT</pubDate></item>
            </channel></rss>"""
        server.enqueue(MockResponse.Builder().body(body).build())
        val account = env.subscriptions.ensureLocalAccount()
        val feedId = env.subscriptions.commitImport(account, OpmlParser.parse(opmlFor(server.url("/f.xml").toString()))).feedIdsToFetch.single()
        env.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {}
        val byKey = env.db.articleDao().articlesOfFeed(feedId).associateBy { it.entryKey }
        assertEquals(near.toEpochMilli(), byKey.getValue("id:near").sortAt)
        assertEquals(now.toEpochMilli(), byKey.getValue("id:far").sortAt)
        assertEquals(far.toEpochMilli(), byKey.getValue("id:far").publishedAt)
        assertEquals(Instant.parse("1990-01-01T00:00:00Z").toEpochMilli(), byKey.getValue("id:old").sortAt)
    }

    @Test
    fun malformedResponseKeepsPreviousArticlesAndValidators() = runTest {
        server.enqueue(xml("feeds/rss2-basic.xml").newBuilder().setHeader("ETag", "\"v1\"").build())
        server.enqueue(xml("feeds/malformed.xml"))
        val account = env.subscriptions.ensureLocalAccount()
        val feedId = env.subscriptions.commitImport(account, OpmlParser.parse(opmlFor(server.url("/f.xml").toString()))).feedIdsToFetch.single()
        env.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {}
        val before = env.db.articleDao().articlesOfFeed(feedId)
        val stateBefore = env.db.subscriptionDao().fetchState(feedId)!!

        env.clock.advanceMinutes(60)
        val report = env.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {}
        assertEquals(mapOf(feedId to FetchError.FORMAT), report.failed)
        assertFalse(report.isComplete)
        assertEquals(before, env.db.articleDao().articlesOfFeed(feedId))
        val state = env.db.subscriptionDao().fetchState(feedId)!!
        assertEquals("\"v1\"", state.etag)
        assertEquals(stateBefore.lastSuccessAt, state.lastSuccessAt)
        assertEquals(FetchError.FORMAT.name, state.lastErrorCode)
        assertEquals(1, state.failureCount)
        assertNotNull(state.nextEligibleAt)
    }

    @Test
    fun httpCharsetIsUsedOnlyWithoutBomOrDeclaration() = runTest {
        // No XML declaration: the Content-Type charset decides (EUC-JP).
        val eucJp = "<rss version=\"2.0\"><channel><title>EUC配信</title><item><guid>e1</guid><title>日本語のEUC-JP記事</title></item></channel></rss>"
        val eucResponse = MockResponse.Builder().setHeader("Content-Type", "application/rss+xml; charset=EUC-JP").body(Buffer().write(eucJp.toByteArray(charset("EUC-JP")))).build()
        // Declared Shift_JIS wins over a wrong header charset.
        val sjisResponse = xml("feeds/rss2-shiftjis.xml", "text/xml; charset=utf-8")
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest) = if (request.url.encodedPath == "/euc") eucResponse else sjisResponse
        }
        val account = env.subscriptions.ensureLocalAccount()
        val (euc, sjis) = env.subscriptions.commitImport(account, OpmlParser.parse(opmlFor(server.url("/euc").toString(), server.url("/sjis").toString()))).feedIdsToFetch
        val report = env.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {}
        assertEquals(2, report.updated)
        assertEquals("日本語のEUC-JP記事", env.db.articleDao().articlesOfFeed(euc).single().title)
        assertEquals("日本語の文字化けを確認", env.db.articleDao().articlesOfFeed(sjis).single().title)
        assertEquals("日本語配信", env.db.subscriptionDao().feed(sjis)!!.feedTitle)
    }

    @Test
    fun dataSurvivesDatabaseReopen() = runTest {
        val context = TestEnv.context()
        val name = "reopen-test.db"
        context.deleteDatabase(name)
        val fileDb = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val fileEnv = TestEnv(db = fileDb)
        server.enqueue(xml("feeds/rss2-basic.xml"))
        val account = fileEnv.subscriptions.ensureLocalAccount()
        fileEnv.subscriptions.commitImport(account, OpmlParser.parse(opmlFor(server.url("/f.xml").toString())))
        fileEnv.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {}
        val saved = fileEnv.articles.rows(spec(account)).first()
        fileEnv.articles.setSaved(saved.id, true)
        fileEnv.close()

        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val reopenedEnv = TestEnv(db = reopened)
        assertEquals(account, reopenedEnv.subscriptions.ensureLocalAccount())
        val rows = reopenedEnv.articles.rows(spec(account, ReadFilter.SAVED))
        assertEquals(listOf(saved.id), rows.map { it.id })
        assertEquals(1, reopened.subscriptionDao().categories(account).size)
        reopenedEnv.close()
        context.deleteDatabase(name)
    }
}
