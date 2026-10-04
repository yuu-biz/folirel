package io.github.yuubiz.folirel

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.yuubiz.folirel.app.MainActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** On-device UI checks: T01, T29, T32 (Custom Tabs path), T34, T31/T37 (mode, images off). */
@RunWith(AndroidJUnit4::class)
class HomeFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun firstLaunchWithoutDataOffersImportAndAdd() {
        TestData.reset(onboardingDone = false)
        launch()
        waitForTag("onboarding_skip")
        compose.onNodeWithTag("onboarding_import").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_add").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_skip").performClick()
        waitForTag("empty_message")
        compose.onNodeWithTag("empty_message").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("article_list").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun openingScrollingAndReturningNeverMarksRead() {
        TestData.reset()
        val ids = TestData.seedArticles(60)
        launch()
        waitForTag("article_list")
        compose.onNodeWithTag("article_list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("article_list").performScrollToIndex(25)
        val anchor = ids[25]
        compose.onNodeWithTag("article_$anchor").assertIsDisplayed()

        // Tap opens the original article in a Custom Tab (Chrome on this image).
        compose.onNodeWithTag("article_$anchor").performClick()
        val opened = device.wait(Until.hasObject(By.pkg("com.android.chrome")), 15_000)
        assertTrue("Custom Tab / browser did not open", opened == true)
        Thread.sleep(1500)
        device.pressBack()
        device.wait(Until.hasObject(By.pkg("io.github.yuubiz.folirel")), 10_000)
        compose.waitForIdle()

        // Same position after returning, and nothing became read.
        waitForTag("article_$anchor")
        compose.onNodeWithTag("article_$anchor").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("article_${ids[0]}").fetchSemanticsNodes().isEmpty())
        assertTrue(ids.none { TestData.state(it).isRead })
    }

    @Test
    fun positionSurvivesRotationAndRecreation() {
        TestData.reset()
        val ids = TestData.seedArticles(60)
        val s = launch()
        waitForTag("article_list")
        compose.onNodeWithTag("article_list").performScrollToIndex(30)
        val anchor = ids[30]
        compose.onNodeWithTag("article_$anchor").assertIsDisplayed()

        s.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        device.waitForIdle()
        waitForTag("article_$anchor")
        compose.onNodeWithTag("article_$anchor").assertIsDisplayed()

        s.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        device.waitForIdle()
        s.recreate()
        waitForTag("article_$anchor")
        compose.onNodeWithTag("article_$anchor").assertIsDisplayed()
        assertTrue(ids.none { TestData.state(it).isRead })
    }

    @Test
    fun imagesOffRemovesThumbnailAreaAndTheLayoutSwitches() {
        TestData.reset(showImages = true)
        TestData.seedArticles(5)
        launch()
        waitForTag("article_list")
        assertTrue(compose.onAllNodesWithTag("thumbnail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())

        kotlinx.coroutines.runBlocking { TestData.container.settings.setShowImages(false) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("thumbnail", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

        kotlinx.coroutines.runBlocking { TestData.container.settings.setListMode(io.github.yuubiz.folirel.model.ListMode.COMPACT_LIST) }
        compose.waitForIdle()
        // The Compact list shows no summary line.
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("article_list")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithTag("article_list").fetchSemanticsNodes().isNotEmpty())
        assertFalse(compose.onAllNodes(androidx.compose.ui.test.hasText("概要 0")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun articleWithoutUrlShowsMessageNotBrowser() {
        TestData.reset()
        val ids = TestData.seedArticles(3, withLinks = false)
        launch()
        waitForTag("article_list")
        compose.onNodeWithTag("article_${ids[0]}").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("URL", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(device.hasObject(By.pkg("com.android.chrome")))
        assertFalse(TestData.state(ids[0]).isRead)
    }
}
