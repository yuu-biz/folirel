package io.github.yuubiz.feeddeck.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlParser
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlWriter
import io.github.yuubiz.feeddeck.model.NormalizedEntry
import io.github.yuubiz.feeddeck.model.NormalizedFeed
import io.github.yuubiz.feeddeck.model.Structure
import io.github.yuubiz.feeddeck.testutil.Fixtures
import io.github.yuubiz.feeddeck.testutil.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** T07 OPML round trip, T09 add/unsubscribe/resubscribe, T10 category edit/delete. */
@RunWith(AndroidJUnit4::class)
class CategoryAndExportTest {
    private val env = TestEnv()
    private val dao = env.db.subscriptionDao()

    @After
    fun tearDown() = env.close()

    private suspend fun export(e: TestEnv): String {
        val account = e.subscriptions.ensureLocalAccount()
        val (feeds, paths) = e.subscriptions.exportData(account)
        return OpmlWriter.write("t", feeds.map { OpmlWriter.ExportFeed(Structure.displayName(it), it.subscriptionUrl, it.siteUrl, paths[it.id].orEmpty()) })
    }

    private suspend fun memberships(e: TestEnv): Set<Pair<String, List<String>>> {
        val account = e.subscriptions.ensureLocalAccount()
        val d = e.db.subscriptionDao()
        val urls = d.feeds(account).associate { it.id to it.subscriptionUrl }
        val paths = d.categories(account).associate { it.id to CategoryPaths.decode(it.pathSegmentsJson) }
        return d.memberships(account).map { urls.getValue(it.feedId) to paths.getValue(it.categoryId) }.toSet()
    }

    @Test
    fun opmlRoundTripPreservesUrlsAndDirectMemberships() = runBlocking {
        val account = env.subscriptions.ensureLocalAccount()
        env.subscriptions.commitImport(account, OpmlParser.parse(Fixtures.bytes("opml/subscriptions.opml")))
        val extra = """<opml version="2.0"><body>
            <outline text="特殊 &amp; &lt;記号&gt; &quot;引用&quot;"><outline text="A&amp;B &lt;tag&gt;" xmlUrl="https://example.com/q?a=1&amp;b=2"/></outline>
            <outline text="Legacy" xmlUrl="http://example.org/legacy.xml"/>
            <outline text="Paused" xmlUrl="https://example.com/paused.xml"/>
            <outline text="Gone" xmlUrl="https://example.com/gone.xml"/>
            </body></opml>"""
        env.subscriptions.commitImport(account, OpmlParser.parse(extra.toByteArray()))
        env.subscriptions.setPaused(dao.feedByUrl(account, "https://example.com/paused.xml")!!.id, true)
        env.subscriptions.unsubscribe(dao.feedByUrl(account, "https://example.com/gone.xml")!!.id)
        // Reading state must never be exported.
        val alpha = dao.feedByUrl(account, "https://example.com/alpha.xml")!!.id
        env.store.applySuccess(alpha, NormalizedFeed("Alpha", null, listOf(NormalizedEntry("id:x", "secret-article-title", null, "", null, null, null))), FetchMetadata(null, null, "u", null))
        env.articles.setSaved(env.db.articleDao().articlesOfFeed(alpha).single().id, true)

        val opml = export(env)
        assertFalse(opml.contains("secret-article-title"))
        assertFalse(opml.contains("gone.xml"))
        assertTrue(opml.contains("http://example.org/legacy.xml"))
        assertTrue(opml.contains("paused.xml"))

        val other = TestEnv()
        try {
            val otherAccount = other.subscriptions.ensureLocalAccount()
            other.subscriptions.commitImport(otherAccount, OpmlParser.parse(opml.toByteArray()))
            val expectedUrls = dao.feeds(account).filter { it.isSubscribed }.map { it.subscriptionUrl }.toSet()
            assertEquals(expectedUrls, other.db.subscriptionDao().feeds(otherAccount).map { it.subscriptionUrl }.toSet())
            val expectedMemberships = memberships(env).filter { (url, _) -> url in expectedUrls }.toSet()
            assertEquals(expectedMemberships, memberships(other))
            assertTrue(memberships(other).any { it.second == listOf("特殊 & <記号> \"引用\"") })
            assertEquals(0, other.db.articleDao().countArticles(otherAccount))
        } finally {
            other.close()
        }
    }

