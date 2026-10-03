package io.github.yuubiz.feeddeck.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.yuubiz.feeddeck.backend.BackendCapabilities
import io.github.yuubiz.feeddeck.backend.BackendSynchronizer
import io.github.yuubiz.feeddeck.backend.RefreshProgress
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshReport
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.data.preferences.AppSettings
import io.github.yuubiz.feeddeck.network.NetworkState
import io.github.yuubiz.feeddeck.testutil.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** T25: periodic OFF, unmetered-only, startup 30 minutes, automatic vs manual. */
@RunWith(AndroidJUnit4::class)
class UpdateConstraintsTest {
    private val unmetered = NetworkState(connected = true, metered = false)
    private val metered = NetworkState(connected = true, metered = true)
    private val offline = NetworkState(connected = false, metered = true)
    private val ready = AppSettings(onboardingDone = true)

    @Test
    fun automaticNetworkRule() {
        assertTrue(AutoRefreshPolicy.networkAllowed(ready, unmetered))
        assertFalse(AutoRefreshPolicy.networkAllowed(ready, metered))
        assertTrue(AutoRefreshPolicy.networkAllowed(ready.copy(unmeteredOnly = false), metered))
        assertFalse(AutoRefreshPolicy.networkAllowed(ready, offline))
        assertFalse(AutoRefreshPolicy.networkAllowed(ready.copy(refreshIntervalHours = 0), unmetered))
        assertFalse(AutoRefreshPolicy.networkAllowed(AppSettings(onboardingDone = false), unmetered))
    }

    @Test
    fun startupEvery30MinutesFromLastAttempt() {
        val last = 1_000_000L
        assertFalse(AutoRefreshPolicy.startupDue(last + 29 * 60_000, last))
        assertTrue(AutoRefreshPolicy.startupDue(last + 30 * 60_000, last))
        assertTrue("never attempted", AutoRefreshPolicy.startupDue(1_790_000_000_000L, 0))
    }

    private class CountingBackend : BackendSynchronizer {
        val calls = AtomicInteger()
        override val backendType = "FAKE"
        override val capabilities = BackendCapabilities(subscriptionEditing = true, remoteStateSync = false)
        override suspend fun refresh(accountId: String, scope: RefreshScope, reason: RefreshReason, onProgress: (RefreshProgress) -> Unit): RefreshReport {
            calls.incrementAndGet()
            return RefreshReport(total = 1, updated = 1)
        }
    }

    @Test
    fun coordinatorBlocksAutomaticButNotManual() = runBlocking {
        val backend = CountingBackend()
        var allowed = false
        val attempts = mutableListOf<Long>()
        val clock = MutableClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = RefreshCoordinator(backend, { "acc" }, { allowed }, { attempts += it }, scope, clock)

        val blocked = coordinator.refresh(RefreshScope.All, RefreshReason.STARTUP)
        assertEquals(1, blocked.deferredConstraint)
        assertFalse(blocked.isComplete)
        assertEquals(0, backend.calls.get())
        assertTrue(attempts.isEmpty())
        assertTrue(coordinator.state.value.lastAutoBlocked)

        assertEquals(1, coordinator.refresh(RefreshScope.All, RefreshReason.MANUAL).updated)
        assertEquals(1, backend.calls.get())
        // Any started refresh counts as an attempt for the startup 30-minute rule; a blocked one does not.
        assertEquals(listOf(clock.millis()), attempts)

        allowed = true
        clock.advanceMinutes(5)
        coordinator.refresh(RefreshScope.All, RefreshReason.PERIODIC)
        assertEquals(2, backend.calls.get())
        assertEquals(2, attempts.size)
        assertEquals(clock.millis(), attempts.last())
        scope.cancel()
    }

    @Test
    fun schedulerFollowsSettings() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val wm = WorkManager.getInstance(context)
        val scheduler = RefreshScheduler(context)
        fun info(): WorkInfo? = wm.getWorkInfosForUniqueWork(RefreshScheduler.WORK_NAME).get().firstOrNull { !it.state.isFinished }

        scheduler.apply(AppSettings(onboardingDone = false))
        assertEquals(null, info())

        scheduler.apply(ready) // default: every 2 h, unmetered only
        val twoHours = info()!!
        assertEquals(NetworkType.UNMETERED, twoHours.constraints.requiredNetworkType)
        assertEquals(TimeUnit.HOURS.toMillis(2), twoHours.periodicityInfo!!.repeatIntervalMillis)

        scheduler.apply(ready.copy(refreshIntervalHours = 6, unmeteredOnly = false))
        val six = info()!!
        assertEquals(NetworkType.CONNECTED, six.constraints.requiredNetworkType)
        assertEquals(TimeUnit.HOURS.toMillis(6), six.periodicityInfo!!.repeatIntervalMillis)

        scheduler.apply(ready.copy(refreshIntervalHours = 0))
        assertEquals(null, info())
    }
}
