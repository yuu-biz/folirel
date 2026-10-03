package io.github.yuubiz.feeddeck.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.feeddeck.backend.FeedResult
import io.github.yuubiz.feeddeck.backend.local.opml.OpmlParser
import io.github.yuubiz.feeddeck.data.db.FeedCategoryEntity
import io.github.yuubiz.feeddeck.model.FeedListFilter
import io.github.yuubiz.feeddeck.model.FeedDiagnostics
import io.github.yuubiz.feeddeck.model.FeedProblem
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.model.NormalizedEntry
import io.github.yuubiz.feeddeck.model.NormalizedFeed
import io.github.yuubiz.feeddeck.model.Structure
import io.github.yuubiz.feeddeck.testutil.TestEnv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** U10 / U11 / U12: bulk unsubscribe keeps everything else; failures are told apart; filters. */
@RunWith(AndroidJUnit4::class)
class BulkUnsubscribeTest {
    private val env = TestEnv()
    private val dao = env.db.subscriptionDao()

    @After
    fun tearDown() = env.close()

    private val meta = FetchMetadata(null, null, "https://x/", null)

    private suspend fun feed(name: String): String {
        val account = env.subscriptions.ensureLocalAccount()
        return (env.subscriptions.addFeed(account, "https://$name.example.com/feed.xml") as AddFeedResult.Added).feedId
    }

    private suspend fun article(feedId: String, key: String) =
        env.store.applySuccess(
            feedId,
            NormalizedFeed("t", null, listOf(NormalizedEntry("url:https://a.example/$key", key, "https://a.example/$key", "", null, 5_000L, null))),
            meta, BodyStats(0, 1), AttemptInfo(200, false),
        ).let { env.db.articleDao().articlesOfFeed(feedId).first { it.entryKey.endsWith(key) } }

    @Test
    fun unsubscribesOnlyTheChosenFeedsAndKeepsStatesAndCategories() = runBlocking<Unit> {
        val account = env.subscriptions.ensureLocalAccount()
        val a = feed("a")
        val b = feed("b")
        val c = feed("c")
        env.subscriptions.createCategory(account, null, "News")
        val category = dao.categories(account).single().id
        dao.insertMembership(FeedCategoryEntity(a, category))
        val articleA = article(a, "one")
        val articleB = article(b, "two")
        env.articles.setSaved(articleA.id, true)
        env.articles.setRead(articleA.id, true)

        val changed = env.subscriptions.unsubscribeAll(listOf(a, b))
        assertEquals(setOf(a, b), changed.toSet())
        assertFalse(dao.feed(a)!!.isSubscribed)
        assertFalse(dao.feed(b)!!.isSubscribed)
        assertTrue("the feed that was not chosen stays", dao.feed(c)!!.isSubscribed)
        // Articles, saved/read state and category membership stay.
        assertEquals(1, env.db.articleDao().articlesOfFeed(a).size)
        assertTrue(env.db.articleDao().state(articleA.id)!!.isSaved)
        assertTrue(env.db.articleDao().state(articleA.id)!!.isRead)
        assertFalse(env.db.articleDao().state(articleB.id)!!.isSaved)
        assertEquals(listOf(a), dao.memberships(account).map { it.feedId })
        // Active feeds no longer include them.
        assertEquals(listOf(c), env.store.activeFeeds(account).map { it.id })

        // Doing it again changes nothing and reports nothing.
        assertEquals(emptyList<String>(), env.subscriptions.unsubscribeAll(listOf(a, b)))
        // Undo: exactly those, nothing else.
        assertEquals(setOf(a, b), env.subscriptions.resubscribeAll(changed).toSet())
        assertTrue(dao.feed(a)!!.isSubscribed && dao.feed(b)!!.isSubscribed)
        assertEquals("the same article rows, no copies", 1, env.db.articleDao().articlesOfFeed(a).size)
        assertTrue(env.db.articleDao().state(articleA.id)!!.isSaved)
    }

