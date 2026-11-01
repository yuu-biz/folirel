package io.github.yuubiz.folirel.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
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
import io.github.yuubiz.folirel.network.NetworkState
import io.github.yuubiz.folirel.testutil.MutableClock
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
        assertFalse(AutoRefreshPolicy.networkAllowed(ready.copy(refreshIntervalMinutes = 0), unmetered))
        // Startup refresh has its own switch.
        assertTrue(AutoRefreshPolicy.networkAllowed(ready.copy(refreshIntervalMinutes = 0), unmetered, RefreshReason.STARTUP))
        assertFalse(AutoRefreshPolicy.networkAllowed(ready.copy(startupRefresh = false), unmetered, RefreshReason.STARTUP))
        assertFalse(AutoRefreshPolicy.networkAllowed(ready, metered, RefreshReason.STARTUP))
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
        override suspend fun refresh(accountId: String, scope: RefreshScope, reason: RefreshReason, force: Boolean, onProgress: (RefreshProgress) -> Unit): RefreshReport {
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
        assertTrue(twoHours.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.HOURS.toMillis(2), twoHours.periodicityInfo!!.repeatIntervalMillis)

        scheduler.apply(ready.copy(refreshIntervalMinutes = 360, unmeteredOnly = false))
        val six = info()!!
        assertEquals(NetworkType.CONNECTED, six.constraints.requiredNetworkType)
        assertTrue(six.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.HOURS.toMillis(6), six.periodicityInfo!!.repeatIntervalMillis)

        scheduler.apply(ready.copy(refreshIntervalMinutes = 15))
        assertEquals(TimeUnit.MINUTES.toMillis(15), info()!!.periodicityInfo!!.repeatIntervalMillis)
        scheduler.apply(ready.copy(refreshIntervalMinutes = 30))
        assertEquals(TimeUnit.MINUTES.toMillis(30), info()!!.periodicityInfo!!.repeatIntervalMillis)

        scheduler.apply(ready.copy(refreshIntervalMinutes = 0))
        assertEquals(null, info())
    }

    @Test
    fun anInstallUpdatedFromAnOlderVersionGetsTheNewConstraintOnItsNextStart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val wm = WorkManager.getInstance(context)
        fun info(): WorkInfo = wm.getWorkInfosForUniqueWork(RefreshScheduler.WORK_NAME).get().single { !it.state.isFinished }
        // What 0.10.x registered: the network rule only.
        val old = PeriodicWorkRequestBuilder<RefreshWorker>(2, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        wm.enqueueUniquePeriodicWork(RefreshScheduler.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, old).result.get()
        assertFalse(info().constraints.requiresBatteryNotLow())

        RefreshScheduler(context).apply(ready) // a new process after the update
        val updated = info()
        assertEquals("the same work, updated in place", old.id, updated.id)
        assertTrue(updated.constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.UNMETERED, updated.constraints.requiredNetworkType)
    }
}
