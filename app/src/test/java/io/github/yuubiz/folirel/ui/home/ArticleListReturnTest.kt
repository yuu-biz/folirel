package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.ui.theme.FolirelTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first row on screen leaves the list (read under Unread, unsaved under Saved) and Undo brings it back: it is on screen again
 * where it was. A list keeps its first row by key, which on its own would leave the returned row just above the screen. An article
 * that was never in the list still arrives without moving it.
 */
@RunWith(AndroidJUnit4::class)
class ArticleListReturnTest {
    @get:Rule
    val compose = createComposeRule()

    private var items by mutableStateOf(emptyList<ArticleRow>())

    private fun row(n: Int) = ArticleRow(
        id = "a$n", feedId = "f", title = "記事 $n", summaryText = "概要 $n", linkUrl = "https://example.com/$n", imageUrl = null,
        author = null, publishedAt = 1_000_000L - n, sortAt = 1_000_000L - n, isRead = false, isSaved = false,
        feedName = "feed", feedUrl = "https://feed.example.com/", feedSubscribed = true,
    )

    private fun shown(id: String) = compose.onAllNodes(hasTestTag("article_$id")).fetchSemanticsNodes().isNotEmpty()

    private fun host(mode: ListMode) {
        val host = object : ListHost {
            override fun anchorFor(listKey: String): ListAnchor? = null
            override fun saveAnchor(anchor: ListAnchor) = Unit
            override fun acknowledgeNew(ids: Collection<String>) = Unit
        }
        val actions = ArticleActions(open = {}, openExternal = {}, toggleSaved = {}, setRead = { _, _ -> }, share = {}, showSummary = {})
        val loader = ImageLoader.Builder(ApplicationProvider.getApplicationContext()).build()
        compose.setContent {
            FolirelTheme(ThemeMode.SYSTEM, 1.0f) {
                Box(Modifier.fillMaxWidth().height(600.dp)) {
                    ArticleList(
                        host = host,
                        state = ArticlesState("list", items, loaded = true),
                        mode = mode,
                        showImages = false,
                        cardColumns = 0,
                        cardStyle = CardLayoutStyle.MIXED,
                        imageLoader = loader,
                        actions = actions,
                        newIds = emptySet(),
                        newestFirst = true,
                        isCurrent = true,
                        scrollToTopTick = 0,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun theFirstRowComesBackOnScreen(mode: ListMode) {
        val all = (1..40).map(::row)
        items = all
        host(mode)
        assertTrue(shown("a1"))

        items = all.drop(1)
        compose.waitForIdle()
        assertFalse("$mode: the row left", shown("a1"))

        items = all
        compose.waitForIdle()
        assertTrue("$mode: the returned row is on screen again", shown("a1"))
        assertTrue("$mode: the row below it is still there", shown("a2"))

        // An article that was never in this list arrives at the head: the list does not move for it (a banner announces it).
        items = listOf(row(0)) + all
        compose.waitForIdle()
        assertFalse("$mode: a new arrival does not move the list", shown("a0"))
        assertTrue(shown("a1"))
    }

    @Test
    fun listLayout() = theFirstRowComesBackOnScreen(ListMode.LIST)

    @Test
    fun compactListLayout() = theFirstRowComesBackOnScreen(ListMode.COMPACT_LIST)
}
