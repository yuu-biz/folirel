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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The "confirm before updating on a metered network" setting ("do not ask again" in the dialog). */
@RunWith(AndroidJUnit4::class)
class MeteredConfirmSettingTest {
    /** Asks by default; "do not ask again" survives a restart and leaves the automatic-update rule alone; Settings can restore it. */
    @Test
    fun confirmationIsOnByDefaultPersistsAndDoesNotTouchTheAutomaticRule() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(TestEnv.context().cacheDir, "metered-${System.nanoTime()}.preferences_pb")
        val first = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
        assertTrue(first.current().confirmMeteredRefresh)
        assertTrue(first.current().unmeteredOnly)
        first.setConfirmMeteredRefresh(false)
        // Wait until the store has really let go of the file: a second store on the same file fails while the first is still registered.
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val second = SettingsRepository(PreferenceDataStoreFactory.create(scope = restartScope) { file })
        assertFalse("remembered after restart", second.current().confirmMeteredRefresh)
        assertTrue("automatic updates still wait for an unmetered network", second.current().unmeteredOnly)
        second.setConfirmMeteredRefresh(true)
        assertTrue(second.current().confirmMeteredRefresh)
        restartScope.cancel()
    }
}
