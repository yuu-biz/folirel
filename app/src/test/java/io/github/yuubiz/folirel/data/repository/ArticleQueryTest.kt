package io.github.yuubiz.folirel.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.model.ArticleQuerySpec
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import io.github.yuubiz.folirel.model.Structure
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** T26 manual state, T27 bulk read snapshot, T28 unsubscribed saved, T30 scope/filter, T36 local search. */
@RunWith(AndroidJUnit4::class)
class ArticleQueryTest {
    private val env = TestEnv()
    private lateinit var account: String
    private lateinit var feeds: Map<String, String>
    private lateinit var structure: Structure

    @After
    fun tearDown() = env.close()

    private fun entry(key: String, title: String, summary: String = "", minutesAgo: Long = 0) = NormalizedEntry(
        entryKey = "id:$key", title = title, linkUrl = "https://example.com/$key", summaryText = summary, author = null,
        publishedAt = env.clock.millis() - minutesAgo * 60_000, imageUrl = null,
    )

    private fun store(feedId: String, vararg entries: NormalizedEntry) = runBlocking {
        env.store.applySuccess(feedId, NormalizedFeed(null, null, entries.toList()), FetchMetadata(null, null, "https://example.com/", null))
    }

    @Before
    fun seed() = runBlocking {
        account = env.subscriptions.ensureLocalAccount()
        // Tech > AI has "ai"; "both" is in Tech/AI and News; "loose" is uncategorized.
        val opml = """<opml version="2.0"><body>
            <outline text="Tech"><outline text="AI">
              <outline text="AI feed" xmlUrl="https://example.com/ai.xml"/>
              <outline text="Both" xmlUrl="https://example.com/both.xml"/>
            </outline></outline>
            <outline text="News"><outline text="Both again" xmlUrl="https://example.com/both.xml"/></outline>
            <outline text="Loose" xmlUrl="https://example.com/loose.xml"/>
            </body></opml>"""
        env.subscriptions.commitImport(account, OpmlParser.parse(opml.toByteArray()))
        val dao = env.db.subscriptionDao()
        feeds = mapOf(
            "ai" to dao.feedByUrl(account, "https://example.com/ai.xml")!!.id,
            "both" to dao.feedByUrl(account, "https://example.com/both.xml")!!.id,
            "loose" to dao.feedByUrl(account, "https://example.com/loose.xml")!!.id,
        )
        store(feeds.getValue("ai"), entry("ai1", "機械学習の新しい手法", "大規模言語モデル", 10), entry("ai2", "Transformer explained", "attention", 20))
        store(feeds.getValue("both"), entry("b1", "両方のカテゴリに属する記事", "", 5))
        store(feeds.getValue("loose"), entry("l1", "100% 果汁_ジュース", "割引 50%", 30), entry("l2", "Plain title", "nothing special", 40))
        structure = env.subscriptions.observeStructure(account).first()
    }

    private fun categoryId(vararg path: String) = structure.categories.first { it.path == path.toList() }.id

    private fun spec(scope: ArticleScope = ArticleScope.All, filter: ReadFilter = ReadFilter.ALL, search: String = "", sort: SortOrder = SortOrder.NEWEST): ArticleQuerySpec {
        val ids = (scope as? ArticleScope.Category)?.let { structure.categoryIdsFor(it.categoryId) }.orEmpty()
        return ArticleQuerySpec(account, scope, filter, sort, search, ids)
    }

    private fun titles(spec: ArticleQuerySpec) = runBlocking { env.articles.rows(spec).map { it.title } }

    private fun idOf(title: String) = runBlocking { env.articles.rows(spec()).first { it.title == title }.id }

    @Test
    fun scopesIncludeDescendantsWithoutDuplicates() {
        assertEquals(5, titles(spec()).size)
        // Tech includes Tech/AI; the article of a feed in two categories appears once.
        assertEquals(
            listOf("両方のカテゴリに属する記事", "機械学習の新しい手法", "Transformer explained"),
            titles(spec(ArticleScope.Category(categoryId("Tech")))),
        )
        assertEquals(listOf("両方のカテゴリに属する記事"), titles(spec(ArticleScope.Category(categoryId("News")))))
        assertEquals(listOf("100% 果汁_ジュース", "Plain title"), titles(spec(ArticleScope.Uncategorized)))
        assertEquals(listOf("Transformer explained", "機械学習の新しい手法").reversed(), titles(spec(ArticleScope.Feed(feeds.getValue("ai")))))
        // Stable ordering both ways.
        assertEquals(titles(spec()).reversed(), titles(spec(sort = SortOrder.OLDEST)))
    }

