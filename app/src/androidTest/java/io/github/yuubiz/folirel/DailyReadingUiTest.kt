package io.github.yuubiz.folirel

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.PeriodFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3A on the real home screen (P3A-T01..T05, the panel part of T06..T13 and the manage sheet of T19..T27). Written against the
 * same harness as P1DenseTest; it needs a device or an emulator, and 0.9.0 was built where none was available, so it has only been
 * compiled (compileDebugAndroidTestKotlin), never run.
 */
@RunWith(AndroidJUnit4::class)
class DailyReadingUiTest : HomeFlowTestBase() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(res: Int) = context.getString(res)

    private fun subtitle(): String =
        compose.onNodeWithTag("scope_subtitle", useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun openPanel() {
        compose.onNodeWithTag("display_open").performClick()
        until(5_000) { existsAnywhere("display_panel") }
        compose.waitForIdle()
    }

    private fun openMenuOfFirstArticleOfFeed() {
        val row = vm.pageList(all).value.items.first { it.feedId == feedOne }
        compose.onNodeWithTag("article_${row.id}").performTouchInput { longClick() }
        until(5_000) { existsAnywhere("menu_show_feed") }
    }

    /** T01, T03: "Show articles from this source" is a scope change; the article's read and saved state stay as they were. */
    @Test
    fun theMenuShowsTheArticlesOfThatSourceWithoutChangingTheArticle() {
        firstFetchThenShow()
        val row = vm.pageList(all).value.items.first { it.feedId == feedOne }
        openMenuOfFirstArticleOfFeed()
        compose.onNodeWithTag("menu_show_feed").performClick()
        until(5_000) { vm.selection.value.scope == ArticleScope.Feed(feedOne) }
        compose.waitForIdle()
        val after = runBlocking { TestData.container.database.articleDao().state(row.id)!! }
        assertEquals(row.isRead, after.isRead)
        assertEquals(row.isSaved, after.isSaved)
        assertEquals("opening the source marks nothing read", 0, readCount())
        // T05: Back returns to where the source was chosen from.
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        until(5_000) { vm.selection.value.scope == ArticleScope.All }
    }

    /** T02: "Source details" opens the existing feed detail of that feed. */
    @Test
    fun theMenuOpensTheDetailsOfThatSource() {
        firstFetchThenShow()
        openMenuOfFirstArticleOfFeed()
        compose.onNodeWithTag("menu_feed_details").performClick()
        until(5_000) { feedDetailsOpened.isNotEmpty() }
        assertEquals(listOf(feedOne), feedDetailsOpened)
    }

    /** T06..T11 at the panel: a chip sets the period, the subtitle says it, closing and opening the panel changes nothing. */
    @Test
    fun thePeriodIsChosenInThePanelAndShownUnderTheTitle() {
        firstFetchThenShow()
        assertEquals("${text(R.string.filter_all)} · ${text(R.string.sort_newest)}", subtitle())
        openPanel()
        compose.onNodeWithTag("period_DAYS_3").performClick()
        compose.waitForIdle()
        assertEquals(PeriodFilter.DAYS_3, vm.selection.value.period)
        until(5_000) { subtitle() == "${text(R.string.filter_all)} · ${text(R.string.period_3d)} · ${text(R.string.sort_newest)}" }
        compose.onNodeWithTag("period_ALL").performClick()
        compose.waitForIdle()
        assertEquals("${text(R.string.filter_all)} · ${text(R.string.sort_newest)}", subtitle())
    }

    /** T19, T26, T27 and the entry in the panel: add a word, see the dot, switch it off, delete it. */
    @Test
    fun aKeywordIsAddedSwitchedOffAndDeletedInTheSheet() {
        firstFetchThenShow()
        openPanel()
        assertFalse("nothing to switch with an empty list", existsAnywhere("hidden_keywords_switch"))
        compose.onNodeWithTag("hidden_keywords_manage").performClick()
        until(5_000) { existsAnywhere("hidden_keywords_sheet") }
        compose.onNodeWithTag("hidden_keywords_input").performTextInput("旧記事 1")
        compose.onNodeWithTag("hidden_keywords_add").performClick()
        until(5_000) { runBlocking { TestData.container.settings.current().hiddenKeywords } == listOf("旧記事 1") }
        assertTrue(runBlocking { TestData.container.settings.current().hiddenKeywordsEnabled })
        compose.onNodeWithTag("hidden_keywords_sheet_switch").performClick()
        until(5_000) { !runBlocking { TestData.container.settings.current().hiddenKeywordsEnabled } }
        compose.onAllNodes(hasTestTag("hidden_keyword_remove"), useUnmergedTree = true)[0].performClick()
        until(5_000) { runBlocking { TestData.container.settings.current().hiddenKeywords }.isEmpty() }
    }
}
