package io.github.yuubiz.folirel.ui.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.repository.FetchMetadata
import io.github.yuubiz.folirel.model.ArticleQuerySpec
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import io.github.yuubiz.folirel.network.NetworkState
import io.github.yuubiz.folirel.sync.RefreshCoordinator
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.testutil.cancelAndJoinAll
import io.github.yuubiz.folirel.testutil.scopeJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Snackbar feedback of the home view model with the real database: only changes that take articles out of the list on screen are
 * reported, and their Undo puts back exactly what the change did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HomeUndoTest {
    private val env = TestEnv()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var account: String
    private lateinit var vm: HomeViewModel

    @Before
    fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        account = env.subscriptions.ensureLocalAccount()
        val opml = """<opml version="2.0"><body><outline text="One" xmlUrl="https://example.com/one.xml"/></body></opml>"""
        env.subscriptions.commitImport(account, OpmlParser.parse(opml.toByteArray()))
        val feedId = env.db.subscriptionDao().feedByUrl(account, "https://example.com/one.xml")!!.id
        val entries = listOf("a", "b", "c", "d").mapIndexed { i, key ->
            NormalizedEntry(entryKey = "id:$key", title = key.uppercase(), linkUrl = "https://example.com/$key", summaryText = "", author = null, publishedAt = env.clock.millis() - (i + 1) * 60_000L, imageUrl = null)
        }
        env.store.applySuccess(feedId, NormalizedFeed(null, null, entries), FetchMetadata(null, null, "https://example.com/", null))
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = bg) { File(TestEnv.context().cacheDir, "undo-${System.nanoTime()}.preferences_pb") })
        val coordinator = RefreshCoordinator(env.backend, { account }, { true }, {}, bg, env.clock)
        vm = HomeViewModel(env.articles, env.subscriptions, settings, coordinator, flowOf(NetworkState(true, false)), SavedStateHandle(), env.clock)
        // The stored filter is applied once the settings are read; choosing one before that would be overwritten.
        withTimeout(5_000) { vm.settingsLoaded.first { it } }
    }

    @After
    fun tearDown() {
        cancelAndJoinAll(listOf(vm.scopeJob, bg.coroutineContext.job))
        env.close()
        Dispatchers.resetMain()
    }

    private suspend fun rows(): Map<String, ArticleRow> =
        env.articles.rows(ArticleQuerySpec(account, ArticleScope.All, ReadFilter.ALL, SortOrder.NEWEST)).associateBy { it.title }

    private suspend fun nextEvent(): HomeEvent = withTimeout(5_000) { vm.eventFlow.first() }

    private suspend fun noEvent(): HomeEvent? = withTimeoutOrNull(300) { vm.eventFlow.first() }

    private fun showOnly(filter: ReadFilter) {
        vm.selectFilter(filter)
        assertEquals(filter, vm.selection.value.filter)
    }

    @Test
    fun undoOfMarkAllAsReadMakesUnreadExactlyTheArticlesItChanged() = runBlocking<Unit> {
        // Ready once the account and the hidden keywords are known.
        val request = withTimeout(5_000) {
            var ready = vm.prepareMarkAllRead("All")
            while (ready == null) {
                delay(20)
                ready = vm.prepareMarkAllRead("All")
            }
            ready
        }
        assertEquals(4, request.ids.size)
        // Read by hand while the confirmation was open: mark all does not change it, so its undo must not either.
        vm.setRead(rows().getValue("B"), true).join()
        vm.confirmMarkAllRead(request).join()
        val event = nextEvent() as HomeEvent.AllMarkedRead
        assertEquals(setOf("A", "C", "D"), event.ids.map { id -> rows().values.single { it.id == id }.title }.toSet())
        assertEquals(List(4) { true }, rows().values.map { it.isRead })

        vm.undo(event).join()
        val after = rows()
        assertEquals(mapOf("A" to false, "B" to true, "C" to false, "D" to false), after.mapValues { it.value.isRead })
        assertNull(noEvent())
    }

    @Test
    fun aRowThatLeavesTheUnreadListCanBeBroughtBack() = runBlocking<Unit> {
        showOnly(ReadFilter.UNREAD)
        val a = rows().getValue("A")
        vm.setRead(a, true).join()
        val event = nextEvent()
        assertEquals(HomeEvent.LeftUnread(a.id), event)
        vm.undo(event as HomeEvent.Undoable).join()
        assertEquals(false, rows().getValue("A").isRead)
    }

    @Test
    fun aRowThatLeavesTheSavedListCanBeBroughtBack() = runBlocking<Unit> {
        vm.toggleSaved(rows().getValue("C")).join()
        assertNull("saving shows on the row itself", noEvent())
        showOnly(ReadFilter.SAVED)
        val c = rows().getValue("C")
        vm.toggleSaved(c).join()
        val event = nextEvent()
        assertEquals(HomeEvent.LeftSaved(c.id), event)
        vm.undo(event as HomeEvent.Undoable).join()
        assertEquals(true, rows().getValue("C").isSaved)
    }

    @Test
    fun changesTheRowItselfShowsAreNotAnnounced() = runBlocking<Unit> {
        // All articles: a read or unread row stays in the list, its look says what changed.
        vm.setRead(rows().getValue("A"), true).join()
        vm.setRead(rows().getValue("A"), false).join()
        vm.toggleSaved(rows().getValue("B")).join()
        vm.toggleSaved(rows().getValue("B")).join()
        // Unread only: marking unread keeps the row.
        showOnly(ReadFilter.UNREAD)
        vm.setRead(rows().getValue("D"), false).join()
        assertNull(noEvent())
    }
}
