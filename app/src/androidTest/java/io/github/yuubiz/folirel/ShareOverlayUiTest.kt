package io.github.yuubiz.folirel

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.model.ExistingFeed
import io.github.yuubiz.folirel.model.ShareCandidate
import io.github.yuubiz.folirel.model.ShareDiscovery
import io.github.yuubiz.folirel.model.ShareProvenance
import io.github.yuubiz.folirel.ui.share.ShareState
import io.github.yuubiz.folirel.ui.share.ShareSubscribeOverlay
import io.github.yuubiz.folirel.ui.share.ShareSubscriptionController
import io.github.yuubiz.folirel.ui.theme.FolirelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Share to Subscribe dialog on a device (P3B-T19, T20, T21, T22, T28 on the screen). Written against the controller with a fake
 * search and a fake add path, so it needs no network. Needs a device or an emulator; 0.9.2 was built where none was available, so it has
 * only been compiled (compileDebugAndroidTestKotlin), never run.
 */
@RunWith(AndroidJUnit4::class)
class ShareOverlayUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val registered = ArrayList<String>()
    private val opened = ArrayList<String>()
    private var result: ShareDiscovery = ShareDiscovery.Found(null, emptyList())

    private fun candidate(url: String, existing: ExistingFeed? = null) =
        ShareCandidate(url, url, "Blog $url", "https://site.example/", 4, null, null, existing)

    private fun controller() = ShareSubscriptionController(
        CoroutineScope(Dispatchers.Main.immediate),
        discover = { result },
        register = { url, onDone, _ -> registered += url; onDone(AddFeedResult.Added("new")) },
    )

    private fun show(controller: ShareSubscriptionController) = compose.setContent {
        FolirelTheme(ThemeMode.LIGHT, 1f, ColorPalette.CLASSIC) {
            ShareSubscribeOverlay(controller, onOpenFeed = { opened += it })
        }
    }

    private fun share(c: ShareSubscriptionController, text: String) {
        compose.runOnUiThread { c.setReady(true); c.receive(text) }
        compose.waitForIdle()
    }

    @Test
    fun oneCandidateIsShownAndOnlyTheSubscribeButtonRegistersIt() {
        result = ShareDiscovery.Found(null, listOf(candidate("https://a.example/feed.xml")))
        val c = controller()
        show(c)
        share(c, "https://a.example/")
        compose.onNodeWithTag("share_subscribe").assertIsDisplayed()
        assertTrue("nothing is registered by showing it", registered.isEmpty())
        compose.onNodeWithTag("share_subscribe").performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://a.example/feed.xml"), registered)
        compose.onNodeWithTag("share_overlay").assertDoesNotExist()
    }

    @Test
    fun cancelClosesTheDialogWithoutRegisteringAnything() {
        result = ShareDiscovery.Found(null, listOf(candidate("https://a.example/feed.xml")))
        val c = controller()
        show(c)
        share(c, "https://a.example/")
        compose.onNodeWithTag("share_cancel").performClick()
        compose.waitForIdle()
        assertEquals(ShareState.Idle, c.state.value)
        assertTrue(registered.isEmpty())
    }

    @Test
    fun severalCandidatesAreChosenFromAListAndOneIsRegistered() {
        result = ShareDiscovery.Found(null, listOf(candidate("https://a.example/one.xml"), candidate("https://a.example/two.xml")))
        val c = controller()
        show(c)
        share(c, "https://a.example/")
        compose.onNodeWithTag("share_candidate_https://a.example/two.xml").assertIsDisplayed()
        assertTrue(registered.isEmpty())
    }

    @Test
    fun anAlreadySubscribedFeedOffersItsDetailsAndNoSubscribeButton() {
        result = ShareDiscovery.Found(null, listOf(candidate("https://a.example/feed.xml", ExistingFeed("f1", "Mine", subscribed = true))))
        val c = controller()
        show(c)
        share(c, "https://a.example/")
        compose.onNodeWithTag("share_already").assertIsDisplayed()
        compose.onNodeWithTag("share_subscribe").assertDoesNotExist()
        compose.onNodeWithTag("share_open_feed").performClick()
        compose.waitForIdle()
        assertEquals(listOf("f1"), opened)
        assertEquals(ShareState.Idle, c.state.value)
    }

    @Test
    fun anUnsubscribedFeedAsksForAnExplicitResubscribe() {
        result = ShareDiscovery.Found(null, listOf(candidate("https://a.example/feed.xml", ExistingFeed("f1", "Mine", subscribed = false))))
        val c = controller()
        show(c)
        share(c, "https://a.example/")
        compose.onNodeWithTag("share_unsubscribed").assertIsDisplayed()
        assertTrue(registered.isEmpty())
        compose.onNodeWithTag("share_resubscribe").performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://a.example/feed.xml"), registered)
    }

    @Test
    fun aFeedFoundOnTheSiteRootSaysSo() {
        result = ShareDiscovery.Found(null, listOf(candidate("https://a.example/feed.xml").copy(provenance = ShareProvenance.SITE_ROOT)))
        val c = controller()
        show(c)
        share(c, "https://a.example/docs/1.html")
        compose.onNodeWithTag("share_from_root").assertIsDisplayed()
        compose.onNodeWithTag("share_subscribe").assertIsDisplayed()
    }

    @Test
    fun aProblemIsShownInWordsAndClosesWithOk() {
        val c = controller()
        show(c)
        share(c, "no address here")
        compose.onNodeWithTag("share_problem").assertIsDisplayed()
        compose.onNodeWithTag("share_cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("share_overlay").assertDoesNotExist()
    }
}
