package io.github.yuubiz.folirel.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** P2-A02/A03/D01/D02 at the storage level: the new colour key, the text-size grid, and what an old store reads as. */
@RunWith(AndroidJUnit4::class)
class PaletteSettingTest {
    private fun file() = File(TestEnv.context().cacheDir, "palette-${System.nanoTime()}.preferences_pb")

    /** A store written by 0.6.0 has no palette key: it reads as Classic and every other value is untouched (P2-T11, P2-D01). */
    @Test
    fun aStoreWithoutAPaletteKeyReadsAsClassicAndKeepsTheOtherValues() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file() }
        store.edit {
            it[stringPreferencesKey("theme")] = "DARK"
            it[floatPreferencesKey("text_scale")] = 1.15f
            it[stringPreferencesKey("list_layout")] = "BENTO"
        }
        val s = SettingsRepository(store).current()
        assertEquals(ColorPalette.CLASSIC, s.palette)
        assertEquals(ThemeMode.DARK, s.theme)
        assertEquals(1.15f, s.textScale, 0f)
        assertEquals(ListMode.BENTO, s.listMode)
        // Reading did not write the key: the file is as the old version left it.
        assertFalse(store.data.first().asMap().keys.any { it.name == "color_palette" })
        scope.cancel()
    }

    @Test
    fun theChoiceSurvivesARestartAndAnUnknownNameReadsAsClassic() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val f = file()
        val first = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { f })
        first.setPalette(ColorPalette.SAND)
        first.setTheme(ThemeMode.LIGHT)
        // Wait until the store has really let go of the file: a second store on the same file fails while the first is still registered.
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = restartScope) { f }
        val second = SettingsRepository(store)
        assertEquals(ColorPalette.SAND, second.current().palette)
        assertEquals("light/dark is its own setting", ThemeMode.LIGHT, second.current().theme)
        store.edit { it[stringPreferencesKey("color_palette")] = "FROM_A_LATER_VERSION" }
        assertEquals(ColorPalette.CLASSIC, second.current().palette)
        restartScope.cancel()
    }

    /** Dynamic is kept as stored, and it is drawn (and shown selected) as Classic where it does not exist (P2-A03). */
    @Test
    fun dynamicIsStoredAsChosenButDrawnAsClassicBeforeAndroid12() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file() })
        repo.setPalette(ColorPalette.DYNAMIC)
        val stored = repo.current().palette
        assertEquals(ColorPalette.DYNAMIC, stored)
        assertEquals(ColorPalette.CLASSIC, stored.effective(28))
        assertEquals(ColorPalette.CLASSIC, stored.effective(30))
        assertEquals(ColorPalette.DYNAMIC, stored.effective(31))
        assertEquals(ColorPalette.DYNAMIC, stored.effective(37))
        for (fixed in ColorPalette.entries - ColorPalette.DYNAMIC) assertEquals(fixed, fixed.effective(28))
        assertFalse(ColorPalette.isAvailable(ColorPalette.DYNAMIC, 30))
        assertTrue(ColorPalette.isAvailable(ColorPalette.DYNAMIC, 31))
        assertTrue(ColorPalette.isAvailable(ColorPalette.LEAF, 28))
        scope.cancel()
    }

    /** The four sizes older versions offered are on the new 5 % grid and read back unchanged; off-grid values are not accepted (P2-T16). */
    @Test
    fun textSizeKeepsTheOldValuesAndAcceptsTheFivePercentGrid() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file() }
        val repo = SettingsRepository(store)
        for (old in listOf(0.9f, 1.0f, 1.15f, 1.3f)) {
            repo.setTextScale(old)
            assertEquals(old, repo.current().textScale, 0f)
        }
        for (percent in 90..130 step 5) {
            val value = AppSettings.textScaleOf(percent)
            assertTrue("$percent is on the grid", AppSettings.isTextScale(value))
            repo.setTextScale(value)
            assertEquals(percent, AppSettings.textScalePercent(repo.current().textScale))
        }
        assertEquals(9, (90..130 step 5).count())
        for (bad in listOf(0.85f, 1.07f, 1.31f, 1.35f, 2.0f, 0f, -1f)) {
            assertFalse("$bad is not a size", AppSettings.isTextScale(bad))
            store.edit { it[floatPreferencesKey("text_scale")] = bad }
            assertEquals("$bad reads as 100 %", 1.0f, repo.current().textScale, 0f)
        }
        scope.cancel()
    }

    /** Many quick changes: the last one stays (P2-D02, P2-T09). */
    @Test
    fun theLastOfManyQuickChangesIsTheOneThatIsKept() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file() })
        val palettes = ColorPalette.entries
        val modes = ThemeMode.entries
        repeat(60) { i ->
            repo.setPalette(palettes[i % palettes.size])
            repo.setTheme(modes[i % modes.size])
        }
        assertEquals(palettes[59 % palettes.size], repo.current().palette)
        assertEquals(modes[59 % modes.size], repo.current().theme)
        scope.cancel()
    }
}
