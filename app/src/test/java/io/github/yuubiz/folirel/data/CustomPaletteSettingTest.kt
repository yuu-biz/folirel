package io.github.yuubiz.folirel.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** P3C-T16, T17, T24 (once), T26, T27 (0.9.3): the Custom colour set and its base colour in the settings store. */
@RunWith(AndroidJUnit4::class)
class CustomPaletteSettingTest {
    private fun file() = File(TestEnv.context().cacheDir, "custom-palette-${System.nanoTime()}.preferences_pb")

    /** A store written by 0.9.2 has neither the Custom name nor a seed: nothing changes for an install that never chose Custom. */
    @Test
    fun aStoreFromBefore093ReadsAsItDidAndWritesNothing() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file() }
        store.edit { it[stringPreferencesKey("color_palette")] = "VIOLET" }
        val s = SettingsRepository(store).current()
        assertEquals(ColorPalette.VIOLET, s.palette)
        assertEquals(AppSettings.DEFAULT_CUSTOM_SEED, s.customSeed)
        assertFalse(store.data.first().asMap().keys.any { it.name == "custom_seed" })
        // A fresh install is Classic with the default seed.
        val fresh = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file() }).current()
        assertEquals(ColorPalette.CLASSIC, fresh.palette)
        assertEquals(AppSettings.DEFAULT_CUSTOM_SEED, fresh.customSeed)
        scope.cancel()
    }

    @Test
    fun customAndItsSeedSurviveARestartAndOtherSettingsAreUntouched() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val f = file()
        val first = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { f })
        first.setTheme(ThemeMode.DARK)
        first.applyCustomPalette(0xFF8E24AA.toInt())
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val second = SettingsRepository(PreferenceDataStoreFactory.create(scope = restartScope) { f }).current()
        assertEquals(ColorPalette.CUSTOM, second.palette)
        assertEquals(0xFF8E24AA.toInt(), second.customSeed)
        assertEquals(ThemeMode.DARK, second.theme)
        restartScope.cancel()
    }

    /** The seed is an RGB colour: whatever alpha is given or found in a store, it is read as opaque. */
    @Test
    fun theSeedIsAlwaysOpaque() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file() }
        val repo = SettingsRepository(store)
        repo.applyCustomPalette(0x00123456)
        assertEquals(0xFF123456.toInt(), repo.current().customSeed)
        store.edit { it[intPreferencesKey("custom_seed")] = 0x11ABCDEF }
        assertEquals(0xFFABCDEF.toInt(), repo.current().customSeed)
        scope.cancel()
    }

    /** The picker's Apply is one write: no state is ever read with Custom chosen and the old seed, or the new seed under another set. */
    @Test
    fun applyWritesTheSeedAndTheSetInOneStep() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file() })
        repo.setPalette(ColorPalette.LEAF)
        val seen = CopyOnWriteArrayList<Pair<ColorPalette, Int>>()
        val started = CompletableDeferred<Unit>()
        val collecting = scope.launch { repo.settings.collect { seen += it.palette to it.customSeed; started.complete(Unit) } }
        // The collector has the starting state before the write is made.
        started.await()
        repo.applyCustomPalette(0xFF3366CC.toInt())
        repo.settings.first { it.palette == ColorPalette.CUSTOM }
        collecting.cancelAndJoin()
        assertEquals(ColorPalette.LEAF to AppSettings.DEFAULT_CUSTOM_SEED, seen.first())
        assertEquals(ColorPalette.CUSTOM to 0xFF3366CC.toInt(), seen.last())
        assertTrue("no intermediate state: $seen", seen.all { it == seen.first() || it == seen.last() })
        scope.cancel()
    }

    /** Choosing another set keeps the seed (Custom comes back as it was); reset returns the seed only. */
    @Test
    fun anotherSetKeepsTheSeedAndResetReturnsToTheDefault() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file() })
        repo.applyCustomPalette(0xFF3366CC.toInt())
        repo.setPalette(ColorPalette.SAND)
        assertEquals(0xFF3366CC.toInt(), repo.current().customSeed)
        repo.setPalette(ColorPalette.CUSTOM)
        assertEquals(0xFF3366CC.toInt(), repo.current().customSeed)
        repo.resetCustomSeed()
        val s = repo.current()
        assertEquals(AppSettings.DEFAULT_CUSTOM_SEED, s.customSeed)
        assertEquals("reset changes the colour, not the set", ColorPalette.CUSTOM, s.palette)
        scope.cancel()
    }

    @Test
    fun customIsAlwaysAvailableAndNeverFallsBack() {
        for (sdk in listOf(28, 30, 31, 37)) {
            assertTrue(ColorPalette.isAvailable(ColorPalette.CUSTOM, sdk))
            assertEquals(ColorPalette.CUSTOM, ColorPalette.CUSTOM.effective(sdk))
        }
    }
}
