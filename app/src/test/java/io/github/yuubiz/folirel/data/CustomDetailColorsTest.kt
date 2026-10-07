package io.github.yuubiz.folirel.data

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.CustomColors
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.ui.settings.SettingsWriteQueue
import io.github.yuubiz.folirel.ui.theme.CustomPalette
import io.github.yuubiz.folirel.ui.theme.Palettes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.9.5: the colours of the Custom set set by hand, light and dark apart. Compatibility with 0.9.4 stores, independence of light and dark
 * and of the base colour, explicit regeneration, the stored form and its validation, a restart, no effect on the other sets, low contrast
 * that is saved as chosen, and a failed write that changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class CustomDetailColorsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context = TestEnv.context()
    private val seed = 0xFF8E24AA.toInt()

    @After
    fun tearDown() = scope.cancel()

    private fun file() = File(context.cacheDir, "custom-detail-${System.nanoTime()}.preferences_pb")

    private fun store(f: File = file(), s: CoroutineScope = scope) = PreferenceDataStoreFactory.create(scope = s) { f }

    private val handLight = CustomColors(0xFFC2185B.toInt(), 0xFFFFD8E4.toInt(), 0xFF5D4037.toInt(), 0xFFFFFDE7.toInt(), 0xFF212121.toInt())
    private val handDark = CustomColors(0xFFFFB300.toInt(), 0xFF5D4000.toInt(), 0xFF80CBC4.toInt(), 0xFF101820.toInt(), 0xFFFAFAFA.toInt())

    /** Every colour role of a scheme, so two schemes can be compared by value. */
    private fun roles(s: ColorScheme): List<Int> = listOf(
        s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer, s.inversePrimary, s.secondary, s.onSecondary, s.secondaryContainer,
        s.onSecondaryContainer, s.tertiary, s.onTertiary, s.tertiaryContainer, s.onTertiaryContainer, s.background, s.onBackground, s.surface,
        s.onSurface, s.surfaceVariant, s.onSurfaceVariant, s.surfaceTint, s.inverseSurface, s.inverseOnSurface, s.error, s.onError,
        s.errorContainer, s.onErrorContainer, s.outline, s.outlineVariant, s.scrim, s.surfaceBright, s.surfaceDim, s.surfaceContainer,
        s.surfaceContainerHigh, s.surfaceContainerHighest, s.surfaceContainerLow, s.surfaceContainerLowest,
    ).map { it.toArgb() }

    private fun drawn(s: AppSettings, dark: Boolean) =
        roles(Palettes.scheme(s.palette, dark, context, s.customSeed, if (dark) s.customDark else s.customLight))

    @Test
    fun theStoredFormRoundTripsAndOnlyACompleteValidValueIsAccepted() {
        assertEquals(handLight, CustomColors.decode(handLight.encode()))
        assertEquals(
            "accent=#C2185B;container=#FFD8E4;secondary=#5D4037;background=#FFFDE7;text=#212121",
            handLight.encode(),
        )
        val good = handLight.encode()
        for (bad in listOf(
            null, "", " ",
            good.replace("#212121", "#21212"), // five digits
            good.replace("#212121", "#2121211"), // seven digits
            good.replace("#212121", "#GG2121"), // not hexadecimal
            good.replace("#212121", "red"), // a colour name
            good.replace("#212121", "#FF212121"), // alpha
            good.replace(";text=#212121", ""), // a role missing
            "$good;text=#000000", // a role twice
            "$good;shadow=#000000", // an unknown role
            good.replace("accent=", "accent"), // no '='
        )) {
            assertNull("$bad", CustomColors.decode(bad))
        }
        // Spaces and lower case are fine; alpha given to with() is ignored.
        assertEquals(handLight, CustomColors.decode(good.lowercase().replace(";", " ; ")))
        assertEquals(0xFF123456.toInt(), handLight.with(CustomColors.Role.TEXT, 0x00123456).text)
        assertEquals(0xFF0A0B0C.toInt(), CustomColors.parseHex(" #0a0B0c "))
        assertNull(CustomColors.parseHex("#abc"))
    }

    /** A Custom user of 0.9.4 updates: nothing set by hand, so both modes are drawn exactly as before, and reading writes nothing. */
    @Test
    fun aStoreOf094LooksExactlyAsBeforeAndIsNotWritten() = runBlocking<Unit> {
        val st = store()
        st.edit {
            it[stringPreferencesKey("color_palette")] = "CUSTOM"
            it[intPreferencesKey("custom_seed")] = seed
        }
        val before = st.data.first()
        val s = SettingsRepository(st).current()
        assertNull(s.customLight)
        assertNull(s.customDark)
        for (dark in listOf(false, true)) assertEquals(roles(CustomPalette.scheme(seed, dark)), drawn(s, dark))
        assertEquals(before, st.data.first())
    }

    @Test
    fun lightAndDarkAreSetApartAndTheColoursAreUsedAsGiven() = runBlocking<Unit> {
        val repo = SettingsRepository(store())
        repo.applyCustomPalette(seed)
        repo.setCustomColors(dark = false, handLight)
        var s = repo.current()
        assertEquals(handLight, s.customLight)
        assertNull(s.customDark)
        // Light is the reader's five colours, dark is still made from the base colour.
        val light = Palettes.scheme(ColorPalette.CUSTOM, false, context, s.customSeed, s.customLight)
        assertEquals(handLight.accent, light.primary.toArgb())
        assertEquals(handLight.accentContainer, light.primaryContainer.toArgb())
        assertEquals(handLight.secondary, light.secondary.toArgb())
        assertEquals(handLight.background, light.surface.toArgb())
        assertEquals(handLight.background, light.background.toArgb())
        assertEquals(handLight.text, light.onSurface.toArgb())
        assertEquals(handLight.text, light.onBackground.toArgb())
        assertEquals(roles(CustomPalette.scheme(seed, true)), drawn(s, dark = true))

        repo.setCustomColors(dark = true, handDark)
        s = repo.current()
        assertEquals(handLight, s.customLight)
        assertEquals(handDark, s.customDark)
        assertEquals(handDark.text, Palettes.scheme(ColorPalette.CUSTOM, true, context, s.customSeed, s.customDark).onSurface.toArgb())
    }

    /** Changing the base colour (picker Apply or reset) keeps what was set by hand, and a mode set by hand does not follow the seed. */
    @Test
    fun theBaseColourNeverOverwritesColoursSetByHand() = runBlocking<Unit> {
        val repo = SettingsRepository(store())
        repo.applyCustomPalette(seed)
        repo.setCustomColors(dark = false, handLight)
        val lightBefore = drawn(repo.current(), dark = false)
        val darkBefore = drawn(repo.current(), dark = true)
        repo.applyCustomPalette(0xFF00838F.toInt())
        val after = repo.current()
        assertEquals(handLight, after.customLight)
        assertEquals(lightBefore, drawn(after, dark = false))
        // The mode that was not set by hand follows the new base colour.
        assertNotEquals(darkBefore, drawn(after, dark = true))
        repo.resetCustomSeed()
        assertEquals(handLight, repo.current().customLight)
        for (other in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt())) {
            assertEquals(roles(CustomPalette.detailed(handLight, false)), roles(CustomPalette.scheme(other, false, handLight)))
        }
    }

    @Test
    fun regenerationIsExplicitAndPerMode() = runBlocking<Unit> {
        val repo = SettingsRepository(store())
        repo.applyCustomPalette(seed)
        suspend fun setBoth() {
            repo.setCustomColors(false, handLight)
            repo.setCustomColors(true, handDark)
        }
        setBoth()
        repo.regenerateCustomColors(light = true, dark = false)
        assertNull(repo.current().customLight)
        assertEquals(handDark, repo.current().customDark)
        assertEquals(roles(CustomPalette.scheme(seed, false)), drawn(repo.current(), dark = false))

        setBoth()
        repo.regenerateCustomColors(light = false, dark = true)
        assertEquals(handLight, repo.current().customLight)
        assertNull(repo.current().customDark)

        setBoth()
        repo.regenerateCustomColors(light = true, dark = true)
        assertNull(repo.current().customLight)
        assertNull(repo.current().customDark)
        assertEquals(seed, repo.current().customSeed)
        assertEquals(ColorPalette.CUSTOM, repo.current().palette)
    }

    @Test
    fun theColoursSetByHandComeBackAfterARestart() = runBlocking<Unit> {
        val f = file()
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        SettingsRepository(store(f, first)).apply {
            applyCustomPalette(seed)
            setCustomColors(false, handLight)
            setCustomColors(true, handDark)
        }
        first.coroutineContext[Job]!!.cancelAndJoin()
        val second = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = SettingsRepository(store(f, second)).current()
        assertEquals(ColorPalette.CUSTOM, s.palette)
        assertEquals(seed, s.customSeed)
        assertEquals(handLight, s.customLight)
        assertEquals(handDark, s.customDark)
        second.coroutineContext[Job]!!.cancelAndJoin()
    }

    /** A damaged value reads as "not set": the mode is made from the base colour, nothing breaks. */
    @Test
    fun aDamagedStoredValueFallsBackToTheBaseColour() = runBlocking<Unit> {
        val st = store()
        st.edit {
            it[stringPreferencesKey("color_palette")] = "CUSTOM"
            it[intPreferencesKey("custom_seed")] = seed
            it[stringPreferencesKey("custom_light_colors")] = "accent=#C2185B;container=#nothex"
        }
        val s = SettingsRepository(st).current()
        assertNull(s.customLight)
        assertEquals(roles(CustomPalette.scheme(seed, false)), drawn(s, dark = false))
    }

    /** The other colour sets do not see the Custom colours at all. */
    @Test
    fun colourSetsOtherThanCustomAreNotAffected() {
        for (p in ColorPalette.entries - ColorPalette.CUSTOM - ColorPalette.DYNAMIC) for (dark in listOf(false, true)) {
            val hand = if (dark) handDark else handLight
            assertTrue("$p", Palettes.scheme(p, dark, context, seed, hand) === Palettes.fixedScheme(p, dark))
        }
    }

    /** Low contrast is the reader's choice: it is saved exactly, the scheme uses it, and the editor's check only reports it. */
    @Test
    fun hardToReadColoursAreSavedAsChosenWithAWarningOnly() = runBlocking<Unit> {
        val same = CustomColors(0xFF777777.toInt(), 0xFF777777.toInt(), 0xFF777777.toInt(), 0xFF777777.toInt(), 0xFF777777.toInt())
        assertTrue(CustomPalette.hasLowContrast(same, dark = false))
        assertFalse(CustomPalette.hasLowContrast(handLight, dark = false))
        assertFalse(CustomPalette.hasLowContrast(handDark, dark = true))
        val repo = SettingsRepository(store())
        repo.applyCustomPalette(seed)
        repo.setCustomColors(dark = false, same)
        val s = repo.current()
        assertEquals(same, s.customLight)
        val drawnScheme = Palettes.scheme(ColorPalette.CUSTOM, false, context, s.customSeed, s.customLight)
        assertEquals(0xFF777777.toInt(), drawnScheme.onSurface.toArgb())
        assertEquals(0xFF777777.toInt(), drawnScheme.surface.toArgb())
    }

    /** A store that holds [stored] and fails every write, the way a full disk would. */
    private class FailingStore(stored: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(stored)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
    }

    /** Save, Regenerate and the base colour go through the settings write queue: a failure is reported once and nothing is stored. */
    @Test
    fun aFailedWriteIsReportedAndTheStoredColoursStay() = runBlocking<Unit> {
        val stored = preferencesOf(
            stringPreferencesKey("color_palette") to "CUSTOM",
            intPreferencesKey("custom_seed") to seed,
            stringPreferencesKey("custom_dark_colors") to handDark.encode(),
        )
        val failing = FailingStore(stored)
        val failures = AtomicInteger()
        val queue = SettingsWriteQueue(scope, SettingsRepository(failing)) { failures.incrementAndGet() }
        queue.submit { setCustomColors(false, handLight) }
        queue.submit { setCustomColors(true, handLight) }
        queue.submit { regenerateCustomColors(light = true, dark = true) }
        withTimeout(5_000) { while (failures.get() < 3) kotlinx.coroutines.delay(10) }
        assertEquals("one report per failed write (the screen shows its message for each)", 3, failures.get())
        // What the screen draws is the store, so it goes back to what was stored: dark still set by hand, light from the base colour.
        val shown = SettingsRepository(failing).current()
        assertNull(shown.customLight)
        assertEquals(handDark, shown.customDark)
        assertEquals(seed, shown.customSeed)
    }
}
