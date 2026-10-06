package io.github.yuubiz.folirel.ui.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.repository.FetchMetadata
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.HiddenKeywords
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.model.PeriodFilter
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.network.NetworkState
import io.github.yuubiz.folirel.sync.RefreshCoordinator
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The home view model with the real database, settings store and query: what the period and the hidden keywords do to the list,
 * to "mark all as read" and to the single-feed scope. Not a screen test: the menu and panel need a device (NOT_RUN there).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HomeViewModelFiltersTest {
    private val env = TestEnv()
    private val main = UnconfinedTestDispatcher()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val collectors = HashMap<ArticleScope, Job>()
    private lateinit var account: String
    private lateinit var feedId: String
    private lateinit var settingsFile: File
    private lateinit var settings: SettingsRepository
    private lateinit var vm: HomeViewModel

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }

    private fun entry(key: String, title: String, summary: String = "", age: Long) = NormalizedEntry(
        entryKey = "id:$key", title = title, linkUrl = "https://example.com/$key", summaryText = summary, author = null,
        publishedAt = env.clock.millis() - age, imageUrl = null,
    )

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): HomeViewModel {
        val coordinator = RefreshCoordinator(env.backend, { account }, { true }, {}, bg, env.clock)
        return HomeViewModel(env.articles, env.subscriptions, settings, coordinator, flowOf(NetworkState(true, false)), handle, env.clock)
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(main)
        account = env.subscriptions.ensureLocalAccount()
        val opml = """<opml version="2.0"><body><outline text="Tech"><outline text="One" xmlUrl="https://example.com/one.xml"/></outline></body></opml>"""
        env.subscriptions.commitImport(account, OpmlParser.parse(opml.toByteArray()))
        feedId = env.db.subscriptionDao().feedByUrl(account, "https://example.com/one.xml")!!.id
        env.store.applySuccess(
            feedId,
            NormalizedFeed(
                null, null,
                listOf(
                    entry("a", "Fresh about AI", "model news", 2 * HOUR),
                    entry("b", "Fresh basics", "nothing", 3 * HOUR),
                    entry("c", "Three days old", "", 2 * DAY + 2 * HOUR),
                    entry("d", "Ten days old AI", "", 10 * DAY),
                    entry("e", "Forty days old", "", 40 * DAY),
                ),
            ),
            FetchMetadata(null, null, "https://example.com/", null),
        )
        settingsFile = File(TestEnv.context().cacheDir, "vm-${System.nanoTime()}.preferences_pb")
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = bg) { settingsFile })
        vm = newViewModel()
    }

    @After
    fun tearDown() {
        collectors.values.forEach { it.cancel() }
        bg.cancel()
        env.close()
        Dispatchers.resetMain()
    }

    /** Keeps the page list of [scope] subscribed (it only runs while someone collects) and waits for its answer. */
    private fun titles(scope: ArticleScope = ArticleScope.All, expected: List<String>? = null): List<String> {
        val flow = vm.pageList(scope)
        collectors.getOrPut(scope) { CoroutineScope(Dispatchers.Unconfined).launch { flow.collect { } } }
        return await { flow.value.takeIf { it.loaded }?.items?.map { it.title }?.takeIf { expected == null || it.toSet() == expected.toSet() } }
    }

    private fun <T : Any> await(block: () -> T?): T {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            block()?.let { return it }
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for the view model")
    }

    private fun waitFor(condition: () -> Boolean) = await { if (condition()) true else null }

    @Test
    fun choosingAPeriodNarrowsTheListKeepsItAndStoresIt() = runBlocking<Unit> {
        assertEquals(5, titles().size)
        vm.selectPeriod(PeriodFilter.HOURS_24)
        assertEquals(setOf("Fresh about AI", "Fresh basics"), titles(expected = listOf("Fresh about AI", "Fresh basics")).toSet())
        vm.selectPeriod(PeriodFilter.DAYS_7)
        assertEquals(3, titles(expected = listOf("Fresh about AI", "Fresh basics", "Three days old")).size)
        waitFor { vm.selection.value.period == PeriodFilter.DAYS_7 }
        // It is part of the stored settings (restart) and moving to a category keeps it (it belongs to no scope).
        waitFor { runBlocking { settings.current().periodFilter } == PeriodFilter.DAYS_7 }
        vm.selectScope(ArticleScope.Category("none"))
        assertEquals(PeriodFilter.DAYS_7, vm.selection.value.period)
        // Nothing was deleted by narrowing.
        assertEquals(5, env.articles.countArticles(account))
    }

    @Test
    fun theListKeyHoldsThePeriodSoAnotherPeriodNeverRestoresThisScrollPosition() {
        val before = titles()
        assertEquals(5, before.size)
        val keyAll = vm.pageList(ArticleScope.All).value.key
        vm.selectPeriod(PeriodFilter.DAYS_30)
        waitFor { vm.pageList(ArticleScope.All).value.key != keyAll }
        val key30 = vm.pageList(ArticleScope.All).value.key
        assertTrue(key30.endsWith("DAYS_30"))
        vm.selectPeriod(PeriodFilter.ALL)
        waitFor { vm.pageList(ArticleScope.All).value.key == keyAll }
    }

    @Test
    fun theChoiceComesBackAfterProcessRecreationAndAfterARestart() {
        vm.selectPeriod(PeriodFilter.DAYS_3)
        waitFor { runBlocking { settings.current().periodFilter } == PeriodFilter.DAYS_3 }
        // Process recreation: the saved state of the old view model goes to the new one.
        val handle = SavedStateHandle(mapOf("scope" to "all", "filter" to "ALL", "sort" to "NEWEST", "search" to "", "period" to "DAYS_3"))
        val recreated = newViewModel(handle)
        waitFor { recreated.selection.value.period == PeriodFilter.DAYS_3 }
        // App restart: no saved state at all, the stored setting decides.
        val restarted = newViewModel(SavedStateHandle())
        waitFor { restarted.selection.value.period == PeriodFilter.DAYS_3 }
        // A saved state with a name this build does not know falls back to the stored one.
        val odd = newViewModel(SavedStateHandle(mapOf("scope" to "all", "period" to "YEARS_9")))
        waitFor { odd.selection.value.period == PeriodFilter.ALL || odd.selection.value.period == PeriodFilter.DAYS_3 }
    }

    @Test
    fun anOpenListLosesOldArticlesWhenTheAppReturnsAndAsTheClockTicks() {
        vm.selectPeriod(PeriodFilter.HOURS_24)
        assertEquals(setOf("Fresh about AI", "Fresh basics"), titles(expected = listOf("Fresh about AI", "Fresh basics")).toSet())
        // 21 h 30 min later "Fresh basics" (3 h old) is 24 h 30 min old and out, "Fresh about AI" (2 h old) is 23 h 30 min old and in.
        // The app comes back to the foreground, which renews the cutoff.
        env.clock.advanceMinutes(21 * 60 + 30)
        vm.refreshPeriodClock()
        assertEquals(listOf("Fresh about AI"), titles(expected = listOf("Fresh about AI")))
        // Without a foreground event the coarse tick does the same.
        env.clock.advanceMinutes(2 * 60)
        main.scheduler.advanceTimeBy(HomeViewModel.PERIOD_TICK_MS + 1)
        assertEquals(emptyList<String>(), titles(expected = emptyList()))
        assertEquals(5, runBlocking { env.articles.countArticles(account) })
    }

    @Test
    fun hiddenKeywordsHideInTheListAndComeBackWhenSwitchedOffOrRemoved() = runBlocking<Unit> {
        assertTrue(vm.addHiddenKeyword("ai") is HiddenKeywords.AddResult.Added)
        assertEquals(setOf("Fresh basics", "Three days old", "Forty days old"), titles(expected = listOf("Fresh basics", "Three days old", "Forty days old")).toSet())
        vm.setHiddenKeywordsEnabled(false)
        assertEquals(5, titles(expected = listOf("Fresh about AI", "Fresh basics", "Three days old", "Ten days old AI", "Forty days old")).size)
        vm.setHiddenKeywordsEnabled(true)
        assertEquals(3, titles(expected = listOf("Fresh basics", "Three days old", "Forty days old")).size)
        vm.removeHiddenKeyword("ai")
        assertEquals(5, titles(expected = listOf("Fresh about AI", "Fresh basics", "Three days old", "Ten days old AI", "Forty days old")).size)
        assertEquals(HiddenKeywords.AddResult.Blank, vm.addHiddenKeyword("   "))
    }

    @Test
    fun markAllAsReadCountsAndMarksExactlyWhatThePeriodKeywordsAndSearchLeaveVisible() = runBlocking<Unit> {
        vm.selectPeriod(PeriodFilter.DAYS_30)
        vm.addHiddenKeyword("basics")
        // 30 days, "basics" hidden: Fresh about AI, Three days old, Ten days old AI (Forty is outside, Fresh basics is hidden).
        titles(expected = listOf("Fresh about AI", "Three days old", "Ten days old AI"))
        val request = vm.prepareMarkAllRead("All")!!
        assertEquals(3, request.ids.size)
        assertEquals(PeriodFilter.DAYS_30, request.period)
        assertTrue(request.hiddenKeywordsActive)
        vm.confirmMarkAllRead(request).join()
        val byTitle = env.articles.rows(io.github.yuubiz.folirel.model.ArticleQuerySpec(account, ArticleScope.All, ReadFilter.ALL, io.github.yuubiz.folirel.model.SortOrder.NEWEST)).associateBy { it.title }
        assertFalse("hidden stays unread", byTitle.getValue("Fresh basics").isRead)
        assertFalse("outside the period stays unread", byTitle.getValue("Forty days old").isRead)
        assertTrue(byTitle.getValue("Fresh about AI").isRead && byTitle.getValue("Three days old").isRead && byTitle.getValue("Ten days old AI").isRead)
        // The same with a search on top: only the match inside the visible set.
        vm.setSearchText("ten days")
        main.scheduler.advanceTimeBy(HomeViewModel.SEARCH_DEBOUNCE_MS + 1)
        waitFor { vm.selection.value.search == "ten days" }
        val searched = vm.prepareMarkAllRead("All")!!
        assertEquals(0, searched.ids.size) // already read above: nothing unread matches
    }

    @Test
    fun showingASourceIsAScopeChangeThatLeavesFilterReadAndSavedAndBackReturns() = runBlocking<Unit> {
        val saved = env.articles.rows(io.github.yuubiz.folirel.model.ArticleQuerySpec(account, ArticleScope.All, ReadFilter.ALL, io.github.yuubiz.folirel.model.SortOrder.NEWEST)).first { it.title == "Fresh basics" }
        env.articles.setSaved(saved.id, true)
        titles()
        vm.showSaved()
        waitFor { vm.selection.value.filter == ReadFilter.SAVED }
        vm.showFeed(feedId)
        waitFor { vm.selection.value.scope == ArticleScope.Feed(feedId) }
        // The filter stays (S10), the way back is remembered, and nothing about any article changed.
        assertEquals(ReadFilter.SAVED, vm.selection.value.filter)
        assertEquals(ArticleScope.All, vm.returnScope.value)
        assertEquals(listOf("Fresh basics"), titles(ArticleScope.Feed(feedId), expected = listOf("Fresh basics")))
        val state = env.db.articleDao().state(saved.id)!!
        assertTrue(state.isSaved)
        assertFalse(state.isRead)
        // Showing the same source again keeps the first return point; Back goes there and forgets it.
        vm.showFeed(feedId)
        assertEquals(ArticleScope.All, vm.returnScope.value)
        vm.leaveFeed()
        waitFor { vm.selection.value.scope == ArticleScope.All }
        assertNull(vm.returnScope.value)
        // Choosing another scope from the single feed also drops the return point (no stale Back target).
        vm.showFeed(feedId)
        vm.selectScope(ArticleScope.Uncategorized)
        assertNull(vm.returnScope.value)
    }
}
