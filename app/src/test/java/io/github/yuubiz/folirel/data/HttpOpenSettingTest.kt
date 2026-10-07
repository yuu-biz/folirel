package io.github.yuubiz.folirel.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** U06 / U07: the "confirm before opening http:// articles" setting. */
@RunWith(AndroidJUnit4::class)
class HttpOpenSettingTest {
    private val main = File("src/main/java")

    /** U06: asks by default; the choice survives a restart (new repository on the same file); settings can restore it. */
    @Test
    fun confirmationIsOnByDefaultPersistsAndCanBeRestored() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(TestEnv.context().cacheDir, "http-open-${System.nanoTime()}.preferences_pb")
        val first = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
        assertTrue(first.current().confirmHttpOpen)
        first.setConfirmHttpOpen(false)
        // Wait until the store has really let go of the file: a second store on the same file fails while the first is still registered.
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val second = SettingsRepository(PreferenceDataStoreFactory.create(scope = restartScope) { file })
        assertFalse("remembered after restart", second.current().confirmHttpOpen)
        second.setConfirmHttpOpen(true)
        assertTrue(second.current().confirmHttpOpen)
        restartScope.cancel()
    }

    /** U07: it is a separate switch. Changing it never touches the HTTP download opt-in, and the reverse. */
    @Test
    fun confirmationIsIndependentFromHttpDownloadOptIn() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(TestEnv.context().cacheDir, "http-open-ind-${System.nanoTime()}.preferences_pb")
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
        assertFalse(repo.current().allowHttp)
        repo.setConfirmHttpOpen(false)
        assertFalse("silencing the question does not allow HTTP feeds or images", repo.current().allowHttp)
        repo.setAllowHttp(true)
        repo.setConfirmHttpOpen(true)
        assertTrue(repo.current().allowHttp)
        assertEquals(true, repo.current().confirmHttpOpen)
        scope.cancel()
    }

    /**
     * U07 (static): the network guard, the fetchers and the image loader never read the confirmation setting, and
     * nothing rewrites an http:// article URL to https://.
     */
    @Test
    fun networkCodeDoesNotDependOnTheConfirmation() {
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.relativeTo(main).path.replace('\\', '/') to it.readText() }
        val users = files.filter { (_, text) -> text.contains("confirmHttpOpen") }.keys
        assertEquals(
            setOf(
                // The full backup writes and reads the setting like every other one; it has no network path (BackupArchiveSecurityTest).
                "io/github/yuubiz/folirel/data/backup/BackupJson.kt",
                "io/github/yuubiz/folirel/data/preferences/SettingsRepository.kt",
                "io/github/yuubiz/folirel/ui/home/HomeScreen.kt",
                "io/github/yuubiz/folirel/ui/settings/SettingsPages.kt",
            ),
            users,
        )
        // The only writer that switches the question off from the dialog is reached from the dialog's "open" action.
        val screen = files.getValue("io/github/yuubiz/folirel/ui/home/HomeScreen.kt")
        assertEquals(1, Regex("vm\\.stopAskingBeforeHttpOpen\\(\\)").findAll(screen).count())
        assertTrue(screen.contains("if (dontAskAgain) vm.stopAskingBeforeHttpOpen()"))
        assertTrue("an http:// link is opened exactly as given", files.values.none { it.contains("replaceFirst(\"http://\", \"https://\")") && it.contains("browser.open") })
    }
}
