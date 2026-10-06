package io.github.yuubiz.folirel

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.model.ListMode
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Evidence helper (skipped unless `-e p1evidence true`): screenshots of the Masonry and Bento layouts with the same articles the density
 * pictures use, on the phone width and at 560 dp (three columns), to `/sdcard/Android/media/<package>/tileshots/`.
 */
@RunWith(AndroidJUnit4::class)
class TileShotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun onlyWhenAsked() {
        Assume.assumeTrue("evidence helper (pass -e p1evidence true)", InstrumentationRegistry.getArguments().getString("p1evidence") == "true")
        TestData.reset()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(1_500)
        compose.waitForIdle()
        val dir = File(instrumentation.targetContext.externalMediaDirs.first { it != null }, "tileshots").also { it.mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun tileLayouts() {
        val m = MagazineHost(compose)
        val items = (0 until 30).map { n ->
            m.article(
                n,
                when (n % 3) { 0 -> "短い見出し $n"; 1 -> "二行になる見出しの例です。少し長めの文章で $n"; else -> "三行になる長い見出しの例です。まとめサイトの記事タイトルはこのくらい長いことがよくあります $n" },
                when (n % 4) { 0 -> ""; 1 -> "続きを読む"; 2 -> "短い概要です。"; else -> "通常の概要の文章です。記事の内容を一、二行で説明しています。ここまでが概要です。" },
            )
        }
        m.show(items, mode = ListMode.MASONRY, columns = 0, listKey = "masonry")
        shot("01-masonry-phone")
        m.show(items, mode = ListMode.BENTO, columns = 0, listKey = "bento")
        shot("02-bento-phone")
        m.show(items, mode = ListMode.BENTO, columns = 3, widthDp = 560, listKey = "bento3")
        shot("03-bento-560dp-3col")
        m.show(items, mode = ListMode.MASONRY, columns = 3, widthDp = 560, listKey = "masonry3")
        shot("04-masonry-560dp-3col")
        m.show(items, mode = ListMode.BENTO, columns = 0, fontScale = 2f, listKey = "bento-font")
        shot("05-bento-font200")
    }
}
