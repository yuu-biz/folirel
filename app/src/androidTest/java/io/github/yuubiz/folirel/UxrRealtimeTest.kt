package io.github.yuubiz.folirel

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import coil3.ImageLoader
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.ui.home.ArticleActions
import io.github.yuubiz.folirel.ui.home.ArticleList
import io.github.yuubiz.folirel.ui.home.ArticlesState
import io.github.yuubiz.folirel.ui.home.ListAnchor
import io.github.yuubiz.folirel.ui.home.ListHost
import io.github.yuubiz.folirel.ui.theme.FolirelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UXR-02: the way back to the top in REAL time, with pictures that really load, through the arrow button a reader taps
 * (found and pressed through the accessibility tree like a user would). The compose test rule is deliberately not used:
 * its frame clock only runs while the test pumps it, which is not real time. The first visible article is reported by the
 * list on every change and timed here; that is the position trace, written to logcat under UXR_REALTIME. The run is meant
 * to be screen-recorded (docs/testing.md). The checks are loose on purpose: an emulator draws slowly, so they show the
 * shape of the movement (it ends on the first article, never goes the other way, does not stop for long on its way), not
 * a frame rate.
 */
@RunWith(AndroidJUnit4::class)
class UxrRealtimeTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val anchors = HashMap<String, ListAnchor>()
    private val log = java.util.Collections.synchronizedList(ArrayList<Triple<Long, String, Int>>())
    private var items by mutableStateOf<List<ArticleRow>>(emptyList())
    private var listKey by mutableStateOf("rt-start")

    private val host = object : ListHost {
        override fun anchorFor(listKey: String) = anchors[listKey]
        override fun saveAnchor(anchor: ListAnchor) {
            anchors[anchor.listKey] = anchor
            log += Triple(System.nanoTime(), anchor.articleId, anchor.offset)
        }
        override fun acknowledgeNew(ids: Collection<String>) = Unit
    }

    @Before
    fun quietApp() {
        TestData.reset()
    }

    private fun row(n: Int, image: String?) = ArticleRow(
        id = "a$n", feedId = "f", title = "記事 $n " + "見出しの文章が長くなると行数が増える ".repeat(Math.floorMod(n, 3)),
        summaryText = if (Math.floorMod(n, 6) == 5) "" else "概要 $n " + "本文の冒頭が続きます ".repeat(Math.floorMod(n, 6) * 3),
        linkUrl = "https://example.com/$n", imageUrl = image, author = null, publishedAt = 1_000_000L - n, sortAt = 1_000_000L - n,
        isRead = false, isSaved = false, feedName = "feed", feedUrl = "https://feed.example.com/", feedSubscribed = true,
    )

    private class Run(val label: String, val updates: List<Triple<Long, String, Int>>) {
        val indices = updates.map { it.second.removePrefix("a").toInt() }
        val gapsMs = updates.zipWithNext { a, b -> (b.first - a.first) / 1_000_000 }
        val reversals = indices.zipWithNext().count { (a, b) -> b > a }
        val durationMs = if (updates.size < 2) 0 else (updates.last().first - updates.first().first) / 1_000_000
        val longestGapMs = gapsMs.maxOrNull() ?: 0
        fun p(q: Double) = gapsMs.sorted().let { if (it.isEmpty()) 0L else it[((it.size - 1) * q).toInt()] }
        fun summary() = "$label: updates=${updates.size} duration=${durationMs}ms gap p50=${p(0.5)}ms p95=${p(0.95)}ms max=${longestGapMs}ms reversals=$reversals endIndex=${indices.lastOrNull()}"
    }

    private fun scenario(label: String, mode: ListMode, from: Int, columns: Int = 0, repeats: Int = 3) {
        val pictures = ListHarness.fileImages(context)
        items = (0 until BIG).map { row(it, pictures.takeIf { p -> Math.floorMod(it, 2) == 0 }?.get(Math.floorMod(it, pictures.size))) }
        val loader = ImageLoader.Builder(context).build()
        val actions = ArticleActions(open = {}, openExternal = {}, toggleSaved = {}, setRead = { _, _ -> }, share = {}, showSummary = {})
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val view = ComposeView(activity)
                activity.setContentView(view)
                view.setContent {
                    FolirelTheme(ThemeMode.SYSTEM, 1.0f) {
                        // The home screen's Scaffold keeps its content out of the system bars; so does this host (the arrow sits above the navigation bar).
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            ArticleList(
                                host = host, state = ArticlesState(listKey, items, loaded = true), mode = mode, showImages = true, cardColumns = columns,
                                cardStyle = CardLayoutStyle.MIXED, imageLoader = loader, actions = actions, newIds = emptySet(), newestFirst = true,
                                isCurrent = true, scrollToTopTick = 0,
                            )
                        }
                    }
                }
            }
            val arrow = By.desc(context.getString(R.string.scroll_to_top))
            repeat(repeats) { round ->
                // A new list key starts the list at the anchor: far down again, the way a reader who scrolled there would be.
                anchors["rt-$round"] = ListAnchor("rt-$round", "a$from", 1_000_000L - from, 0)
                scenario.onActivity { listKey = "rt-$round" }
                assertTrue("the arrow shows far down", device.wait(Until.hasObject(arrow), 8_000))
                Thread.sleep(1_200) // the screen settles and its pictures load, as for a reader who has stopped on it
                log.clear()
                device.findObject(arrow).click()
                val deadline = System.nanoTime() + 8_000_000_000L
                var lastCount = -1
                var stillSince = System.nanoTime()
                while (System.nanoTime() < deadline) {
                    Thread.sleep(40)
                    val count = log.size
                    if (count != lastCount) { lastCount = count; stillSince = System.nanoTime() }
                    else if (System.nanoTime() - stillSince > 600_000_000L && count > 0) break
                }
                val run = Run("$label#$round", synchronized(log) { log.toList() })
                Log.i(TAG, run.summary())
                run.updates.forEach { (t, id, offset) -> Log.i(TAG, "${run.label},${(t - run.updates.first().first) / 1_000_000},$id,$offset") }
                assertEquals("${run.summary()}: it ends on the first article", 0, run.indices.last())
                assertEquals("${run.summary()}: it never goes away from the top", 0, run.reversals)
                assertTrue("${run.summary()}: it does not stop for long on its way", run.longestGapMs < 500)
                assertTrue("${run.summary()}: it is one quick movement", run.durationMs < 1_500)
                Thread.sleep(800)
            }
        } finally {
            scenario.close()
        }
    }

    @Test fun listWithPicturesFarDown() = scenario("rt-list-far", ListMode.LIST, FAR)
    @Test fun magazineTwoColumnsWithPicturesFarDown() = scenario("rt-magazine2-far", ListMode.MAGAZINE, FAR, columns = 2)
    @Test fun magazineTwoColumnsWithPicturesAFewScreensDown() = scenario("rt-magazine2-near", ListMode.MAGAZINE, NEAR, columns = 2)

    private companion object {
        const val TAG = "UXR_REALTIME"
        const val BIG = 3000
        const val FAR = 2600
        const val NEAR = 24
    }
}
