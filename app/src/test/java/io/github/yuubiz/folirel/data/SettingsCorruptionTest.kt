package io.github.yuubiz.folirel.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A settings file that cannot be decoded must not stop the app at every start (that would leave only "clear storage", which deletes the database). */
@RunWith(AndroidJUnit4::class)
class SettingsCorruptionTest {
    private fun corruptFile() = File(TestEnv.context().cacheDir, "corrupt-${System.nanoTime()}.preferences_pb").apply {
        writeBytes(byteArrayOf(0x0A, 0x7F, 0x12, 0x34, 0x56)) // a length that runs past the end: not a valid protobuf
    }

    @Test
    fun withoutTheHandlerTheFileFailsEveryRead() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { corruptFile() }
        val failure = runCatching { SettingsRepository(store).current() }.exceptionOrNull()
        assertTrue("$failure", failure is CorruptionException)
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun theAppsHandlerReplacesTheFileWithTheDefaultsAndKeepsWorking() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = corruptFile()
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(corruptionHandler = SettingsRepository.corruptionHandler, scope = scope) { file })
        val read = repo.current()
        assertEquals(AppSettings(), read)
        assertEquals(false, read.allowHttp)
        // The replaced file is a normal store again: a change is written and read back after a restart.
        repo.setTheme(ThemeMode.DARK)
        scope.coroutineContext[Job]!!.cancelAndJoin()
        val restart = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        assertEquals(ThemeMode.DARK, SettingsRepository(PreferenceDataStoreFactory.create(scope = restart) { file }).current().theme)
        restart.coroutineContext[Job]!!.cancelAndJoin()
    }
}
