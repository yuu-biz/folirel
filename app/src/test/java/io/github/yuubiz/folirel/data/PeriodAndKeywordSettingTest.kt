package io.github.yuubiz.folirel.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.HiddenKeywords.AddResult
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.PeriodFilter
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** P3A-T15/T16 (the period is kept), T24/T25 at the store, T34 (a store from 0.8.0) and T26/T27 (switch, delete). */
@RunWith(AndroidJUnit4::class)
class PeriodAndKeywordSettingTest {
    private fun file() = File(TestEnv.context().cacheDir, "p3a-${System.nanoTime()}.preferences_pb")

    @Test
    fun aStoreFromVersion080ReadsWithTheNewDefaultsAndIsNotWrittenToByReading() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file() }
        // What 0.8.0 left behind: its own keys only.
        store.edit {
            it[stringPreferencesKey("theme")] = "DARK"
            it[stringPreferencesKey("list_layout")] = "BENTO"
            it[stringPreferencesKey("color_palette")] = "SAND"
            it[stringPreferencesKey("category_navigation")] = "PANE"
            it[stringPreferencesKey("last_scope")] = "category:c1"
            it[stringPreferencesKey("last_filter")] = "UNREAD"
        }
        val s = SettingsRepository(store).current()
        assertEquals(PeriodFilter.ALL, s.periodFilter)
        assertEquals(emptyList<String>(), s.hiddenKeywords)
        assertFalse(s.hiddenKeywordsEnabled)
        assertEquals(emptyList<String>(), s.activeHiddenKeywords)
        // Everything the user had is as it was.
        assertEquals(ThemeMode.DARK, s.theme)
        assertEquals(ListMode.BENTO, s.listMode)
        assertEquals(ArticleScope.Category("c1"), s.lastScope)
        // The last tab is still stored, but a start opens All until the reader turns "open the last tab" on.
        assertFalse(s.restoreLastScope)
        assertEquals(ArticleScope.All, s.startScope)
        val keys = store.data.first().asMap().keys.map { it.name }
        assertFalse("period_filter" in keys || "hidden_keywords" in keys || "hidden_keywords_enabled" in keys)
        scope.cancel()
    }

    @Test
    fun thePeriodSurvivesARestartAndAnUnknownNameReadsAsAll() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val f = file()
        val first = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { f })
        first.setPeriodFilter(PeriodFilter.DAYS_7)
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = restartScope) { f }
        val second = SettingsRepository(store)
        assertEquals(PeriodFilter.DAYS_7, second.current().periodFilter)
        store.edit { it[stringPreferencesKey("period_filter")] = "YEARS_9" }
        assertEquals(PeriodFilter.ALL, second.current().periodFilter)
        restartScope.cancel()
    }

    @Test
    fun theFirstKeywordSwitchesTheFilterOnAndTheOrderAndSwitchAreKept() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val f = file()
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { f })
        assertTrue(repo.addHiddenKeyword("  AI ") is AddResult.Added)
        assertTrue(repo.current().hiddenKeywordsEnabled)
        repo.setHiddenKeywordsEnabled(false)
        // A second keyword does not turn a filter the reader switched off back on.
        assertTrue(repo.addHiddenKeyword("機械学習") is AddResult.Added)
        assertTrue(repo.addHiddenKeyword("b") is AddResult.Added)
        var s = repo.current()
        assertEquals(listOf("AI", "機械学習", "b"), s.hiddenKeywords)
        assertFalse(s.hiddenKeywordsEnabled)
        assertEquals("off means none in effect", emptyList<String>(), s.activeHiddenKeywords)
        repo.setHiddenKeywordsEnabled(true)
        assertEquals(listOf("AI", "機械学習", "b"), repo.current().activeHiddenKeywords)
        // Delete removes one and keeps the order of the rest.
        repo.removeHiddenKeyword("機械学習")
        s = repo.current()
        assertEquals(listOf("AI", "b"), s.hiddenKeywords)
        assertTrue(s.hiddenKeywordsEnabled)
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val again = SettingsRepository(PreferenceDataStoreFactory.create(scope = restartScope) { f }).current()
        assertEquals(listOf("AI", "b"), again.hiddenKeywords)
        assertTrue(again.hiddenKeywordsEnabled)
        restartScope.cancel()
    }

    @Test
    fun theStoreRefusesBlankDuplicateAndTooManyWithoutChangingAnything() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file() })
        assertEquals(AddResult.Blank, repo.addHiddenKeyword("   "))
        assertEquals(emptyList<String>(), repo.current().hiddenKeywords)
        assertFalse("a refused first keyword does not switch the filter on", repo.current().hiddenKeywordsEnabled)
        repo.addHiddenKeyword("Ai")
        assertEquals(AddResult.Duplicate, repo.addHiddenKeyword("aI"))
        assertEquals(AddResult.TooLong, repo.addHiddenKeyword("x".repeat(65)))
        repeat(31) { assertTrue(repo.addHiddenKeyword("w$it") is AddResult.Added) }
        assertEquals(32, repo.current().hiddenKeywords.size)
        assertEquals(AddResult.ListFull, repo.addHiddenKeyword("overflow"))
        assertEquals(32, repo.current().hiddenKeywords.size)
        scope.cancel()
    }

    @Test
    fun quickAddsAllLandBecauseTheListIsReadInsideTheWrite() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file() })
        (0 until 20).map { i -> async(Dispatchers.IO) { repo.addHiddenKeyword("k$i") } }.awaitAll()
        assertEquals((0 until 20).map { "k$it" }.toSet(), repo.current().hiddenKeywords.toSet())
        assertEquals(20, repo.current().hiddenKeywords.size)
        scope.cancel()
    }
}
