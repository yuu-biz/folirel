package io.github.yuubiz.folirel.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.CategoryNavigation
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.model.ListMode
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** RSP-T07 / T08 / T39 at the storage level: the column choices 0..5, and the category navigation choice with what an old store reads as. */
@RunWith(AndroidJUnit4::class)
class ResponsiveSettingsTest {
    private fun file() = File(TestEnv.context().cacheDir, "responsive-${System.nanoTime()}.preferences_pb")

    /** A store written by 0.7.2 has no navigation key and columns 0..3: they read as they were, navigation is Auto, nothing is written by reading. */
    @Test
    fun aStoreFrom072ReadsAsItWasAndNavigationIsAuto() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        for (columns in 0..3) {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file() }
            store.edit {
                it[intPreferencesKey("card_columns")] = columns
                it[stringPreferencesKey("list_layout")] = "MAGAZINE"
            }
            val s = SettingsRepository(store).current()
            assertEquals(columns, s.cardColumns)
            assertEquals(ListMode.MAGAZINE, s.listMode)
            assertEquals(CategoryNavigation.AUTO, s.categoryNavigation)
            assertFalse(store.data.first().asMap().keys.any { it.name == "category_navigation" })
        }
        assertEquals(CategoryNavigation.AUTO, AppSettings().categoryNavigation)
        scope.cancel()
    }

    @Test
    fun fourAndFiveColumnsAreStoredAndSixOrNegativeReadsAsAutomatic() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file() }
        val repo = SettingsRepository(store)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), AppSettings.CARD_COLUMNS)
        for (columns in AppSettings.CARD_COLUMNS) {
            repo.setCardColumns(columns)
            assertEquals(columns, repo.current().cardColumns)
        }
        for (bad in listOf(6, 9, -1)) {
            store.edit { it[intPreferencesKey("card_columns")] = bad }
            assertEquals("a stored $bad is not a choice", 0, repo.current().cardColumns)
        }
        scope.cancel()
    }

    /** RSP-T39 (storage part): the pane choice and the columns survive a restart of the store; an unknown name reads as Auto. */
    @Test
    fun theNavigationChoiceAndColumnsSurviveARestartAndAnUnknownNameReadsAsAuto() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val f = file()
        val first = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { f })
        first.setCategoryNavigation(CategoryNavigation.PANE)
        first.setCardColumns(5)
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = restartScope) { f }
        val second = SettingsRepository(store)
        assertEquals(CategoryNavigation.PANE, second.current().categoryNavigation)
        assertEquals(5, second.current().cardColumns)
        second.setCategoryNavigation(CategoryNavigation.DRAWER)
        assertEquals(CategoryNavigation.DRAWER, second.current().categoryNavigation)
        store.edit { it[stringPreferencesKey("category_navigation")] = "FROM_A_LATER_VERSION" }
        assertEquals(CategoryNavigation.AUTO, second.current().categoryNavigation)
        assertEquals("the columns are untouched", 5, second.current().cardColumns)
        restartScope.cancel()
    }
}
