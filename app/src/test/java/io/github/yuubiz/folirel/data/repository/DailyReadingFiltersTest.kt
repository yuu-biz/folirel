package io.github.yuubiz.folirel.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.model.ArticleQuerySpec
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.model.PeriodFilter
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.ui.home.newArticlesIn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3A on the real query and the real database: the period (P3A-T06..T14), the hidden keywords (T19..T30) and what depends on
 * the same set (mark all as read T31, the new-article notice T32/T33, bound values T35). The period counts back from [now].
 */
@RunWith(AndroidJUnit4::class)
class DailyReadingFiltersTest {
    private val env = TestEnv()
    private lateinit var account: String
    private lateinit var feedId: String
    private lateinit var otherFeedId: String
    private val now get() = env.clock.millis()

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }

    @After
    fun tearDown() = env.close()

    private fun entry(key: String, title: String, summary: String = "", age: Long) = NormalizedEntry(
        entryKey = "id:$key", title = title, linkUrl = "https://example.com/$key", summaryText = summary, author = null,
        publishedAt = now - age, imageUrl = null,
    )

    private fun store(feed: String, vararg entries: NormalizedEntry) = runBlocking {
        env.store.applySuccess(feed, NormalizedFeed(null, null, entries.toList()), FetchMetadata(null, null, "https://example.com/", null))
    }

    @Before
    fun seed() = runBlocking<Unit> {
        account = env.subscriptions.ensureLocalAccount()
        val opml = """<opml version="2.0"><body>
            <outline text="One" xmlUrl="https://example.com/one.xml"/>
            <outline text="Two" xmlUrl="https://example.com/two.xml"/>
            </body></opml>"""
        env.subscriptions.commitImport(account, OpmlParser.parse(opml.toByteArray()))
        val dao = env.db.subscriptionDao()
        feedId = dao.feedByUrl(account, "https://example.com/one.xml")!!.id
        otherFeedId = dao.feedByUrl(account, "https://example.com/two.xml")!!.id
        // Ages around every boundary: exactly 24 h is still inside, one minute more is not.
        store(
            feedId,
            entry("h1", "1 hour old", age = HOUR),
            entry("h24in", "exactly 24 hours", age = DAY),
            entry("h24out", "24 hours and a minute", age = DAY + MINUTE),
            entry("d3in", "exactly 3 days", age = 3 * DAY),
            entry("d3out", "3 days and a minute", age = 3 * DAY + MINUTE),
            entry("d7in", "exactly 7 days", age = 7 * DAY),
            entry("d7out", "7 days and a minute", age = 7 * DAY + MINUTE),
            entry("d30in", "exactly 30 days", age = 30 * DAY),
            entry("d30out", "30 days and a minute", age = 30 * DAY + MINUTE),
        )
        store(otherFeedId, entry("o1", "other feed fresh", age = 2 * HOUR))
    }

    private fun spec(
        period: PeriodFilter = PeriodFilter.ALL,
        filter: ReadFilter = ReadFilter.ALL,
        search: String = "",
        sort: SortOrder = SortOrder.NEWEST,
        hidden: List<String> = emptyList(),
        scope: ArticleScope = ArticleScope.All,
        at: Long = now,
    ) = ArticleQuerySpec(
        account, scope, filter, sort, search, emptyList(),
        period = period, periodCutoff = period.cutoff(at), hiddenKeywords = hidden,
    )

    private fun rows(spec: ArticleQuerySpec) = runBlocking { env.articles.rows(spec) }
    private fun titles(spec: ArticleQuerySpec) = rows(spec).map { it.title }
    private fun idOf(title: String) = rows(spec()).first { it.title == title }.id

    // --- period -----------------------------------------------------------------------------------------------------------------

    @Test
    fun allShowsEverythingAndHasNoCutoff() {
        assertEquals(10, titles(spec()).size)
        assertEquals(null, PeriodFilter.ALL.cutoff(now))
        assertEquals(null, spec().periodCutoff)
    }

    @Test
    fun theBoundaryIsInclusiveAtTheCutoffAndExclusiveOneMinuteBefore() {
        val day = titles(spec(PeriodFilter.HOURS_24))
        assertEquals(listOf("1 hour old", "exactly 24 hours", "other feed fresh").sorted(), day.sorted())
        assertEquals(now - DAY, PeriodFilter.HOURS_24.cutoff(now))
    }

    @Test
    fun threeSevenAndThirtyDaysKeepTheirOwnBoundaries() {
        assertEquals(
            setOf("1 hour old", "exactly 24 hours", "24 hours and a minute", "exactly 3 days", "other feed fresh"),
            titles(spec(PeriodFilter.DAYS_3)).toSet(),
        )
        assertEquals(
            setOf("1 hour old", "exactly 24 hours", "24 hours and a minute", "exactly 3 days", "3 days and a minute", "exactly 7 days", "other feed fresh"),
            titles(spec(PeriodFilter.DAYS_7)).toSet(),
        )
        val thirty = titles(spec(PeriodFilter.DAYS_30)).toSet()
        assertTrue("exactly 30 days" in thirty)
        assertFalse("30 days and a minute" in thirty)
        assertEquals(9, thirty.size)
    }

    @Test
    fun theCutoffMovesWithTheClockAndTheListShrinks() {
        assertEquals(3, titles(spec(PeriodFilter.HOURS_24)).size)
        // 30 minutes later the "exactly 24 hours" article is 24 h 30 min old, and "1 hour old" is still inside.
        env.clock.advanceMinutes(30)
        assertEquals(setOf("1 hour old", "other feed fresh"), titles(spec(PeriodFilter.HOURS_24)).toSet())
        // 30 more hours: nothing of the seed is younger than 24 hours.
        env.clock.advanceMinutes(30 * 60)
        assertEquals(emptyList<String>(), titles(spec(PeriodFilter.HOURS_24)))
        // Nothing was deleted by the shrinking.
        assertEquals(10, titles(spec()).size)
    }

    @Test
    fun theSpecKeepsOneCutoffSoNothingIsRequeriedWithoutAClockChange() {
        assertEquals(spec(PeriodFilter.DAYS_7), spec(PeriodFilter.DAYS_7))
        assertEquals(spec(PeriodFilter.ALL), spec(PeriodFilter.ALL, at = now + 5 * DAY))
        assertNotEquals(spec(PeriodFilter.DAYS_7), spec(PeriodFilter.DAYS_7, at = now + MINUTE))
    }

    @Test
    fun periodCombinesWithReadFilterSavedSearchAndOrder() {
        val fresh = idOf("1 hour old")
        val old = idOf("exactly 7 days")
        runBlocking {
            env.articles.setRead(fresh, true)
            env.articles.setSaved(old, true)
            env.articles.setSaved(idOf("other feed fresh"), true)
        }
        // period + unread
        assertEquals(setOf("exactly 24 hours", "other feed fresh"), titles(spec(PeriodFilter.HOURS_24, ReadFilter.UNREAD)).toSet())
        // period + saved (the period applies to the saved list too)
        assertEquals(listOf("other feed fresh"), titles(spec(PeriodFilter.HOURS_24, ReadFilter.SAVED)))
        assertEquals(setOf("exactly 7 days", "other feed fresh"), titles(spec(PeriodFilter.DAYS_7, ReadFilter.SAVED)).toSet())
        // period + search
        assertEquals(listOf("exactly 24 hours"), titles(spec(PeriodFilter.DAYS_3, search = "exactly 24")))
        assertEquals(emptyList<String>(), titles(spec(PeriodFilter.HOURS_24, search = "exactly 7")))
        // period + order
        val newest = titles(spec(PeriodFilter.DAYS_3))
        assertEquals(newest.reversed(), titles(spec(PeriodFilter.DAYS_3, sort = SortOrder.OLDEST)))
        assertEquals("1 hour old", newest.first())
    }

    @Test
    fun theReadAndSavedStateIsNotTouchedByAnyPeriod() {
        val before = runBlocking { rows(spec()).associate { it.id to (it.isRead to it.isSaved) } }
        for (p in PeriodFilter.entries) rows(spec(p))
        val after = runBlocking { rows(spec()).associate { it.id to (it.isRead to it.isSaved) } }
        assertEquals(before, after)
        assertTrue(before.values.none { it.first || it.second })
    }

    @Test
    fun aSingleFeedScopeAppliesThePeriodAndReachesAnUnsubscribedFeed() = runBlocking<Unit> {
        assertEquals(listOf("other feed fresh"), titles(spec(PeriodFilter.HOURS_24, scope = ArticleScope.Feed(otherFeedId))))
        val saved = idOf("other feed fresh")
        env.articles.setSaved(saved, true)
        env.subscriptions.unsubscribe(otherFeedId)
        // Not in the normal lists any more, but its articles are there as the scope of that one feed (from Saved, say).
        assertFalse("other feed fresh" in titles(spec()))
        val viaFeed = rows(spec(scope = ArticleScope.Feed(otherFeedId), filter = ReadFilter.SAVED))
        assertEquals(listOf("other feed fresh"), viaFeed.map { it.title })
        assertEquals(otherFeedId, viaFeed.single().feedId)
        assertFalse(viaFeed.single().feedSubscribed)
        // Showing the feed changes nothing about the article.
        assertTrue(env.db.articleDao().state(saved)!!.isSaved)
        assertFalse(env.db.articleDao().state(saved)!!.isRead)
    }

    // --- hidden keywords --------------------------------------------------------------------------------------------------------

    private fun seedKeywordArticles() = store(
        feedId,
        entry("k1", "AI changes everything", "a long read", age = 10 * MINUTE),
        entry("k2", "Quiet title", "but the summary mentions Machine Learning", age = 11 * MINUTE),
        entry("k3", "100% juice", "sweet", age = 12 * MINUTE),
        entry("k4", "100X juice", "not a percent", age = 13 * MINUTE),
        entry("k5", "a_b under", "", age = 14 * MINUTE),
        entry("k6", "aXb under", "", age = 15 * MINUTE),
        entry("k7", "path c:\\temp\\x", "", age = 16 * MINUTE),
        entry("k8", "path c:/temp/x", "", age = 17 * MINUTE),
        entry("k9", "ガイド記事", "ひらがなだけ", age = 18 * MINUTE),
    )

    private val keywordTitles = listOf(
        "AI changes everything", "Quiet title", "100% juice", "100X juice", "a_b under", "aXb under",
        "path c:\\temp\\x", "path c:/temp/x", "ガイド記事",
    )

    private fun visible(hidden: List<String>, period: PeriodFilter = PeriodFilter.ALL) =
        titles(spec(period, hidden = hidden)).filter { it in keywordTitles }.toSet()

    @Test
    fun aKeywordHidesByTitleOrSummaryAndOnlyThose() {
        seedKeywordArticles()
        assertEquals(keywordTitles.toSet() - "AI changes everything", visible(listOf("everything")))
        assertEquals(keywordTitles.toSet() - "Quiet title", visible(listOf("machine learning")))
        // The source name is not matched: the feed is called "One".
        assertEquals(keywordTitles.toSet(), visible(listOf("One")))
        // Neither is the link ("example.com/k1") nor the author.
        assertEquals(keywordTitles.toSet(), visible(listOf("example.com", "k1")))
    }

    @Test
    fun asciiIgnoresCaseAndJapaneseIsASubstringAfterNormalization() {
        seedKeywordArticles()
        for (spelling in listOf("ai", "Ai", "AI", "  ai  ")) {
            assertEquals(keywordTitles.toSet() - "AI changes everything", visible(listOf(io.github.yuubiz.folirel.model.HiddenKeywords.normalize(spelling))))
        }
        // The keyword is typed decomposed (ガ as カ + dakuten); the stored text is composed: they meet after NFC.
        val keyword = io.github.yuubiz.folirel.model.HiddenKeywords.normalize("\u30AB\u3099イド")
        assertEquals("ガイド", keyword)
        assertEquals(keywordTitles.toSet() - "ガイド記事", visible(listOf(keyword)))
        // A substring in the middle of a Japanese summary hides it too.
        assertEquals(keywordTitles.toSet() - "ガイド記事", visible(listOf("ひらがな")))
    }

    @Test
    fun percentUnderscoreAndBackslashAreLiteral() {
        seedKeywordArticles()
        assertEquals(keywordTitles.toSet() - "100% juice", visible(listOf("100%")))
        assertEquals(keywordTitles.toSet() - "a_b under", visible(listOf("a_b")))
        assertEquals(keywordTitles.toSet() - "path c:\\temp\\x", visible(listOf("c:\\temp")))
        // A lone wildcard matches only articles that really contain it, not everything.
        assertEquals(keywordTitles.toSet() - "100% juice", visible(listOf("%")))
        assertEquals(keywordTitles.toSet() - "a_b under", visible(listOf("_")))
        assertEquals(keywordTitles.toSet() - "path c:\\temp\\x", visible(listOf("\\")))
    }

    @Test
    fun severalKeywordsEachHideAndDisablingOrRemovingBringsTheArticlesBack() {
        seedKeywordArticles()
        val all = keywordTitles.toSet()
        assertEquals(all - "AI changes everything" - "Quiet title", visible(listOf("everything", "learning")))
        // "Off" is an empty list in the spec: everything is back. Removing a word brings its articles back.
        assertEquals(all, visible(emptyList()))
        assertEquals(all - "Quiet title", visible(listOf("learning")))
        // A keyword that matches nothing hides nothing.
        assertEquals(all, visible(listOf("zzzzzz")))
    }

    @Test
    fun hidingNeverChangesReadSavedOrTheStoredArticles() = runBlocking<Unit> {
        seedKeywordArticles()
        val saved = idOf("AI changes everything")
        val read = idOf("Quiet title")
        env.articles.setSaved(saved, true)
        env.articles.setRead(read, true)
        val total = env.articles.countArticles(account)
        val hidden = listOf("everything", "learning")
        assertFalse("AI changes everything" in titles(spec(hidden = hidden)))
        assertFalse("AI changes everything" in titles(spec(filter = ReadFilter.SAVED, hidden = hidden)))
        assertEquals(total, env.articles.countArticles(account))
        val savedState = env.db.articleDao().state(saved)!!
        val readState = env.db.articleDao().state(read)!!
        assertTrue(savedState.isSaved && !savedState.isRead)
        assertTrue(readState.isRead && !readState.isSaved)
        // Without the keywords both are listed again, in the state they were in.
        val back = rows(spec()).associateBy { it.title }
        assertTrue(back.getValue("AI changes everything").isSaved)
        assertTrue(back.getValue("Quiet title").isRead)
    }

    @Test
    fun keywordPeriodAndSearchTogether() {
        seedKeywordArticles()
        store(feedId, entry("old-ai", "AI is old", "ancient", age = 20 * DAY), entry("old-q", "Quiet and old", "ancient", age = 21 * DAY))
        val s = spec(PeriodFilter.DAYS_7, search = "quiet", hidden = listOf("learning"))
        // "Quiet title" matches the search and the period but is hidden by its summary; "Quiet and old" is outside the period.
        assertEquals(emptyList<String>(), titles(s))
        assertEquals(listOf("Quiet title"), titles(s.copy(hiddenKeywords = emptyList())))
        assertEquals(setOf("Quiet title", "Quiet and old"), titles(s.copy(period = PeriodFilter.ALL, periodCutoff = null, hiddenKeywords = emptyList())).toSet())
        assertEquals(listOf("Quiet and old"), titles(s.copy(period = PeriodFilter.ALL, periodCutoff = null)))
    }

    // --- one set for the list, mark all as read and the notice ------------------------------------------------------------------

    @Test
    fun markAllAsReadTargetsExactlyTheUnreadRowsOfTheVisibleList() = runBlocking<Unit> {
        seedKeywordArticles()
        store(feedId, entry("old-q", "Quiet and old", "ancient", age = 21 * DAY))
        env.articles.setRead(idOf("100% juice"), true)
        val specs = listOf(
            spec(),
            spec(PeriodFilter.DAYS_7),
            spec(hidden = listOf("learning", "juice")),
            spec(PeriodFilter.HOURS_24, hidden = listOf("everything")),
            spec(PeriodFilter.DAYS_7, search = "under", hidden = listOf("a_b")),
            spec(PeriodFilter.DAYS_30, ReadFilter.UNREAD, hidden = listOf("ガイド")),
            spec(PeriodFilter.DAYS_7, ReadFilter.SAVED),
        )
        for (s in specs) {
            val visibleUnread = rows(s).filter { !it.isRead }.map { it.id }
            val snapshot = env.articles.unreadIdsSnapshot(s)
            assertEquals("count the dialog shows = what the list shows: $s", visibleUnread.size, snapshot.size)
            assertEquals(visibleUnread.toSet(), snapshot.toSet())
        }
        // Marking really leaves the hidden, the out-of-period and the unsearched articles unread.
        val s = spec(PeriodFilter.DAYS_7, hidden = listOf("learning"))
        val target = env.articles.unreadIdsSnapshot(s)
        env.articles.markRead(target)
        val byTitle = rows(spec()).associateBy { it.title }
        assertFalse("hidden by keyword stays unread", byTitle.getValue("Quiet title").isRead)
        assertFalse("outside the period stays unread", byTitle.getValue("Quiet and old").isRead)
        assertFalse("outside the period stays unread", byTitle.getValue("exactly 30 days").isRead)
        assertTrue(byTitle.getValue("AI changes everything").isRead)
        assertTrue(rows(s).all { it.isRead })
    }

    private fun row(id: String) = ArticleRow(id, "f", id, "", null, null, null, null, 0, false, false, null, "u", true)

    @Test
    fun theNoticeCountsOnlyArticlesTheListShowsAndKeepsTheRestPending() = runBlocking<Unit> {
        seedKeywordArticles()
        val aiRow = rows(spec()).first { it.title == "AI changes everything" }
        val announced = setOf(aiRow.id)
        // The list hides it: no banner, nothing acknowledged.
        val hiddenList = rows(spec(hidden = listOf("AI")))
        assertEquals(emptyList<String>(), newArticlesIn(hiddenList, announced))
        // The filter is loosened: the same pending announcement now counts, still unanswered.
        val looserList = rows(spec())
        assertEquals(listOf(aiRow.id), newArticlesIn(looserList, announced))
        // The period works the same way.
        val old = idOf("exactly 7 days")
        assertEquals(emptyList<String>(), newArticlesIn(rows(spec(PeriodFilter.HOURS_24)), setOf(old)))
        assertEquals(listOf(old), newArticlesIn(rows(spec(PeriodFilter.DAYS_7)), setOf(old)))
        assertEquals(emptyList<String>(), newArticlesIn(listOf(row("x")), emptySet()))
    }

    // --- values are bound, never interpolated -----------------------------------------------------------------------------------

    @Test
    fun keywordsPeriodAndSearchAreBindArgumentsAndNeverPartOfTheSql() {
        val injection = "' OR 1=1 --"
        val dangerous = spec(PeriodFilter.DAYS_7, search = "needle", hidden = listOf(injection, "secret"))
        val safe = spec(PeriodFilter.DAYS_7, search = "x", hidden = listOf("x", "y"))
        val q = ArticleQuery.rows(dangerous)
        for (value in listOf("secret", injection, "needle", dangerous.periodCutoff.toString(), "OR 1=1")) {
            assertFalse("`$value` must not be in the SQL text: ${q.sql}", q.sql.contains(value))
        }
        // Same shape for any text: the text only changes the arguments.
        assertEquals(ArticleQuery.rows(safe).sql, q.sql)
        // account, period cutoff, 2 keywords x 2 columns, 1 search term x 3 columns.
        assertEquals(1 + 1 + 4 + 3, q.argCount)
        assertEquals(1 + 1 + 4 + 3, ArticleQuery.unreadIds(dangerous).argCount)
        // And the injection text is matched literally (nothing in the table contains it, so nothing is hidden by it).
        seedKeywordArticles()
        assertEquals(keywordTitles.toSet(), visible(listOf(injection)))
    }

    @Test
    fun noFilterAddsNoClause() {
        val plain = ArticleQuery.rows(spec())
        assertFalse(plain.sql.contains("sortAt >="))
        assertFalse(plain.sql.contains("NOT ("))
        assertEquals(1, plain.argCount)
    }
}
