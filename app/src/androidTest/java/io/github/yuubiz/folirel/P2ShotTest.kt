package io.github.yuubiz.folirel

import android.graphics.Bitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Screenshots of the Phase 2 screens for the report (colour sets in light and dark, every settings page, the layout picker, the
 * category sheet, dialogs). Not an assertion test: it writes PNG files to `/sdcard/Android/media/<package>/p2shots/<label>/` and logs
 * `P2SHOT` lines. Skipped in an ordinary run: pass `-e p2evidence true`. `shotLabel` names the folder (default "run"); the window size
 * and text size are set from outside (`wm size`, `settings put system font_scale`), so the same run is repeated for 320 dp, a
 * normal phone, a tablet width and 200 % text.
 */
@RunWith(AndroidJUnit4::class)
class P2ShotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val label get() = InstrumentationRegistry.getArguments().getString("shotLabel") ?: "run"
    private lateinit var dir: File
    private val settings get() = TestData.container.settings

    @Before
    fun seed() {
        org.junit.Assume.assumeTrue("evidence helper (pass -e p2evidence true)", InstrumentationRegistry.getArguments().getString("p2evidence") == "true")
        TestData.reset(showImages = false)
        TestData.seedCategories(listOf("Alpha", "Beta", "Gamma"), perCategory = 12)
        dir = File(instrumentation.targetContext.externalMediaDirs.first { it != null }, "p2shots/$label").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun has(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun until(condition: () -> Boolean) = compose.waitUntil(10_000) { condition() }

    private fun click(tag: String) {
        until { has(tag) }
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        until { has("drawer_open") || has("settings_top") }
        compose.waitForIdle()
        Thread.sleep(500)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(500)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("P2SHOT", "${file.absolutePath} ${bitmap.width}x${bitmap.height}")
    }

    private fun openPage(page: String) {
        if (!has("settings_top")) {
            click("drawer_open")
            click("drawer_item_settings")
            until { has("settings_top") }
        }
        click("settings_row_$page")
        until { has("settings_page_$page") }
    }

    /** Back until the top page of Settings is shown (a dialog, then the page). */
    private fun toTop() {
        repeat(3) {
            if (!has("settings_top")) {
                device.pressBack()
                compose.waitForIdle()
                Thread.sleep(300)
            }
        }
        until { has("settings_top") }
    }

    @Test
    fun settingsScreens() {
        launch()
        openPage("APPEARANCE")
        toTop()
        shot("01-top")
        openPage("APPEARANCE")
        for (dark in listOf(false, true)) {
            runBlocking { settings.setTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) }
            for (palette in ColorPalette.entries) {
                runBlocking { settings.setPalette(palette) }
                compose.waitForIdle()
                shot("02-appearance-${palette.name.lowercase()}-${if (dark) "dark" else "light"}")
            }
        }
        // The lower part of the page (the size slider) on its own.
        runBlocking { settings.setTheme(ThemeMode.LIGHT); settings.setPalette(ColorPalette.LEAF) }
        compose.waitForIdle()
        compose.onNodeWithTag("text_size_slider").performScrollTo()
        shot("03-appearance-slider")
        toTop()
        runBlocking { settings.setPalette(ColorPalette.CLASSIC) }
        openPage("LAYOUT")
        for (mode in ListMode.entries) {
            runBlocking { settings.setListMode(mode) }
            compose.waitForIdle()
            shot("04-layout-${mode.name.lowercase()}")
        }
        runBlocking { settings.setListMode(ListMode.MAGAZINE); settings.setCardStyle(CardLayoutStyle.EVEN) }
        click("settings_columns_row")
        until { has("settings_columns_2") }
        shot("05-layout-columns-dialog")
        toTop()
        for (page in listOf("OPEN", "REFRESH", "STORAGE", "ABOUT")) {
            openPage(page)
            shot("06-${page.lowercase()}")
            if (page == "REFRESH") {
                click("interval_row")
                until { has("interval_60") }
                shot("07-refresh-interval-dialog")
            }
            toTop()
        }
    }

    @Test
    fun homePanelAndCategorySheet() {
        val category = runBlocking {
            val structure = TestData.container.subscriptions.observeStructure(TestData.container.subscriptions.ensureLocalAccount())
            structure.first().categories.first { it.display == "Beta" }
        }
        runBlocking { settings.setLastScope(ArticleScope.Category(category.id)); settings.setListMode(ListMode.MAGAZINE) }
        launch()
        click("display_open")
        until { has("display_panel") }
        shot("08-home-panel-category")
        click("layout_details")
        until { has("category_layout_sheet") }
        shot("09-category-sheet-inherit")
        click("cl_list_BENTO")
        shot("10-category-sheet-bento")
        click("cl_list_LIST")
        shot("11-category-sheet-list")
        click("cl_list_MAGAZINE")
        click("cl_cols_row")
        until { has("cl_cols_0") }
        shot("12-category-sheet-columns-dialog")
    }
}