    @Test
    fun largeSelectionsAndUnknownIdsAreHandled() = runBlocking<Unit> {
        val account = env.subscriptions.ensureLocalAccount()
        val opml = "<opml version=\"2.0\"><body>" + (1..1200).joinToString("") { "<outline text=\"f$it\" xmlUrl=\"https://h$it.example.com/f.xml\"/>" } + "</body></opml>"
        val ids = env.subscriptions.commitImport(account, OpmlParser.parse(opml.toByteArray())).feedIdsToFetch
        assertEquals(1200, ids.size)
        val changed = env.subscriptions.unsubscribeAll(ids.take(1100) + "no-such-feed")
        assertEquals(1100, changed.size)
        assertEquals(100, env.store.activeFeeds(account).size)
    }

    /** A late response of a feed that was unsubscribed meanwhile neither revives it nor writes anything. */
    @Test
    fun aLateFetchResultDoesNotBringBackAnUnsubscribedFeed() = runBlocking<Unit> {
        val a = feed("late")
        env.subscriptions.unsubscribeAll(listOf(a))
        val result = env.store.applySuccess(
            a, NormalizedFeed("t", null, listOf(NormalizedEntry("url:https://a.example/x", "x", "https://a.example/x", "", null, 1L, null))),
            meta, BodyStats(0, 1), AttemptInfo(200, false),
        )
        assertEquals(ApplyResult.SkippedInactive, result)
        assertFalse(dao.feed(a)!!.isSubscribed)
        assertEquals(0, env.db.articleDao().articlesOfFeed(a).size)
    }

    @Test
    fun migratedAndMovedFeedsAreReEnabledOnlyByTheOldOpmlThatNamesThem() = runBlocking<Unit> {
        val account = env.subscriptions.ensureLocalAccount()
        val a = feed("old")
        env.subscriptions.unsubscribeAll(listOf(a))
        val opml = OpmlParser.parse("<opml version=\"2.0\"><body><outline text=\"f\" xmlUrl=\"https://old.example.com/feed.xml\"/></body></opml>".toByteArray())
        val preview = env.subscriptions.previewImport(account, opml, allowHttp = false)
        // The old OPML visibly resubscribes it (count and names), never silently.
        assertEquals(1, preview.resubscribeFeeds)
        assertEquals(listOf("old.example.com"), preview.resubscribeSamples)
        val commit = env.subscriptions.commitImport(account, opml)
        assertEquals(1, commit.resubscribedFeeds)
        assertEquals(0, commit.createdFeeds)
    }

    // U12: classification and filters

    private fun node(
        error: FetchError? = null,
        initialized: Boolean = true,
        paused: Boolean = false,
        fetchUrl: String = "https://x.example.com/f",
        retryAfterUntil: Long? = null,
        nextEligibleAt: Long? = null,
        subscribed: Boolean = true,
    ) = io.github.yuubiz.feeddeck.model.FeedNode(
        id = "id", name = "n", url = fetchUrl, fetchUrl = fetchUrl, subscribed = subscribed, paused = paused,
        lastError = error, initialized = initialized, categoryIds = emptySet(), retryAfterUntil = retryAfterUntil, nextEligibleAt = nextEligibleAt,
    )

