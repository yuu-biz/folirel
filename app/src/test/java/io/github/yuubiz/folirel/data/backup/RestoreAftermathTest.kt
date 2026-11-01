package io.github.yuubiz.folirel.data.backup

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.yuubiz.folirel.backend.BackendCapabilities
import io.github.yuubiz.folirel.backend.BackendSynchronizer
import io.github.yuubiz.folirel.backend.RefreshProgress
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshReport
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.PeriodFilter
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import io.github.yuubiz.folirel.network.NetworkState
import io.github.yuubiz.folirel.sync.RefreshCoordinator
import io.github.yuubiz.folirel.sync.RefreshScheduler
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.testutil.cancelAndJoinAll
import io.github.yuubiz.folirel.testutil.literalUrl
import io.github.yuubiz.folirel.testutil.scopeJob
import io.github.yuubiz.folirel.ui.home.HomeViewModel
import io.github.yuubiz.folirel.ui.home.Selection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * After a restore (T38, T42, §8): no refresh is started by it, refresh is held while it runs and works normally afterwards, the home
 * list opens on the restored selection, and periodic updates the restored settings switch on wait one interval.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RestoreAftermathTest {
    /** Counts calls; [hold] makes a call wait until it is cancelled. */
    private class FakeBackend(private val hold: Boolean = false) : BackendSynchronizer {
        override val backendType = "FAKE"
        override val capabilities = BackendCapabilities(subscriptionEditing = true, remoteStateSync = false)
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        override suspend fun refresh(accountId: String, scope: RefreshScope, reason: RefreshReason, force: Boolean, onProgress: (RefreshProgress) -> Unit): RefreshReport {
            calls.incrementAndGet()
            started.complete(Unit)
            if (hold) awaitCancellation()
            return RefreshReport(total = 1, updated = 1)
        }
    }

    /** T38: a paused coordinator starts nothing; a running refresh is cancelled by the pause; after resume refresh works again. */
    @Test
    fun t38_refreshIsHeldDuringARestore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val backend = FakeBackend(hold = true)
            val attempts = AtomicInteger()
            val coordinator = RefreshCoordinator(backend, { "a" }, { true }, { attempts.incrementAndGet() }, scope, java.time.Clock.systemUTC())
            val running = async(Dispatchers.IO) { coordinator.refresh(RefreshScope.All, RefreshReason.MANUAL) }
            withTimeout(5_000) { backend.started.await() }
            coordinator.pause()
            val interrupted = withTimeout(5_000) { running.await() }
            assertEquals(0, interrupted.total)
            withTimeout(5_000) { coordinator.state.first { !it.running } }
            // While paused: nothing starts, not even the attempt bookkeeping.
            assertEquals(0, coordinator.refresh(RefreshScope.All, RefreshReason.PERIODIC).total)
            assertEquals(0, coordinator.refresh(RefreshScope.All, RefreshReason.STARTUP).total)
            assertEquals(1, backend.calls.get())
            assertEquals(1, attempts.get())
            coordinator.resume()
            val free = FakeBackend()
            val again = RefreshCoordinator(free, { "a" }, { true }, {}, scope, java.time.Clock.systemUTC())
            again.pause()
            again.resume()
            assertEquals(1, again.refresh(RefreshScope.All, RefreshReason.MANUAL).updated)
        } finally {
            scope.cancel()
        }
    }

    /** T38: a restore through the app's pause starts no refresh of its own; nothing is requested afterwards either. */
    @Test
    fun t38_restoreStartsNoRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backend = FakeBackend()
        val coordinator = RefreshCoordinator(backend, { "a" }, { true }, {}, scope, java.time.Clock.systemUTC())
        val pause = object : BackgroundWork {
            override suspend fun pause(): Boolean {
                coordinator.pause()
                coordinator.state.first { !it.running }
                return true
            }

            override fun resume() = coordinator.resume()
        }
        val source = BackupDevice()
        val target = BackupDevice(idPrefix = "t", background = pause)
        try {
            RichState.build(source)
            assertEquals(RestoreOutcome.Restored, target.restore(source.backupBytes()))
            assertEquals(0, backend.calls.get())
            assertTrue(coordinator.state.value.requests.isEmpty())
            assertEquals(1L, target.service.replaced.value)
        } finally {
            scope.cancel()
            source.close()
            target.close()
        }
    }

    /** T42: after a restore a manual refresh fetches the restored feeds normally: known articles keep their state, new ones arrive. */
    @Test
    fun t42_manualRefreshAfterRestore() = runBlocking {
        val server = TestEnv.startServer()
        val items = AtomicInteger(3)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(200).addHeader("ETag", "\"v${items.get()}\"").body(
                "<rss version=\"2.0\"><channel><title>Served</title>" +
                    (1..items.get()).joinToString("") { "<item><guid>g$it</guid><title>t$it</title></item>" } + "</channel></rss>",
            ).build()
        }
        val source = BackupDevice()
        val target = BackupDevice(idPrefix = "t")
        try {
            val account = source.account()
            source.subscriptions.addFeed(account, server.literalUrl("/feed.xml").toString())
            source.env.backend.refresh(account, RefreshScope.All, RefreshReason.MANUAL) {}
            val first = source.env.db.articleDao().articlesOfFeed(source.env.db.backupDao().feeds(account).single().id)
            assertEquals(3, first.size)
            val readId = first.first { it.entryKey == "id:g1" }.id
            source.env.articles.setRead(readId, true)
            val bytes = source.backupBytes()
            val requestsBefore = server.requestCount

            assertEquals(RestoreOutcome.Restored, target.restore(bytes))
            assertEquals("no request during the restore", requestsBefore, server.requestCount)
            items.set(4)
            val report = target.env.backend.refresh(target.account(), RefreshScope.All, RefreshReason.MANUAL) {}
            assertTrue(report.isComplete)
            assertEquals(1, report.newArticles)
            assertEquals("a feed fetched before announces its new article", 1, report.newArticleIds.size)
            val feedId = target.env.db.backupDao().feeds(target.account()).single().id
            assertEquals(4, target.env.db.articleDao().articlesOfFeed(feedId).size)
            assertTrue(target.env.db.articleDao().state(readId)!!.isRead)
            assertEquals("\"v4\"", target.env.db.subscriptionDao().fetchState(feedId)!!.etag)
            server.takeRequest() // the source's own fetch
            assertEquals("the first request after the restore sends no old validators", null, server.takeRequest().headers["If-None-Match"])
        } finally {
            server.close()
            source.close()
            target.close()
        }
    }

    /** §8: the home list switches to the restored selection (last scope, filter, order, period) and leaves search; scroll starts at the top. */
    @Test
    fun homeListFollowsTheRestore() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val source = BackupDevice()
        val target = BackupDevice(idPrefix = "t")
        val viewModels = ArrayList<HomeViewModel>()
        try {
            val ids = RichState.build(source)
            val coordinator = RefreshCoordinator(target.env.backend, { target.account() }, { true }, {}, bg, target.clock)
            val vm = HomeViewModel(
                target.env.articles, target.env.subscriptions, target.settings, coordinator, flowOf(NetworkState(true, false)),
                SavedStateHandle(), target.clock, target.service.replaced,
            ).also { viewModels += it }
            withTimeout(5_000) { vm.selection.first { it == Selection() } }
            vm.selectFilter(ReadFilter.SAVED)
            vm.setSearchActive(true)
            vm.setSearchText("something")
            assertEquals(RestoreOutcome.Restored, target.restore(source.backupBytes()))
            val expected = Selection(ArticleScope.Category(ids.categories.getValue("domestic")), ReadFilter.UNREAD, SortOrder.OLDEST, "", PeriodFilter.DAYS_7)
            assertEquals(expected, withTimeout(5_000) { vm.selection.first { it == expected } })
            assertFalse(vm.searchActive.value)
            assertEquals("", vm.searchText.value)
        } finally {
            cancelAndJoinAll(viewModels.map { it.scopeJob } + bg.coroutineContext.job)
            source.close()
            target.close()
            Dispatchers.resetMain()
        }
    }

    /** §8 / T38: periodic updates switched on by a restore wait one interval before their first run; normal scheduling is unchanged. */
    @Test
    fun periodicWorkAfterARestoreWaitsOneInterval() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val wm = WorkManager.getInstance(context)
        fun info(): WorkInfo? = wm.getWorkInfosForUniqueWork(RefreshScheduler.WORK_NAME).get().firstOrNull { !it.state.isFinished }
        val scheduler = RefreshScheduler(context)
        scheduler.apply(AppSettings(onboardingDone = true, refreshIntervalMinutes = 0))
        assertEquals(null, info())
        scheduler.delayNextFirstRun()
        scheduler.apply(AppSettings(onboardingDone = true, refreshIntervalMinutes = 360))
        assertEquals(TimeUnit.HOURS.toMillis(6), info()!!.initialDelayMillis)
        // Only the first enqueue after the restore is delayed.
        scheduler.apply(AppSettings(onboardingDone = true, refreshIntervalMinutes = 0))
        scheduler.apply(AppSettings(onboardingDone = true, refreshIntervalMinutes = 120))
        assertEquals(0L, info()!!.initialDelayMillis)
    }
}