    @Test
    fun renameKeepsIdsAndMembershipsAndConflictsRollBack() = runBlocking {
        val account = env.subscriptions.ensureLocalAccount()
        env.subscriptions.commitImport(account, OpmlParser.parse(Fixtures.bytes("opml/subscriptions.opml")))
        val before = dao.categories(account).associate { CategoryPaths.decode(it.pathSegmentsJson) to it.id }
        val membershipsBefore = dao.memberships(account).toSet()
        val tech = before.getValue(listOf("Tech"))

        val result = env.subscriptions.renameCategory(tech, "技術/開発")
        assertEquals(SubscriptionRepository.CategoryResult.Ok(tech), result)
        val after = dao.categories(account).associate { it.id to CategoryPaths.decode(it.pathSegmentsJson) }
        assertEquals(listOf("技術/開発"), after.getValue(tech))
        assertEquals(listOf("技術/開発", "AI"), after.getValue(before.getValue(listOf("Tech", "AI"))))
        assertEquals(listOf("技術/開発", "Tools"), after.getValue(before.getValue(listOf("Tech", "Tools"))))
        assertEquals(membershipsBefore, dao.memberships(account).toSet())

        // Renaming "News" to an existing sibling path is a conflict: nothing changes.
        val snapshot = dao.categories(account).toSet()
        assertEquals(SubscriptionRepository.CategoryResult.Conflict, env.subscriptions.renameCategory(before.getValue(listOf("News")), "技術/開発"))
        assertEquals(snapshot, dao.categories(account).toSet())
        assertEquals(SubscriptionRepository.CategoryResult.InvalidName, env.subscriptions.renameCategory(tech, "   "))
        // Creating a duplicate path is a conflict as well.
        assertEquals(SubscriptionRepository.CategoryResult.Conflict, env.subscriptions.createCategory(account, null, " News "))
        assertTrue(env.subscriptions.createCategory(account, tech, "New child") is SubscriptionRepository.CategoryResult.Ok)
    }

    @Test
    fun deletingCategoryKeepsFeedsAndArticles() = runBlocking {
        val account = env.subscriptions.ensureLocalAccount()
        env.subscriptions.commitImport(account, OpmlParser.parse(Fixtures.bytes("opml/subscriptions.opml")))
        val beta = dao.feedByUrl(account, "https://example.org/beta.atom")!!.id
        env.store.applySuccess(beta, NormalizedFeed(null, null, listOf(NormalizedEntry("id:b", "beta article", null, "", null, null, null))), FetchMetadata(null, null, "u", null))
        val tech = dao.categories(account).first { CategoryPaths.decode(it.pathSegmentsJson) == listOf("Tech") }.id

        assertEquals(3, env.subscriptions.deleteCategory(tech)) // Tech, Tech/AI, Tech/Tools
        assertEquals(listOf(listOf("News")), dao.categories(account).map { CategoryPaths.decode(it.pathSegmentsJson) })
        assertEquals(4, dao.feeds(account).size)
        assertTrue(dao.memberships(account).none { it.feedId == beta }) // now uncategorized
        assertEquals(1, env.db.articleDao().articlesOfFeed(beta).size)
        // Alpha keeps its News membership.
        val alpha = dao.feedByUrl(account, "https://example.com/alpha.xml")!!.id
        assertEquals(1, dao.memberships(account).count { it.feedId == alpha })
    }

    @Test
    fun feedCategoryEditingAndAddFeedValidation() = runBlocking {
        val account = env.subscriptions.ensureLocalAccount()
        env.subscriptions.commitImport(account, OpmlParser.parse(Fixtures.bytes("opml/subscriptions.opml")))
        val unfiled = dao.feedByUrl(account, "https://example.com/unfiled.xml")!!.id
        val news = dao.categories(account).first { CategoryPaths.decode(it.pathSegmentsJson) == listOf("News") }.id
        env.subscriptions.setFeedCategories(unfiled, setOf(news))
        assertEquals(listOf(news), dao.memberships(account).filter { it.feedId == unfiled }.map { it.categoryId })
        env.subscriptions.setFeedCategories(unfiled, emptySet())
        assertTrue(dao.memberships(account).none { it.feedId == unfiled })

        assertTrue(env.subscriptions.addFeed(account, "javascript:alert(1)") is AddFeedResult.Invalid)
        assertTrue(env.subscriptions.addFeed(account, "ftp://example.com/feed") is AddFeedResult.Invalid)
        val http = env.subscriptions.addFeed(account, "http://example.org/new.xml")
        assertTrue(http is AddFeedResult.Added) // HTTP may be registered; fetching stays blocked until opt-in
        assertEquals(5, dao.feeds(account).size)
    }
}
