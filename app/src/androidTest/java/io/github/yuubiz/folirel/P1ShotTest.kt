package io.github.yuubiz.folirel

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.ListMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Screenshots of the home screen for the before/after comparison (P1 report): the same data, the same device, the same window.
 * Not an assertion test: it writes PNG files to `/sdcard/Android/media/<package>/p1shots/<label>/` and logs `P1SHOT` lines.
 * Skipped in an ordinary run: pass `-e p1evidence true`. The label comes from the instrumentation argument `shotLabel` (default "after"). Only tags that exist in both versions are used,
 * the panel and the picker are pictured only where they exist.
 */
@RunWith(AndroidJUnit4::class)
class P1ShotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val label get() = InstrumentationRegistry.getArguments().getString("shotLabel") ?: "after"
    private lateinit var dir: File
    private lateinit var ids: List<String>

    @Before
    fun seed() {
        org.junit.Assume.assumeTrue("evidence helper (pass -e p1evidence true)", InstrumentationRegistry.getArguments().getString("p1evidence") == "true")
        TestData.reset(showImages = false)
        val names = listOf("IT", "ニュース", "Matome2chNews", "非常に長いカテゴリ名を持つタブ", "開発", "カテゴリ 05", "カテゴリ 06", "カテゴリ 07", "カテゴリ 08", "デザイン", "音楽", "写真")
        val byPath = TestData.seedPaths(names.map { listOf(it) }, perCategory = 12)
        ids = names.map { byPath.getValue(it) }
        dir = File(instrumentation.targetContext.externalMediaDirs.first { it != null }, "p1shots/$label").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        if (InstrumentationRegistry.getArguments().getString("p1evidence") == "true") shell("settings put system font_scale 1.0")
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("article_list")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        Thread.sleep(800)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("P1SHOT", "${file.absolutePath} ${bitmap.width}x${bitmap.height}")
    }

    private fun has(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun homeScreens() {
        runBlocking { TestData.container.settings.setListMode(ListMode.LIST) }
        launch()
        shot("01-home-list")
        runBlocking { TestData.container.settings.setListMode(ListMode.MAGAZINE) }
        launch()
        shot("02-home-magazine")
        // A category in the middle of the tabs (the row has scrolled to it).
        runBlocking { TestData.container.settings.setLastScope(ArticleScope.Category(ids[7])) }
        launch()
        shot("03-home-category-middle")
        if (has("display_open")) {
            compose.onNodeWithTag("display_open").performClick()
            compose.waitUntil(5_000) { has("display_panel") }
            shot("04-display-panel")
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
            compose.waitForIdle()
        }
        shell("settings put system font_scale 2.0")
        runBlocking { TestData.container.settings.setListMode(ListMode.LIST) }
        launch()
        shot("05-home-font200")
        if (has("display_open")) {
            compose.onNodeWithTag("display_open").performClick()
            compose.waitUntil(5_000) { has("display_panel") }
            shot("06-display-panel-font200")
        }
    }

    /** The state a recording starts from: the same data, the middle category selected, the list layout. Launches nothing. */
    @Test
    fun stateForTheRecording() {
        runBlocking {
            TestData.container.settings.setListMode(ListMode.LIST)
            TestData.container.settings.setLastScope(ArticleScope.Category(ids[7]))
        }
    }
}
