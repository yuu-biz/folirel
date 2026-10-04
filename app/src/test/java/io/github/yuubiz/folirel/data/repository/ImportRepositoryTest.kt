package io.github.yuubiz.folirel.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.backend.local.opml.OpmlRejectedException
import io.github.yuubiz.folirel.testutil.Fixtures
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** T02 (DB level), T03, T04, T05 (registration), T09 (merge/resubscribe). */
@RunWith(AndroidJUnit4::class)
class ImportRepositoryTest {
    private val env = TestEnv()
    private val dao = env.db.subscriptionDao()

    @After
    fun tearDown() = env.close()

    private suspend fun counts(accountId: String) = Triple(
        dao.feeds(accountId).size,
        dao.categories(accountId).size,
        dao.memberships(accountId).size,
    )

    @Test
    fun subscriptionsOpmlPreviewAndCommit() = runTest {
        val account = env.subscriptions.ensureLocalAccount()
        val doc = OpmlParser.parse(Fixtures.bytes("opml/subscriptions.opml"))
        val preview = env.subscriptions.previewImport(account, doc, allowHttp = false)
        assertEquals(4, preview.totalUniqueFeeds)
        assertEquals(4, preview.newFeeds)
        assertEquals(4, preview.addedMemberships)
        assertEquals(1, preview.multiCategoryFeeds) // alpha in Tech/AI and News
        assertEquals(4, preview.newCategories)
        assertEquals(0, preview.invalidUrls)
        // Preview writes nothing.
        assertEquals(Triple(0, 0, 0), counts(account))

        val result = env.subscriptions.commitImport(account, doc)
        assertEquals(4, result.createdFeeds)
        assertEquals(4, result.addedMemberships)
        assertEquals(4, result.createdCategories)
        assertEquals(4, result.feedIdsToFetch.size)

        val feeds = dao.feeds(account).associateBy { it.subscriptionUrl }
        val categories = dao.categories(account).associate { it.id to CategoryPaths.decode(it.pathSegmentsJson) }
        val memberships = dao.memberships(account).map { feeds.values.first { f -> f.id == it.feedId }.subscriptionUrl to categories.getValue(it.categoryId) }.toSet()
        assertEquals(
            setOf(
                "https://example.com/alpha.xml" to listOf("Tech", "AI"),
                "https://example.com/alpha.xml" to listOf("News"),
                "https://example.org/beta.atom" to listOf("Tech", "Tools"),
                "https://example.net/gamma.rdf" to listOf("News"),
            ),
            memberships,
        )
        // First label wins; unfiled feed has no membership (未分類 is computed, not a category).
        assertEquals("Alpha", feeds.getValue("https://example.com/alpha.xml").userTitle)
        assertTrue(dao.memberships(account).none { it.feedId == feeds.getValue("https://example.com/unfiled.xml").id })
        assertTrue(categories.values.none { it.contains("未分類") })
    }

    @Test
    fun reimportMergesWithoutDuplicatesAndKeepsUserData() = runTest {
        val account = env.subscriptions.ensureLocalAccount()
        val doc = OpmlParser.parse(Fixtures.bytes("opml/subscriptions.opml"))
        env.subscriptions.commitImport(account, doc)
        val alpha = dao.feedByUrl(account, "https://example.com/alpha.xml")!!
        env.subscriptions.renameFeed(alpha.id, "私の名前")
        env.store.applySuccess(
            alpha.id,
            io.github.yuubiz.folirel.model.NormalizedFeed(null, null, listOf(io.github.yuubiz.folirel.model.NormalizedEntry("id:a", "t", null, "", null, null, null))),
            FetchMetadata(null, null, "https://example.com/alpha.xml", null),
        )
        val articleId = env.db.articleDao().articlesOfFeed(alpha.id).single().id
        env.articles.setRead(articleId, true)
        env.articles.setSaved(articleId, true)

        val again = env.subscriptions.previewImport(account, doc, allowHttp = false)
        assertEquals(0, again.newFeeds)
        assertEquals(4, again.existingFeeds)
        assertEquals(0, again.addedMemberships)
        val second = env.subscriptions.commitImport(account, doc)
        assertEquals(0, second.createdFeeds)
        assertEquals(0, second.addedMemberships)
        assertEquals(4, dao.feeds(account).size)
        assertEquals("私の名前", dao.feed(alpha.id)!!.userTitle)

        // A different file only adds the new membership.
        val other = """<opml version="2.0"><body><outline text="Extra"><outline text="Other label" xmlUrl="HTTPS://EXAMPLE.COM:443/alpha.xml"/></outline></body></opml>"""
        val result = env.subscriptions.commitImport(account, OpmlParser.parse(other.toByteArray()))
        assertEquals(0, result.createdFeeds)
        assertEquals(1, result.addedMemberships)
        assertEquals(5, dao.memberships(account).size)
        assertEquals("私の名前", dao.feed(alpha.id)!!.userTitle)
        val state = env.db.articleDao().state(articleId)!!
        assertTrue("read/saved survive re-import", state.isRead && state.isSaved)
        assertEquals(1, env.db.articleDao().articlesOfFeed(alpha.id).size)
    }

