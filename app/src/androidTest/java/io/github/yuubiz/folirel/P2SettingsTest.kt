package io.github.yuubiz.folirel

import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.BrowserMode
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.CategoryDisplay
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.ResolvedLayout
import io.github.yuubiz.folirel.ui.theme.Palettes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Phase 2 on the real app (MainActivity, DataStore, Room): the Settings pages, the colour sets, the controls chosen for each
 * setting, and the layout picker shared with the category settings. Every test starts from a clean store ([TestData.reset]).
 */
@RunWith(AndroidJUnit4::class)
class P2SettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings get() = TestData.container.settings
    private fun stored(): AppSettings = runBlocking { settings.current() }
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)

    @Before
    fun clean() {
        TestData.reset()
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        until { exists("drawer_open") || exists("settings_top") || anyPage() }
        compose.waitForIdle()
    }

    private fun anyPage() = SettingsPageTags.any { exists(it) }
    private val SettingsPageTags = listOf("settings_page_APPEARANCE", "settings_page_LAYOUT", "settings_page_OPEN", "settings_page_REFRESH", "settings_page_STORAGE", "settings_page_ABOUT")

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun until(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        compose.waitUntil(timeoutMs) { condition() }
    }

    private fun click(tag: String) {
        until { exists(tag) }
        // Not every control is in something that scrolls (the top bar); where it is, bring it into view first.
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun openSettingsTop() {
        click("drawer_open")
        click("drawer_item_settings")
        until { exists("settings_top") }
    }

    private fun openPage(page: String) {
        if (!exists("settings_top")) openSettingsTop()
        click("settings_row_$page")
        until { exists("settings_page_$page") }
    }

    private fun back() {
        device.pressBack()
        compose.waitForIdle()
    }

    private fun waitStored(what: String, check: (AppSettings) -> Boolean) {
        try {
            until(5_000) { check(stored()) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("$what: not stored, settings now ${stored()}")
        }
    }

    /** The colours of the preview, read from what is drawn on the screen: whether [argb] (within a few steps) is there. */
    private fun previewHas(argb: Int, tag: String = "appearance_preview"): Boolean {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        fun near(a: Int, b: Int) = abs(a - b) <= 2
        val matching = pixels.count { p ->
            near((p shr 16) and 255, (argb shr 16) and 255) && near((p shr 8) and 255, (argb shr 8) and 255) && near(p and 255, argb and 255)
        }
        return matching >= 12
    }

    // ---- P2-B01: the top page

    @Test
    fun theTopPageIsShortAndEachRowSaysWhatItHolds() {
        launch()
        openSettingsTop()
        val rows = listOf("APPEARANCE", "LAYOUT", "OPEN", "REFRESH", "STORAGE", "ABOUT")
        rows.forEach { compose.onNodeWithTag("settings_row_$it").performScrollTo().assertExists() }
        // No setting control of any page is on the top page.
        listOf("theme_DARK", "palette_LEAF", "text_size_slider", "interval_row", "retention_row", "allow_http", "settings_layout_MAGAZINE").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        // A summary under each: the values as they are now.
        compose.onNodeWithText(listOf(str(R.string.theme_system), str(R.string.palette_classic), str(R.string.percent_value, 100)).joinToString(" · ")).assertExists()
        compose.onNodeWithText(listOf(str(R.string.nav_mode_auto), str(R.string.list_mode_list)).joinToString(" · ")).assertExists()
        compose.onNodeWithText(str(R.string.browser_custom_tabs)).assertExists()
        compose.onNodeWithText(listOf(str(R.string.settings_summary_no_limit), str(R.string.settings_summary_images_mb, 128)).joinToString(" · ")).assertExists()
        // 2 hours and the unmetered rule (the defaults of TestData.reset: automatic updates are off there, so the summary says that).
        compose.onNodeWithText(str(R.string.settings_summary_refresh_off)).assertExists()
        // One step down and back: the row leads to its page and the system back returns to the top.
        click("settings_row_APPEARANCE")
        until { exists("settings_page_APPEARANCE") }
        back()
        until { exists("settings_top") }
        back()
        until { exists("drawer_open") }
    }

    // ---- P2-A: colours

    @Test
    fun everyPaletteIsDrawnInLightAndDarkAndPhotosAreNotTinted() {
        launch()
        openPage("APPEARANCE")
        var photoPixel: Int? = null
        for (dark in listOf(false, true)) {
            runBlocking { settings.setTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) }
            for (palette in listOf(ColorPalette.CLASSIC, ColorPalette.LEAF, ColorPalette.SAND, ColorPalette.VIOLET)) {
                click("palette_${palette.name}")
                waitStored("$palette") { it.palette == palette }
                compose.waitForIdle()
                val scheme = Palettes.fixedScheme(palette, dark)
                compose.onNodeWithTag("palette_${palette.name}").assertIsSelected()
                assertTrue("$palette ${if (dark) "dark" else "light"}: the accent is on screen", previewHas(scheme.primary.toArgb()))
                assertTrue("$palette ${if (dark) "dark" else "light"}: the surface is on screen", previewHas(scheme.surface.toArgb()))
                // The photograph keeps its own colours: the same pixel in every palette and in both modes.
                val bitmap = compose.onNodeWithTag("preview_photo").captureToImage().asAndroidBitmap()
                val centre = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                if (photoPixel == null) photoPixel = centre
                // The same colour, within one step per channel (a software renderer dithers a gradient by one level).
                fun channel(c: Int, shift: Int) = (c shr shift) and 255
                for (shift in listOf(16, 8, 0)) {
                    assertTrue("the picture is not tinted by $palette (${photoPixel!!.toString(16)} vs ${centre.toString(16)})", abs(channel(photoPixel!!, shift) - channel(centre, shift)) <= 2)
                }
            }
        }
        // Light/dark and the palette are two settings: changing one never changed the other.
        assertEquals(ThemeMode.DARK, stored().theme)
        assertEquals(ColorPalette.VIOLET, stored().palette)
    }

    @Test
    fun theLastOfManyQuickPaletteAndModeChoicesIsTheOneKept() {
        launch()
        openPage("APPEARANCE")
        val taps = listOf(ColorPalette.LEAF, ColorPalette.SAND, ColorPalette.VIOLET, ColorPalette.CLASSIC, ColorPalette.LEAF, ColorPalette.SAND, ColorPalette.VIOLET, ColorPalette.LEAF)
        val modes = listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM, ThemeMode.LIGHT)
        for (i in taps.indices) {
            compose.onNodeWithTag("palette_${taps[i].name}").performClick()
            if (i < modes.size) compose.onNodeWithTag("theme_${modes[i].name}").performClick()
        }
        waitStored("last palette") { it.palette == taps.last() }
        waitStored("last mode") { it.theme == modes.last() }
        compose.waitForIdle()
        compose.onNodeWithTag("palette_${taps.last().name}").assertIsSelected()
        compose.onNodeWithTag("theme_${modes.last().name}").assertIsSelected()
        for (other in ColorPalette.entries - taps.last()) compose.onNodeWithTag("palette_${other.name}").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    /** P2-T07 */
    @Test
    fun dynamicColorIsChosenAndDrawnOnAndroid12AndLater() {
        assumeTrue("Android 12 or later", Build.VERSION.SDK_INT >= 31)
        launch()
        openPage("APPEARANCE")
        runBlocking { settings.setTheme(ThemeMode.LIGHT) }
        compose.onNodeWithTag("palette_DYNAMIC").assertIsEnabled()
        click("palette_DYNAMIC")
        waitStored("dynamic") { it.palette == ColorPalette.DYNAMIC }
        compose.onNodeWithTag("palette_DYNAMIC").assertIsSelected()
        compose.onNodeWithTag("palette_fallback_note").assertDoesNotExist()
        val dynamic = Palettes.scheme(ColorPalette.DYNAMIC, dark = false, context = ctx)
        compose.waitForIdle()
        assertTrue("the system's own accent is drawn", previewHas(dynamic.primary.toArgb()))
        runBlocking { settings.setTheme(ThemeMode.DARK) }
        compose.waitForIdle()
        until { previewHas(Palettes.scheme(ColorPalette.DYNAMIC, dark = true, context = ctx).primary.toArgb()) }
    }

    /** P2-T08: where Dynamic does not exist it is disabled and says why, a stored choice is drawn as Classic, nothing crashes. */
    @Test
    fun dynamicColorIsUnavailableBeforeAndroid12AndAStoredChoiceFallsBackToClassic() {
        assumeTrue("before Android 12", Build.VERSION.SDK_INT < 31)
        runBlocking { settings.setPalette(ColorPalette.DYNAMIC) }
        launch()
        openPage("APPEARANCE")
        compose.onNodeWithTag("palette_DYNAMIC").assertIsNotEnabled()
        compose.onNodeWithTag("palette_DYNAMIC").assert(
            SemanticsMatcher("says it is not available") { it.config.getOrNull(SemanticsProperties.StateDescription) == str(R.string.palette_dynamic_unavailable_state) },
        )
        compose.onNodeWithText(str(R.string.palette_dynamic_unavailable)).assertExists()
        // What is drawn, what is selected and what is said agree: Classic.
        compose.onNodeWithTag("palette_CLASSIC").assertIsSelected()
        compose.onNodeWithTag("palette_DYNAMIC").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose.onNodeWithTag("palette_fallback_note").assertExists()
        val classic = Palettes.fixedScheme(ColorPalette.CLASSIC, dark = false)
        runBlocking { settings.setTheme(ThemeMode.LIGHT) }
        compose.waitForIdle()
        assertTrue("Classic is what is drawn", previewHas(classic.primary.toArgb()))
        // Pressing it does nothing, and the stored choice is not lost.
        compose.onNodeWithTag("palette_DYNAMIC").performClick()
        compose.waitForIdle()
        assertEquals(ColorPalette.DYNAMIC, stored().palette)
        // A real choice replaces it.
        click("palette_LEAF")
        waitStored("leaf") { it.palette == ColorPalette.LEAF }
        compose.onNodeWithTag("palette_fallback_note").assertDoesNotExist()
    }

    @Test
    fun theChoicesSurviveARecreateOfTheScreen() {
        launch()
        openPage("APPEARANCE")
        click("palette_SAND")
        click("theme_DARK")
        waitStored("sand/dark") { it.palette == ColorPalette.SAND && it.theme == ThemeMode.DARK }
        scenario!!.recreate()
        until { exists("settings_page_APPEARANCE") }
        compose.waitForIdle()
        compose.onNodeWithTag("palette_SAND").assertIsSelected()
        compose.onNodeWithTag("theme_DARK").assertIsSelected()
        assertTrue(previewHas(Palettes.fixedScheme(ColorPalette.SAND, dark = true).primary.toArgb()))
    }

    /** P2-T06: with the mode on System the colours follow the system's night setting, in both directions, and the page stays. */
    @Test
    fun theSystemModeFollowsTheSystemNightSetting() {
        assumeTrue("cmd uimode night needs Android 10", Build.VERSION.SDK_INT >= 29)
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            launch()
            openPage("APPEARANCE")
            val light = Palettes.fixedScheme(ColorPalette.CLASSIC, dark = false)
            val dark = Palettes.fixedScheme(ColorPalette.CLASSIC, dark = true)
            compose.onNodeWithTag("theme_SYSTEM").assertIsSelected()
            shell("cmd uimode night no")
            until { exists("settings_page_APPEARANCE") && previewHas(light.surface.toArgb()) && !previewHas(dark.surface.toArgb()) }
            shell("cmd uimode night yes")
            until { exists("settings_page_APPEARANCE") && previewHas(dark.surface.toArgb()) && !previewHas(light.surface.toArgb()) }
            // A forced mode ignores the system: Light stays light while the system is dark.
            click("theme_LIGHT")
            until { previewHas(light.surface.toArgb()) }
            shell("cmd uimode night no")
            click("theme_DARK")
            until { previewHas(dark.surface.toArgb()) }
        } finally {
            shell("cmd uimode night auto")
        }
    }

    // ---- P2-B02: controls

    @Test
    fun everyOnOffSettingIsASwitchRowThatTheWholeRowAndItsTextToggle() {
        launch()
        val rows = listOf(
            Triple("REFRESH", "startup_refresh", R.string.settings_startup_refresh),
            Triple("REFRESH", "unmetered_only", R.string.settings_unmetered),
            Triple("REFRESH", "confirm_metered_refresh", R.string.settings_confirm_metered),
            Triple("OPEN", "confirm_http_open", R.string.settings_confirm_http_open),
            Triple("LAYOUT", "show_images", R.string.settings_show_images),
        )
        for ((page, tag, title) in rows) {
            if (!exists("settings_page_$page")) {
                if (anyPage()) back()
                openPage(page)
            }
            compose.onNodeWithTag(tag).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            val before = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
            // The row (its middle), then its title text: both flip it.
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            compose.waitForIdle()
            val afterRow = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
            assertNotEquals("$tag: the row toggles", before, afterRow)
            compose.onNodeWithText(str(title), useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("$tag: the text toggles back", before, compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
            if (before == androidx.compose.ui.state.ToggleableState.On) compose.onNodeWithTag(tag).assertIsOn() else compose.onNodeWithTag(tag).assertIsOff()
        }
    }

    @Test
    fun theHttpSwitchStillAsksBeforeItAllowsHttp() {
        launch()
        openPage("REFRESH")
        compose.onNodeWithTag("allow_http").performScrollTo().performClick()
        until { exists("allow_http_confirm") }
        assertFalse("not allowed before the answer", stored().allowHttp)
        device.pressBack()
        compose.waitForIdle()
        assertFalse(stored().allowHttp)
        compose.onNodeWithTag("allow_http").performScrollTo().performClick()
        click("allow_http_confirm")
        waitStored("allowed") { it.allowHttp }
        compose.onNodeWithTag("allow_http").performScrollTo().performClick()
        waitStored("off needs no question") { !it.allowHttp }
    }

    @Test
    fun theBrowserAndTheModeAreShortChoicesInOneRowAndApplyAtOnce() {
        launch()
        openPage("OPEN")
        click("browser_EXTERNAL")
        waitStored("external") { it.browserMode == BrowserMode.EXTERNAL }
        compose.onNodeWithTag("browser_EXTERNAL").assertIsSelected()
        click("browser_CUSTOM_TABS")
        waitStored("custom tabs") { it.browserMode == BrowserMode.CUSTOM_TABS }
        // Side by side: one row, not a list down the page.
        val a = compose.onNodeWithTag("browser_CUSTOM_TABS").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("browser_EXTERNAL").fetchSemanticsNode().boundsInRoot
        assertTrue("segmented: the two options share a row", abs(a.top - b.top) < 4f)
        back()
        openPage("APPEARANCE")
        click("theme_DARK")
        waitStored("dark") { it.theme == ThemeMode.DARK }
        val x = compose.onNodeWithTag("theme_SYSTEM").fetchSemanticsNode().boundsInRoot
        val y = compose.onNodeWithTag("theme_DARK").fetchSemanticsNode().boundsInRoot
        assertTrue("segmented: system, light and dark share a row", abs(x.top - y.top) < 4f)
    }

    // ---- text size

    /** P2-T16 to T18 */
    @Test
    fun textSizeIsASliderWithOldValuesPreviewWhileDraggedStoredOnReleaseAndAReset() {
        runBlocking { settings.setTextScale(1.15f) }
        launch()
        openPage("APPEARANCE")
        compose.onNodeWithText(str(R.string.percent_value, 115)).assertExists()
        for (old in listOf(0.9f, 1.3f)) {
            runBlocking { settings.setTextScale(old) }
            compose.waitForIdle()
            until { compose.onAllNodes(hasText(str(R.string.percent_value, AppSettings.textScalePercent(old)))).fetchSemanticsNodes().isNotEmpty() }
        }
        // A drag: the preview follows at once, nothing is stored until the finger is lifted.
        runBlocking { settings.setTextScale(1.0f) }
        compose.waitForIdle()
        until { compose.onAllNodes(hasText(str(R.string.percent_value, 100))).fetchSemanticsNodes().isNotEmpty() }
        val before = compose.onNodeWithTag("appearance_preview").fetchSemanticsNode().size.height
        compose.onNodeWithTag("text_size_slider").performScrollTo()
        compose.onNodeWithTag("text_size_slider").performTouchInput {
            down(Offset(width * 0.5f, centerY))
            moveTo(Offset(width * 0.98f, centerY))
        }
        compose.waitForIdle()
        val during = compose.onNodeWithTag("appearance_preview").fetchSemanticsNode().size.height
        assertTrue("the preview is bigger while dragging to 130 % ($before -> $during)", during > before)
        assertEquals("nothing is stored while the finger is down", 1.0f, stored().textScale, 0f)
        compose.onNodeWithTag("text_size_slider").performTouchInput { up() }
        waitStored("130 %") { it.textScale == 1.3f }
        compose.waitForIdle()
        // The accessibility action stores at once, on a step of five.
        compose.onNodeWithTag("text_size_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(105f) }
        waitStored("105 %") { it.textScale == 1.05f }
        // Reset.
        click("text_size_reset")
        waitStored("100 %") { it.textScale == 1.0f }
        compose.onNodeWithTag("text_size_reset").assertIsNotEnabled()
        // The slider tells assistive technology its value in words and ranges.
        val info = compose.onNodeWithTag("text_size_slider").fetchSemanticsNode().config
        assertEquals(str(R.string.percent_value, 100), info.getOrNull(SemanticsProperties.StateDescription))
        val range = info.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(90f..130f, range!!.range)
        assertEquals(7, range.steps)
    }

    // ---- selection dialogs

    @Test
    fun theIntervalAndTheCacheSizeAreChosenInADialogNotListedOnThePage() {
        launch()
        openPage("REFRESH")
        compose.onNodeWithTag("interval_60").assertDoesNotExist()
        click("interval_row")
        until { exists("interval_60") }
        AppSettings.REFRESH_INTERVALS_MINUTES.forEach { until { exists("interval_$it") } }
        click("interval_60")
        waitStored("hourly") { it.refreshIntervalMinutes == 60 }
        compose.onNodeWithTag("interval_30").assertDoesNotExist()
        back()
        openPage("STORAGE")
        compose.onNodeWithTag("image_cache_256").assertDoesNotExist()
        click("image_cache_row")
        until { exists("image_cache_256") }
        click("image_cache_256")
        waitStored("256 MB") { it.imageCacheMb == 256 }
    }

    /** P2-T20: the preview and the confirmation that a smaller limit needs did not go away with the new control. */
    @Test
    fun aSmallerArticleLimitIsStillPreviewedAndConfirmedAndALooserOneIsNot() {
        TestData.seedArticles(30)
        launch()
        openPage("STORAGE")
        click("retention_row")
        click("retention_1000")
        until { exists("retention_confirm") }
        assertEquals("nothing is stored before the answer", 0, stored().retentionLimit)
        device.pressBack()
        compose.waitForIdle()
        assertEquals(0, stored().retentionLimit)
        click("retention_row")
        click("retention_1000")
        click("retention_confirm")
        waitStored("1000") { it.retentionLimit == 1_000 }
        // A looser limit, and "no limit": nothing more would be removed, so no question.
        click("retention_row")
        click("retention_3000")
        waitStored("3000") { it.retentionLimit == 3_000 }
        assertFalse(exists("retention_confirm"))
        // A tighter one asks again.
        click("retention_row")
        click("retention_1000")
        until { exists("retention_confirm") }
        device.pressBack()
        compose.waitForIdle()
        assertEquals(3_000, stored().retentionLimit)
        click("retention_row")
        click("retention_0")
        waitStored("no limit") { it.retentionLimit == 0 }
        assertFalse(exists("retention_confirm"))
    }

    @Test
    fun clearingTheImageCacheKeepsArticlesReadAndSavedState() {
        val ids = TestData.seedArticles(10)
        runBlocking {
            TestData.container.database.articleDao().state(ids[0])
        }
        launch()
        openPage("STORAGE")
        val before = ids.map { TestData.state(it) }
        click("clear_image_cache")
        compose.waitForIdle()
        assertEquals(before, ids.map { TestData.state(it) })
        assertEquals(ids.size, runBlocking { TestData.container.articles.countArticles(TestData.container.subscriptions.ensureLocalAccount()) })
    }

    // ---- layout picker and options

    /** P2-T22 to T24 */
    @Test
    fun theLayoutPickerOffersAllFiveAndOnlyTheOptionsOfTheChosenLayoutAreShown() {
        launch()
        openPage("LAYOUT")
        val mixedLabel = str(R.string.card_style_mixed)
        val evenLabel = str(R.string.card_style_even)
        for (mode in ListMode.entries) {
            click("settings_layout_${mode.name}")
            waitStored("$mode") { it.listMode == mode }
            compose.onNodeWithTag("settings_layout_${mode.name}").assertIsSelected()
            compose.onNodeWithTag("settings_layout_${mode.name}").assert(hasText(listModeText(mode)))
            for (other in ListMode.entries - mode) compose.onNodeWithTag("settings_layout_${other.name}").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
            val magazine = mode == ListMode.MAGAZINE
            val columns = mode == ListMode.MAGAZINE || mode == ListMode.MASONRY || mode == ListMode.BENTO
            assertEquals("$mode: Mixed/Even only for Magazine", magazine, exists("settings_style_MIXED") && exists("settings_style_EVEN"))
            if (!magazine) {
                compose.onAllNodes(hasText(mixedLabel)).assertCountEquals0()
                compose.onAllNodes(hasText(evenLabel)).assertCountEquals0()
            }
            assertEquals("$mode: columns for the grid layouts only", columns, exists("settings_columns_row"))
            assertEquals("$mode: Masonry and Bento say the arrangement does not apply", mode == ListMode.MASONRY || mode == ListMode.BENTO, exists("settings_style_not_used"))
            assertEquals("$mode: a list says it has no such settings", !columns, exists("settings_layout_options_none"))
        }
        // The choices of a hidden option are kept: Magazine's arrangement is still Even after a visit to Bento.
        click("settings_layout_MAGAZINE")
        click("settings_style_EVEN")
        waitStored("even") { it.cardStyle == CardLayoutStyle.EVEN }
        click("settings_layout_BENTO")
        click("settings_layout_MAGAZINE")
        compose.onNodeWithTag("settings_style_EVEN").assertIsSelected()
        // Columns: Automatic is 0 and a number is a number; the list is in a dialog.
        click("settings_columns_row")
        until { exists("settings_columns_2") }
        click("settings_columns_2")
        waitStored("two columns") { it.cardColumns == 2 }
        click("settings_columns_row")
        click("settings_columns_0")
        waitStored("automatic") { it.cardColumns == 0 }
    }

    private fun listModeText(mode: ListMode) = str(
        when (mode) {
            ListMode.LIST -> R.string.list_mode_list
            ListMode.COMPACT_LIST -> R.string.list_mode_compact_list
            ListMode.MAGAZINE -> R.string.list_mode_magazine
            ListMode.MASONRY -> R.string.list_mode_masonry
            ListMode.BENTO -> R.string.list_mode_bento
        },
    )

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() {
        assertTrue("expected no such node", fetchSemanticsNodes().isEmpty())
    }
}
