package io.github.yuubiz.folirel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.model.ShareCandidate
import io.github.yuubiz.folirel.model.ShareDiscovery
import io.github.yuubiz.folirel.model.ShareProblem
import io.github.yuubiz.folirel.model.ShareProvenance
import io.github.yuubiz.folirel.ui.share.ShareSubscribeOverlay
import io.github.yuubiz.folirel.ui.share.ShareSubscriptionController
import io.github.yuubiz.folirel.ui.subscriptions.AddFeedDialog
import io.github.yuubiz.folirel.ui.theme.FolirelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Add feed URL" with a site address (F1) on a device: the dialog hands the address to the Share to Subscribe search, whose dialog
 * opens over it. Fake search and add path, no network. Needs a device or an emulator; written where none could be used, so it has
 * only been compiled (compileDebugAndroidTestKotlin), never run.
 */
@RunWith(AndroidJUnit4::class)
class AddFeedDialogUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val looked = ArrayList<String>()
    private val registered = ArrayList<String>()
    private var result: ShareDiscovery = ShareDiscovery.Failed(ShareProblem.NO_FEED)

    private fun candidate(url: String, provenance: ShareProvenance) =
        ShareCandidate(url, url, "Blog $url", "https://site.example/", 4, null, null, null, provenance)

    private fun show() {
        val c = ShareSubscriptionController(
            CoroutineScope(Dispatchers.Main.immediate),
            discover = { looked += it; result },
            register = { url, onDone, _ -> registered += url; onDone(AddFeedResult.Added("new")) },
        )
        compose.setContent {
            FolirelTheme(ThemeMode.LIGHT, 1f, ColorPalette.CLASSIC) {
                var open by remember { mutableStateOf(true) }
                if (open) AddFeedDialog(c, onDismiss = { open = false })
                ShareSubscribeOverlay(c, onOpenFeed = {})
            }
        }
    }

    private fun add(typed: String) {
        compose.onNodeWithTag("add_feed_url").performTextInput(typed)
        compose.onNodeWithTag("add_feed_ok").performClick()
        compose.waitForIdle()
    }

    @Test
    fun aFeedAddressIsAddedWithOneTapAndBothDialogsClose() {
        result = ShareDiscovery.Found(null, listOf(candidate("https://a.example/feed.xml", ShareProvenance.DIRECT_FEED)))
        show()
        add("https://a.example/feed.xml")
        assertEquals(listOf("https://a.example/feed.xml"), registered)
        compose.onNodeWithTag("add_feed_dialog").assertDoesNotExist()
        compose.onNodeWithTag("share_overlay").assertDoesNotExist()
    }

    @Test
    fun aSiteAddressShowsItsFeedsAndCancelReturnsToTheAddressAsTyped() {
        result = ShareDiscovery.Found(null, listOf(candidate("https://a.example/feed.xml", ShareProvenance.SHARED_PAGE)))
        show()
        add("a.example")
        assertEquals(listOf("https://a.example"), looked)
        compose.onNodeWithTag("share_subscribe").assertIsDisplayed()
        assertTrue("a feed found on a page waits for a tap", registered.isEmpty())
        compose.onNodeWithTag("share_cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("share_overlay").assertDoesNotExist()
        compose.onNodeWithTag("add_feed_url").assertTextContains("a.example")
    }

    @Test
    fun subscribingToAFeedOfTheSiteClosesBothDialogs() {
        result = ShareDiscovery.Found(null, listOf(candidate("https://a.example/feed.xml", ShareProvenance.SHARED_PAGE)))
        show()
        add("https://a.example/")
        compose.onNodeWithTag("share_subscribe").performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://a.example/feed.xml"), registered)
        compose.onNodeWithTag("add_feed_dialog").assertDoesNotExist()
    }

    @Test
    fun aProblemIsShownAndOkReturnsToTheAddDialog() {
        show()
        add("https://a.example/")
        compose.onNodeWithTag("share_problem").assertIsDisplayed()
        compose.onNodeWithTag("share_cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("add_feed_dialog").assertIsDisplayed()
        assertTrue(registered.isEmpty())
    }
}