    @Test
    fun unsubscribedFeedIsResubscribedWithSameId() = runTest {
        val account = env.subscriptions.ensureLocalAccount()
        val doc = OpmlParser.parse(Fixtures.bytes("opml/subscriptions.opml"))
        env.subscriptions.commitImport(account, doc)
        val beta = dao.feedByUrl(account, "https://example.org/beta.atom")!!
        env.subscriptions.unsubscribe(beta.id)
        val preview = env.subscriptions.previewImport(account, doc, allowHttp = false)
        assertEquals(1, preview.resubscribeFeeds)
        val result = env.subscriptions.commitImport(account, doc)
        assertEquals(1, result.resubscribedFeeds)
        assertEquals(listOf(beta.id), result.feedIdsToFetch)
        assertTrue(dao.feed(beta.id)!!.isSubscribed)

        // Adding the same URL by hand merges as well.
        env.subscriptions.unsubscribe(beta.id)
        assertEquals(AddFeedResult.Resubscribed(beta.id), env.subscriptions.addFeed(account, "https://EXAMPLE.org/beta.atom#x"))
        assertEquals(AddFeedResult.AlreadySubscribed(beta.id), env.subscriptions.addFeed(account, "https://example.org/beta.atom"))
        assertEquals(4, dao.feeds(account).size)
    }

    @Test
    fun warningsOpmlRegistersHttpButDoesNotFetch() = runTest {
        val account = env.subscriptions.ensureLocalAccount()
        val doc = OpmlParser.parse(Fixtures.bytes("opml/warnings.opml"))
        val preview = env.subscriptions.previewImport(account, doc, allowHttp = false)
        assertEquals(3, preview.totalUniqueFeeds)
        assertEquals(1, preview.httpPending)
        assertEquals(1, preview.invalidUrls)
        assertEquals(1, preview.unsupportedItems)
        env.subscriptions.commitImport(account, doc)
        val urls = dao.feeds(account).map { it.subscriptionUrl }.toSet()
        assertEquals(setOf("https://example.com/news.xml", "http://example.org/legacy.xml", "https://example.com/slash.xml"), urls)
        assertEquals(listOf("Folder/with/slashes"), dao.categories(account).map { CategoryPaths.decode(it.pathSegmentsJson) }.single())
    }

    @Test
    fun rejectedFilesChangeNothing() = runTest {
        val account = env.subscriptions.ensureLocalAccount()
        for (name in listOf("opml/malformed.opml", "opml/doctype.opml")) {
            val e = runCatching { OpmlParser.parse(Fixtures.bytes(name)) }.exceptionOrNull()
            assertTrue(e is OpmlRejectedException)
        }
        assertEquals(Triple(0, 0, 0), counts(account))
    }

    @Test
    fun failureDuringCommitRollsBackEverything() = runTest {
        val account = env.subscriptions.ensureLocalAccount()
        // Inject a failure inside the transaction after two memberships were inserted.
        env.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_third_membership BEFORE INSERT ON feed_categories " +
                "WHEN (SELECT COUNT(*) FROM feed_categories) >= 2 BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
        )
        val doc = OpmlParser.parse(Fixtures.bytes("opml/subscriptions.opml"))
        val error = runCatching { env.subscriptions.commitImport(account, doc) }.exceptionOrNull()
        assertTrue("expected injected failure, got $error", error?.message?.contains("injected failure") == true)
        assertEquals(Triple(0, 0, 0), counts(account))
    }
}