    @Test
    fun failuresAreToldApartAndNeverCalledClosures() {
        val expected = mapOf(
            FetchError.NOT_FOUND to FeedProblem.MISSING, FetchError.GONE to FeedProblem.MISSING,
            FetchError.ACCESS_DENIED to FeedProblem.ACCESS, FetchError.RATE_LIMITED to FeedProblem.BUSY,
            FetchError.SERVER to FeedProblem.SERVER, FetchError.TLS to FeedProblem.TLS,
            FetchError.NETWORK to FeedProblem.NETWORK, FetchError.TIMEOUT to FeedProblem.NETWORK,
            FetchError.FORMAT to FeedProblem.UNREADABLE, FetchError.NOT_A_FEED to FeedProblem.UNREADABLE,
            FetchError.SECURITY to FeedProblem.BLOCKED, FetchError.REDIRECT_LOOP to FeedProblem.BLOCKED,
        )
        for ((error, problem) in expected) assertEquals(error.name, problem, FeedProblem.of(error))
        // Every error maps to something; temporary ones are marked so.
        FetchError.entries.forEach { FeedProblem.of(it) }
        assertTrue(FeedProblem.NETWORK.likelyTemporary && FeedProblem.BUSY.likelyTemporary && FeedProblem.SERVER.likelyTemporary)
        assertFalse(FeedProblem.MISSING.likelyTemporary)
        // 404 and 410 share one problem but are not "closed": no problem value says so.
        assertTrue(FeedProblem.entries.none { it.name.contains("CLOSED", ignoreCase = true) })
    }

    @Test
    fun filtersSeparateFailedNeverFetchedHttpPendingWaitingAndPaused() {
        val now = 1_000_000L
        fun matches(f: FeedListFilter, n: io.github.yuubiz.feeddeck.model.FeedNode, d: FeedDiagnostics? = null) = f.matches(n, d, now)

        val failed = node(error = FetchError.NOT_FOUND)
        assertTrue(matches(FeedListFilter.FAILED, failed))
        assertFalse(matches(FeedListFilter.NEVER_FETCHED, failed))

        val never = node(initialized = false)
        assertTrue(matches(FeedListFilter.NEVER_FETCHED, never))
        assertFalse(matches(FeedListFilter.FAILED, never))

        val http = node(initialized = false, fetchUrl = "http://old.example.com/f")
        assertTrue(matches(FeedListFilter.HTTP_PENDING, http))
        assertFalse("http pending is not 'never fetched'", matches(FeedListFilter.NEVER_FETCHED, http))
        assertTrue(matches(FeedListFilter.HTTP_PENDING, node(), FeedDiagnostics("id", result = FeedResult.HTTP_DISABLED)))

        val waiting = node(error = FetchError.RATE_LIMITED, retryAfterUntil = now + 10)
        assertTrue(matches(FeedListFilter.WAITING, waiting))
        assertTrue(matches(FeedListFilter.WAITING, node(error = FetchError.SERVER, nextEligibleAt = now + 10)))
        assertFalse(matches(FeedListFilter.WAITING, node(error = FetchError.SERVER, nextEligibleAt = now - 10)))

        val paused = node(error = FetchError.NOT_FOUND, paused = true)
        assertTrue(matches(FeedListFilter.PAUSED, paused))
        assertFalse("paused feeds are not listed as failed", matches(FeedListFilter.FAILED, paused))

        // Unsubscribed feeds never show up in these filters; "All" is every subscribed feed.
        assertFalse(matches(FeedListFilter.ALL, node(subscribed = false)))
        assertTrue(matches(FeedListFilter.ALL, node()))
        // A healthy feed is in none of the problem filters.
        listOf(FeedListFilter.FAILED, FeedListFilter.NEVER_FETCHED, FeedListFilter.HTTP_PENDING, FeedListFilter.WAITING, FeedListFilter.PAUSED)
            .forEach { assertFalse(it.name, matches(it, node())) }
    }

    @Test
    fun structureCarriesFailureDetails() = runBlocking<Unit> {
        val a = feed("detail")
        env.store.recordFailure(a, FetchError.NOT_FOUND, null, AttemptInfo(404, false))
        env.store.recordFailure(a, FetchError.NOT_FOUND, null, AttemptInfo(404, false))
        val account = env.subscriptions.ensureLocalAccount()
        val node = env.subscriptions.observeStructure(account).first { it.loaded && it.feeds.isNotEmpty() }.feeds.getValue(a)
        assertEquals(FeedProblem.MISSING, node.problem)
        assertEquals(2, node.failureCount)
        assertEquals(null, node.lastSuccessAt)
        assertEquals(Structure.displayName(dao.feed(a)!!), node.name)
    }
}
