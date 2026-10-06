package io.github.yuubiz.folirel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not an assertion test (skipped unless `-e p2evidence true`): stores a colour set and a mode (Sand, Dark) so that a check made from
 * outside (adb: open Settings > Appearance, Home, `am kill`, launch again) can see them come back after the process was killed.
 */
@RunWith(AndroidJUnit4::class)
class P2StateSetup {
    @Before
    fun onlyWhenAsked() {
        Assume.assumeTrue("evidence helper (pass -e p2evidence true)", InstrumentationRegistry.getArguments().getString("p2evidence") == "true")
    }

    @Test
    fun sandAndDark() = runBlocking<Unit> {
        TestData.reset()
        TestData.seedCategories(listOf("Alpha", "Beta"), perCategory = 5)
        TestData.container.settings.setPalette(ColorPalette.SAND)
        TestData.container.settings.setTheme(ThemeMode.DARK)
    }
}
