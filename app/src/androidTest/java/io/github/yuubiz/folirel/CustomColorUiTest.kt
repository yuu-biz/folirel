package io.github.yuubiz.folirel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.ui.settings.CustomColorDialog
import io.github.yuubiz.folirel.ui.settings.CustomColorRow
import io.github.yuubiz.folirel.ui.theme.FolirelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Custom colour row and picker on a device (P3C-T23..T26). They are driven directly with callbacks that record what the settings page
 * would write, so a test sees exactly what is applied. Needs a device or an emulator; 0.9.3 was built where none was available, so this has
 * only been compiled (compileDebugAndroidTestKotlin), never run.
 */
@RunWith(AndroidJUnit4::class)
class CustomColorUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val applied = ArrayList<Int>()
    private var cancels = 0
    private val start = 0xFF009688.toInt()

    private fun showPicker(dark: Boolean = false) = compose.setContent {
        FolirelTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, 1f, ColorPalette.CUSTOM, start) {
            CustomColorDialog(initialSeed = start, dark = dark, storedPercent = 100, onApply = { applied += it }, onCancel = { cancels++ })
        }
    }

    @Test
    fun cancelAppliesNothing() {
        showPicker()
        compose.onNodeWithTag("custom_hex_field").performTextClearance()
        compose.onNodeWithTag("custom_hex_field").performTextInput("#FF8800")
        compose.onNodeWithTag("custom_cancel").performClick()
        assertTrue("nothing is applied by Cancel", applied.isEmpty())
        assertEquals(1, cancels)
    }

    @Test
    fun applyHandsOutTheChosenColourOnce() {
        showPicker()
        compose.onNodeWithTag("custom_hex_field").performTextClearance()
        compose.onNodeWithTag("custom_hex_field").performTextInput("#3366CC")
        compose.onNodeWithTag("custom_apply").assertIsEnabled().performClick()
        assertEquals(listOf(0xFF3366CC.toInt()), applied)
    }

    @Test
    fun anInvalidCodeShowsAMessageAndCannotBeApplied() {
        showPicker()
        compose.onNodeWithTag("custom_hex_field").performTextClearance()
        compose.onNodeWithTag("custom_hex_field").performTextInput("#12G45")
        compose.onNodeWithTag("custom_hex_error").assertIsDisplayed()
        compose.onNodeWithTag("custom_apply").assertIsNotEnabled()
        assertTrue(applied.isEmpty())
    }

    @Test
    fun touchingTheAreaChangesTheColour() {
        showPicker(dark = true)
        compose.onNodeWithTag("custom_sv_area").performTouchInput { click(topLeft + androidx.compose.ui.geometry.Offset(1f, 1f)) }
        compose.onNodeWithTag("custom_apply").performClick()
        assertEquals(1, applied.size)
        // The top-left corner of the area is white at the current hue (saturation 0, value 1).
        assertEquals(0xFFFFFFFF.toInt(), applied.single() or 0xFF000000.toInt())
    }

    @Test
    fun theRowShowsTheSeedAndResetIsOfferedOnlyWhenItIsNotTheDefault() {
        var seed by mutableStateOf(start)
        var resets = 0
        var picks = 0
        compose.setContent {
            FolirelTheme(ThemeMode.LIGHT, 1f, ColorPalette.CUSTOM, seed) {
                CustomColorRow(seed = seed, onPick = { picks++ }, onReset = { resets++; seed = AppSettings.DEFAULT_CUSTOM_SEED })
            }
        }
        compose.onNodeWithTag("custom_seed_hex").assertIsDisplayed()
        compose.onNodeWithTag("custom_pick").performClick()
        assertEquals(1, picks)
        compose.onNodeWithTag("custom_reset").performClick()
        compose.waitForIdle()
        assertEquals(1, resets)
        compose.onNodeWithTag("custom_reset").assertIsNotEnabled()
    }
}
