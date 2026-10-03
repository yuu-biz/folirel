package io.github.yuubiz.feeddeck

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.yuubiz.feeddeck.app.MainActivity
import io.github.yuubiz.feeddeck.data.preferences.BrowserMode
import io.github.yuubiz.feeddeck.data.repository.AttemptInfo
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.model.ListMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks of the 0.3 UX work with seeded data (no network): U03 (position kept across list style
 * changes), U06 (HTTP confirmation), U10/U11 (bulk unsubscribe and undo).
 */
@RunWith(AndroidJUnit4::class)
class UxNextTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var scenario: ActivityScenario<MainActivity>? = null
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        scenario?.close()
        runBlocking { TestData.container.settings.setBrowserMode(BrowserMode.CUSTOM_TABS) }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun text(res: Int) = context.getString(res)

    // U03: the same article stays near the top when the list style changes

    @Test
    fun cardAndColumnStylesKeepTheSameArticleAndNeverMarkRead() {
        TestData.reset()
        val ids = TestData.seedArticles(80)
        runBlocking { TestData.container.settings.setListMode(ListMode.CARD) }
        launch()
        waitForTag("article_list")
        compose.onNodeWithTag("article_list").performScrollToIndex(40)
        val anchor = ids[40]
        compose.onNodeWithTag("article_$anchor").assertIsDisplayed()

        for (mode in listOf(ListMode.MAGAZINE, ListMode.LIST, ListMode.CARD)) {
            runBlocking { TestData.container.settings.setListMode(mode) }
            compose.waitForIdle()
            waitForTag("article_$anchor")
            compose.onNodeWithTag("article_$anchor").assertIsDisplayed()
        }
        assertTrue(ids.none { TestData.state(it).isRead })
    }

    // U06: confirmation for http:// articles

    @Test
    fun httpConfirmationIsSwitchedOffOnlyByOpeningWithTheBoxTicked() {
        TestData.reset()
        val ids = TestData.seedArticles(3, link = "http://insecure.example.com/")
        runBlocking { TestData.container.settings.setBrowserMode(BrowserMode.EXTERNAL) }
        launch()
        waitForTag("article_list")

        // Cancel with the box ticked: nothing is saved, and the box starts unticked next time.
        compose.onNodeWithTag("article_${ids[0]}").performClick()
        waitForTag("http_open_dont_ask")
        compose.onNodeWithTag("http_open_dont_ask").performClick()
        compose.onNodeWithTag("http_open_cancel").performClick()
        compose.waitForIdle()
        assertTrue(runBlocking { TestData.container.settings.current().confirmHttpOpen })
        assertFalse(runBlocking { TestData.container.settings.current().allowHttp })

        // Open with the box ticked: saved, and the HTTP download setting is untouched.
        compose.onNodeWithTag("article_${ids[0]}").performClick()
        waitForTag("http_open_dont_ask")
        compose.onNodeWithTag("http_open_dont_ask").performClick()
        compose.onNodeWithTag("http_open_confirm").performClick()
        // The browser is coming to the front: the app has no compose hierarchy for a moment, so poll the setting directly.
        val deadline = System.currentTimeMillis() + 5_000
        while (runBlocking { TestData.container.settings.current().confirmHttpOpen } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse(runBlocking { TestData.container.settings.current().confirmHttpOpen })
        assertFalse("silencing the question does not allow HTTP feeds or images", runBlocking { TestData.container.settings.current().allowHttp })
        device.wait(Until.hasObject(By.pkg("com.android.chrome")), 10_000)
        device.pressBack()
        device.wait(Until.hasObject(By.pkg("io.github.yuubiz.feeddeck")), 10_000)

        // Now an http:// article opens without the dialog; the read state is untouched.
        compose.waitForIdle()
        waitForTag("article_${ids[1]}")
        compose.onNodeWithTag("article_${ids[1]}").performClick()
        // The browser comes up without anyone pressing "open": there was no dialog in between.
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.chrome")), 10_000) == true)
        assertTrue(ids.none { TestData.state(it).isRead })
    }

    // U10 / U11: bulk unsubscribe from the subscription list

    private fun seedFailingFeeds(): Triple<String, String, String> = runBlocking {
        val c = TestData.container
        val account = c.subscriptions.ensureLocalAccount()
        val opml = """<opml version="2.0"><body><outline text="Gone" xmlUrl="https://gone.example.com/f"/><outline text="Busy" xmlUrl="https://busy.example.com/f"/><outline text="Fine" xmlUrl="https://fine.example.com/f"/></body></opml>"""
        c.subscriptions.commitImport(account, io.github.yuubiz.feeddeck.backend.local.opml.OpmlParser.parse(opml.toByteArray()))
        val feeds = c.database.subscriptionDao().feeds(account).associateBy { it.subscriptionUrl }
        val store = io.github.yuubiz.feeddeck.data.repository.FeedStore(c.database, java.time.Clock.systemUTC(), io.github.yuubiz.feeddeck.data.repository.IdGenerator.Random)
        val gone = feeds.getValue("https://gone.example.com/f").id
        val busy = feeds.getValue("https://busy.example.com/f").id
        val fine = feeds.getValue("https://fine.example.com/f").id
        store.recordFailure(gone, FetchError.NOT_FOUND, null, AttemptInfo(404, false))
        store.recordFailure(busy, FetchError.TIMEOUT, null, AttemptInfo(null, false))
        Triple(gone, busy, fine)
    }

    private fun openSubscriptions() {
        compose.onNodeWithTag("home_more").performClick()
        // The drawer also has this entry (off screen); the menu item is the one inside the popup.
        compose.onNode(hasText(text(R.string.nav_subscriptions)) and hasAnyAncestor(isPopup())).performClick()
        waitForTag("fetch_summary")
    }

    @Test
    fun failedFeedsCanBeSelectedUnsubscribedAndRestored() {
        TestData.reset()
        val (gone, busy, fine) = seedFailingFeeds()
        launch()
        // No articles: the home screen still has the menu.
        waitForTag("home_more")
        openSubscriptions()

        compose.onNodeWithTag("feed_filter_FAILED").performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("feed_row_$gone").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithTag("feed_row_$busy").fetchSemanticsNodes().isNotEmpty())
        assertTrue("a healthy feed is not in the failed filter", compose.onAllNodesWithTag("feed_row_$fine").fetchSemanticsNodes().isEmpty())

        // Select only the "not found" one; the confirmation names exactly it and says a failure proves nothing.
        compose.onNodeWithContentDescriptionMore().performClick()
        compose.onNodeWithTag("menu_select").performClick()
        compose.onNodeWithTag("feed_row_$gone").performClick()
        compose.onNodeWithTag("unsubscribe_selected").performClick()
        waitForTag("bulk_confirm")
        compose.onNodeWithTag("bulk_item_$gone").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("bulk_item_$busy").fetchSemanticsNodes().isEmpty())
        compose.onNode(hasText(text(R.string.problem_missing), substring = true) and hasAnyAncestor(hasTestTag("bulk_item_$gone"))).assertIsDisplayed()

        // Cancel changes nothing.
        compose.onNodeWithTag("bulk_cancel").performClick()
        assertTrue(subscribed(gone))

        compose.onNodeWithTag("unsubscribe_selected").performClick()
        waitForTag("bulk_confirm")
        compose.onNodeWithTag("bulk_confirm").performClick()
        compose.waitUntil(5_000) { !subscribed(gone) }
        assertTrue("the feed that was not selected stays subscribed", subscribed(busy) && subscribed(fine))

        // Undo from the snackbar restores exactly that feed.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text(R.string.action_undo))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.action_undo)).performClick()
        compose.waitUntil(5_000) { subscribed(gone) }
        assertEquals(3, runBlocking { TestData.container.database.subscriptionDao().feeds(TestData.container.subscriptions.ensureLocalAccount()).count { it.isSubscribed } })
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithContentDescriptionMore() =
        onNode(androidx.compose.ui.test.hasContentDescription(text(R.string.action_more)))

    private fun subscribed(feedId: String) = runBlocking { TestData.container.database.subscriptionDao().feed(feedId)!!.isSubscribed }
}
