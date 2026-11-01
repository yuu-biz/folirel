package io.github.yuubiz.folirel.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.preferences.SwipeAction
import io.github.yuubiz.folirel.data.preferences.SwipeActions
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** F3: the swipe actions setting (on by default, which direction does what) and what a released swipe carries out. */
@RunWith(AndroidJUnit4::class)
class SwipeActionsSettingTest {
    private fun file() = File(TestEnv.context().cacheDir, "swipe-${System.nanoTime()}.preferences_pb")

    @Test
    fun aStoreWithoutTheKeyHasSwipeActionsOnAndReadingWritesNothing() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file() }
        store.edit { it[stringPreferencesKey("list_layout")] = "LIST" }
        val s = SettingsRepository(store).current()
        assertEquals(SwipeActions.RIGHT_READ_LEFT_SAVED, s.swipeActions)
        assertEquals(SwipeActions.RIGHT_READ_LEFT_SAVED, AppSettings().swipeActions)
        assertFalse(store.data.first().asMap().keys.any { it.name == "swipe_actions" })
        scope.cancel()
    }

    @Test
    fun theChoiceSurvivesARestartAndAnUnknownNameReadsAsTheDefault() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val f = file()
        val first = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { f })
        first.setSwipeActions(SwipeActions.OFF)
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = restartScope) { f }
        val second = SettingsRepository(store)
        assertEquals(SwipeActions.OFF, second.current().swipeActions)
        second.setSwipeActions(SwipeActions.RIGHT_SAVED_LEFT_READ)
        assertEquals(SwipeActions.RIGHT_SAVED_LEFT_READ, second.current().swipeActions)
        store.edit { it[stringPreferencesKey("swipe_actions")] = "FROM_A_LATER_VERSION" }
        assertEquals(SwipeActions.RIGHT_READ_LEFT_SAVED, second.current().swipeActions)
        restartScope.cancel()
    }

    @Test
    fun aReleasedSwipeActsOnlyPastTheThresholdAndInTheChosenDirection() {
        val threshold = 100f
        val default = SwipeActions.RIGHT_READ_LEFT_SAVED
        assertNull("a short swipe snaps back and changes nothing", default.actionAt(99f, threshold))
        assertNull(default.actionAt(-99f, threshold))
        assertNull(default.actionAt(0f, threshold))
        assertEquals(SwipeAction.READ, default.actionAt(100f, threshold))
        assertEquals(SwipeAction.SAVED, default.actionAt(-250f, threshold))

        val reversed = SwipeActions.RIGHT_SAVED_LEFT_READ
        assertEquals(SwipeAction.SAVED, reversed.actionAt(180f, threshold))
        assertEquals(SwipeAction.READ, reversed.actionAt(-180f, threshold))

        assertFalse(SwipeActions.OFF.enabled)
        assertNull(SwipeActions.OFF.actionAt(500f, threshold))
        assertNull(SwipeActions.OFF.actionAt(-500f, threshold))
    }
}
