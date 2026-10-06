package io.github.yuubiz.folirel.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** A write that cannot be stored is reported once, changes nothing that is shown, and does not stop the writes after it (P2 follow-up). */
@RunWith(AndroidJUnit4::class)
class SettingsWriteQueueTest {
    /** A store whose every write fails, the way a full disk or a failing file system would. */
    private class FailingStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
    }

    @Test
    fun aFailedWriteIsReportedOnceLeavesTheStoredValueAndDoesNotStopTheQueue() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failures = AtomicInteger()
        val queue = SettingsWriteQueue(scope, SettingsRepository(FailingStore())) { failures.incrementAndGet() }
        queue.submit { setPalette(ColorPalette.LEAF) }
        queue.submit { setTheme(ThemeMode.DARK) }
        withTimeout(5_000) { while (failures.get() < 2) kotlinx.coroutines.delay(10) }
        assertEquals("one report per failed write, no retry", 2, failures.get())
        kotlinx.coroutines.delay(200)
        assertEquals(2, failures.get())
        // What the screen reads is the store: nothing was claimed as saved.
        val shown = SettingsRepository(FailingStore()).current()
        assertEquals(ColorPalette.CLASSIC, shown.palette)
        assertEquals(ThemeMode.SYSTEM, shown.theme)
        scope.cancel()
    }

    @Test
    fun aWriteAfterAFailedOneStillHappensAndTheOrderIsKept() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(TestEnv.context().cacheDir, "queue-${System.nanoTime()}.preferences_pb")
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
        val failures = AtomicInteger()
        val queue = SettingsWriteQueue(scope, repo) { failures.incrementAndGet() }
        queue.submit { setPalette(ColorPalette.SAND) }
        queue.submit { throw IOException("the write failed halfway") }
        queue.submit { setPalette(ColorPalette.VIOLET) }
        queue.submit { setTheme(ThemeMode.LIGHT) }
        withTimeout(5_000) { while (repo.current().theme != ThemeMode.LIGHT) kotlinx.coroutines.delay(10) }
        assertEquals(ColorPalette.VIOLET, repo.current().palette)
        assertEquals(1, failures.get())
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