    @Test
    fun readAndSavedAreIndependentAndFiltered() = runBlocking {
        val a = idOf("Plain title")
        assertTrue(env.articles.setSaved(a, true))
        assertTrue(env.articles.setRead(a, true))
        assertEquals(listOf("Plain title"), titles(spec(filter = ReadFilter.SAVED)))
        assertFalse("Plain title" in titles(spec(filter = ReadFilter.UNREAD)))
        assertTrue(env.articles.setRead(a, false))
        val state = env.db.articleDao().state(a)!!
        assertTrue(state.isSaved)
        assertFalse(state.isRead)
        // A write that matches nothing reports failure instead of pretending success.
        assertFalse(env.articles.setSaved("missing-id", true))
    }

    @Test
    fun markAllReadUsesTheFullSnapshotAndExcludesLaterArrivals() = runBlocking {
        val techSpec = spec(ArticleScope.Category(categoryId("Tech")), ReadFilter.UNREAD)
        val snapshot = env.articles.unreadIdsSnapshot(techSpec)
        assertEquals(3, snapshot.size)
        // An article arriving after the confirmation opened is not included.
        store(feeds.getValue("ai"), entry("late", "後から届いた記事", "", 0))
        assertEquals(snapshot.toSet(), env.articles.markRead(snapshot).toSet())
        assertEquals(listOf("後から届いた記事"), titles(techSpec))
        // Other scopes untouched.
        assertEquals(2, titles(spec(ArticleScope.Uncategorized, ReadFilter.UNREAD)).size)

        // Not limited to a first page: 1,200 unread articles are all marked.
        val many = (0 until 1200).map { entry("m$it", "bulk $it", "", 60L + it) }.toTypedArray()
        store(feeds.getValue("loose"), *many)
        val all = env.articles.unreadIdsSnapshot(spec(filter = ReadFilter.UNREAD))
        assertEquals(1203, all.size)
        assertEquals(1203, env.articles.markRead(all).size)
        assertEquals(0, titles(spec(filter = ReadFilter.UNREAD)).size)
    }

    @Test
    fun markAllReadRespectsSearch() = runBlocking {
        val searchSpec = spec(search = "transformer", filter = ReadFilter.ALL)
        val ids = env.articles.unreadIdsSnapshot(searchSpec)
        assertEquals(1, ids.size)
        env.articles.markRead(ids)
        assertEquals(4, titles(spec(filter = ReadFilter.UNREAD)).size)
    }

    @Test
    fun unsubscribedSavedArticlesStayInSavedFilterAndComeBack() = runBlocking {
        val saved = idOf("Plain title")
        env.articles.setSaved(saved, true)
        env.articles.setRead(saved, true)
        env.subscriptions.unsubscribe(feeds.getValue("loose"))
        assertFalse("Plain title" in titles(spec()))
        assertFalse("Plain title" in titles(spec(ArticleScope.Uncategorized)))
        val savedRows = env.articles.rows(spec(filter = ReadFilter.SAVED))
        assertEquals(listOf("Plain title"), savedRows.map { it.title })
        assertFalse(savedRows.single().feedSubscribed)

        env.subscriptions.resubscribe(feeds.getValue("loose"))
        val state = env.db.articleDao().state(saved)!!
        assertTrue(state.isSaved && state.isRead)
        assertTrue("Plain title" in titles(spec()))
    }

    @Test
    fun japaneseSubstringAndLiteralWildcards() {
        assertEquals(listOf("機械学習の新しい手法"), titles(spec(search = "学習")))
        assertEquals(listOf("機械学習の新しい手法"), titles(spec(search = "言語モデル"))) // summary match
        assertEquals(listOf("Transformer explained"), titles(spec(search = "TRANSFORMER"))) // ASCII case-insensitive
        assertEquals(listOf("100% 果汁_ジュース"), titles(spec(search = "100%")))
        assertEquals(listOf("100% 果汁_ジュース"), titles(spec(search = "汁_ジ")))
        assertEquals(emptyList<String>(), titles(spec(search = "%")).filter { !it.contains("%") })
        assertEquals(emptyList<String>(), titles(spec(search = "_")).filter { !it.contains("_") })
        assertEquals(listOf("Plain title"), titles(spec(search = "plain  special"))) // whitespace-separated terms
        assertEquals(listOf("機械学習の新しい手法", "Transformer explained"), titles(spec(search = "AI feed"))) // source name
        // Scope and filter still apply.
        assertEquals(emptyList<String>(), titles(spec(ArticleScope.Uncategorized, search = "学習")))
        assertEquals(emptyList<String>(), titles(spec(filter = ReadFilter.SAVED, search = "学習")))
        assertEquals("a\\%b\\_c\\\\", ArticleQuery.escapeLike("a%b_c\\"))
    }
}
