package io.github.yuubiz.folirel

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.yuubiz.folirel.app.MainActivity
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.data.repository.FeedStore
import io.github.yuubiz.folirel.data.repository.FetchMetadata
import io.github.yuubiz.folirel.data.repository.IdGenerator
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock

/**
 * P1-LAYOUT on the real app (MainActivity, pager, display panel, Room, DataStore): each category shows its own layout, also while the
 * pager is dragged between two pages; a change is written where the panel says; nothing is read, saved or fetched by it; and the
 * same article stays in view when the layout changes.
 */
@RunWith(AndroidJUnit4::class)
class P1LayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var seeded: Map<String, List<String>>
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val repo get() = TestData.container.subscriptions
    private val settings get() = TestData.container.settings

    private val alpha get() = ArticleScope.Category(seeded.keys.elementAt(0))
    private val beta get() = ArticleScope.Category(seeded.keys.elementAt(1))
    private val gamma get() = ArticleScope.Category(seeded.keys.elementAt(2))

    @Before
    fun seed() {
        TestData.reset()
        seeded = TestData.seedCategories(listOf("Alpha", "Beta", "Gamma"), perCategory = 40)
        runBlocking { settings.setListMode(ListMode.LIST) }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("category_pager")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        Thread.sleep(600)
    }

    private fun account() = runBlocking { repo.ensureLocalAccount() }

    private fun override(scope: ArticleScope.Category, mode: ListMode?, style: CardLayoutStyle? = null, columns: Int? = null) = runBlocking {
        repo.setCategoryListLayout(account(), scope.categoryId, mode)
        if (style != null) repo.setCategoryCardStyle(account(), scope.categoryId, style)
        if (columns != null) repo.setCategoryCardColumns(account(), scope.categoryId, columns)
    }

    private fun prefs() = runBlocking { repo.observeStructure(account()).first().preferences }

    private fun page(scope: ArticleScope) = hasTestTag("page_${scope.encode()}")

    private fun listNode(scope: ArticleScope): SemanticsNode? =
        compose.onAllNodes(hasTestTag("article_list") and hasAnyAncestor(page(scope))).fetchSemanticsNodes().firstOrNull()

    /** A magazine grid has cards narrower than the page; the one-column lists have rows as wide as the page. */
    private fun isGrid(scope: ArticleScope): Boolean {
        val pageWidth = compose.onAllNodes(page(scope)).fetchSemanticsNodes().firstOrNull()?.size?.width ?: return false
        val cards = compose.onAllNodes(hasAnyAncestor(page(scope)) and androidx.compose.ui.test.SemanticsMatcher("an article") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.let { tag -> tag.startsWith("article_") && tag != "article_list" } == true
        }).fetchSemanticsNodes()
        return cards.isNotEmpty() && cards.any { it.size.width < pageWidth * 0.8f }
    }

    /** The height of the first article card or row on the page, px: a compact row is much lower than a list row. */
    private fun firstArticleHeight(scope: ArticleScope, firstId: String): Int? =
        compose.onAllNodes(hasTestTag("article_$firstId") and hasAnyAncestor(page(scope))).fetchSemanticsNodes().firstOrNull()?.size?.height

    private fun frames(n: Int) = repeat(n) { Thread.sleep(5); compose.mainClock.advanceTimeByFrame() }

    private fun readOrSaved() = seeded.values.flatten().count { TestData.state(it).isRead || TestData.state(it).isSaved }

    /** P1-T24: A is Magazine, B Compact, C inherits (List): while the pager is dragged between two pages each shows its own layout. */
    @Test
    fun eachPageShowsItsOwnLayoutAlsoWhileDraggedBetweenTwoPages() {
        // Two columns, so that a magazine page is told from a list by its narrower cards (auto is one column at this width).
        override(alpha, ListMode.MAGAZINE, columns = 2)
        override(beta, ListMode.COMPACT_LIST)
        runBlocking { settings.setLastScope(beta) }
        launch()
        val firstBeta = seeded.getValue(beta.categoryId).first()
        val firstAlpha = seeded.getValue(alpha.categoryId).first()
        val firstGamma = seeded.getValue(gamma.categoryId).first()
        compose.waitUntil(10_000) { firstArticleHeight(beta, firstBeta) != null }
        val compact = firstArticleHeight(beta, firstBeta)!!
        assertFalse("B is not a grid", isGrid(beta))
        val width = compose.onNodeWithTag("category_pager").fetchSemanticsNode().size.width.toFloat()
        compose.mainClock.autoAdvance = false
        try {
            // Toward C (to the left): B beside C, 40 % of the way.
            compose.onNodeWithTag("category_pager").performTouchInput { down(center); moveBy(Offset(-width * 0.4f, 0f)) }
            frames(5)
            val c = firstArticleHeight(gamma, firstGamma)
            val b = firstArticleHeight(beta, firstBeta)
            Log.i("P1LAYOUT", "dragging to C: B=$b px (compact), C=$c px (list)")
            assertTrue("both pages are on screen", b != null && c != null)
            assertEquals("B keeps its compact rows", compact, b!!)
            assertTrue("C is a list, higher rows than B's compact ones ($c vs $b)", c!! > b * 1.3f)
            assertFalse("C is not a grid", isGrid(gamma))
            // Back across B to A (to the right): A is a grid while B is still a compact list.
            compose.onNodeWithTag("category_pager").performTouchInput { moveBy(Offset(width * 0.8f, 0f)) }
            var n = 0
            while (!isGrid(alpha) && n++ < 60) frames(1)
            assertTrue("A is a magazine grid beside B", isGrid(alpha))
            assertFalse("B is not changed by A being beside it", isGrid(beta))
            assertEquals("B keeps its compact rows", compact, firstArticleHeight(beta, firstBeta)!!)
            assertTrue("A shows its first article", firstArticleHeight(alpha, firstAlpha) != null)
            compose.onNodeWithTag("category_pager").performTouchInput { up() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertEquals("nothing read or saved", 0, readOrSaved())
    }

    /**
     * P1-T25 and P1-T26: one feed in two categories. A change of A's layout through the panel is A's own: B keeps its layout, the
     * articles, read and saved state are untouched; the global default changes only what inherits; resetting A leaves its pin.
     */
    @Test
    fun aChangeInOneCategoryIsOnlyThatCategoriesAndAGlobalChangeOnlyReachesWhatInherits() = runBlocking {
        TestData.reset()
        val c = TestData.container
        val acc = c.subscriptions.ensureLocalAccount()
        val opml = """<opml version="2.0"><body>
            <outline text="Shared A"><outline text="common" xmlUrl="https://feed.example.com/common.xml"/></outline>
            <outline text="Shared B"><outline text="common" xmlUrl="https://feed.example.com/common.xml"/></outline>
            <outline text="Own"><outline text="own" xmlUrl="https://feed.example.com/own.xml"/></outline></body></opml>"""
        c.subscriptions.commitImport(acc, OpmlParser.parse(opml.toByteArray()))
        val store = FeedStore(c.database, Clock.systemUTC(), IdGenerator.Random)
        val now = System.currentTimeMillis()
        val feeds = c.database.subscriptionDao().feeds(acc).associateBy { it.subscriptionUrl }
        for (url in listOf("common", "own")) {
            val entries = (0 until 20).map { NormalizedEntry("id:$url-$it", "$url 記事 $it", "https://www.example.com/", "概要 $it", null, now - it * 60_000L, null) }
            store.applySuccess(feeds.getValue("https://feed.example.com/$url.xml").id, NormalizedFeed(url, null, entries), FetchMetadata(null, null, "https://feed.example.com/$url.xml", null))
        }
        val cats = c.database.subscriptionDao().categories(acc).associateBy { it.name }
        val a = ArticleScope.Category(cats.getValue("Shared A").id)
        val b = ArticleScope.Category(cats.getValue("Shared B").id)
        val articleIds = c.database.articleDao().articlesOfFeed(feeds.getValue("https://feed.example.com/common.xml").id).map { it.id }
        settings.setListMode(ListMode.LIST)
        settings.setLastScope(a)
        launch()
        val firstId = articleIds.first()
        compose.waitUntil(10_000) { firstArticleHeight(a, firstId) != null }
        val listHeight = firstArticleHeight(a, firstId)!!
        // A's layout through the panel: the category's own choice.
        compose.onNodeWithTag("display_open").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("layout_COMPACT_LIST")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("layout_COMPACT_LIST").performClick()
        compose.waitForIdle()
        device.pressBack()
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals(ListMode.COMPACT_LIST, prefs().getValue(a.categoryId).listLayout)
        assertEquals("the global default is untouched", ListMode.LIST, settings.current().listMode)
        assertTrue("A is compact now", firstArticleHeight(a, firstId)!! < listHeight * 0.8f)
        assertEquals("B has no setting of its own", null, prefs()[b.categoryId]?.listLayout)
        assertEquals("the articles are the same ones", articleIds, c.database.articleDao().articlesOfFeed(feeds.getValue("https://feed.example.com/common.xml").id).map { it.id })
        assertEquals(0, articleIds.count { TestData.state(it).isRead || TestData.state(it).isSaved })
        // B still shows the list; so does Own. Then the global default changes: both follow it, A (own setting) does not.
        compose.onNodeWithTag("category_pager").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { firstArticleHeight(b, firstId) != null }
        assertEquals("B (inherits) has list rows", listHeight, firstArticleHeight(b, firstId)!!)
        settings.setCardColumns(2)
        settings.setListMode(ListMode.MAGAZINE)
        compose.waitForIdle()
        Thread.sleep(600)
        assertTrue("B follows the new default", isGrid(b))
        // Pin A, reset its display: the pin stays, A follows the default again.
        repo.setCategoryPinned(acc, a.categoryId, true)
        repo.resetCategoryLayout(acc, a.categoryId)
        assertTrue("the pin stayed", prefs().getValue(a.categoryId).isPinned)
        assertFalse(prefs().getValue(a.categoryId).overridesLayout)
    }

    /** P1-T27: the same article stays in view when the layout of the shown category changes, and after the screen is recreated. */
    @Test
    fun theSameArticleStaysInViewWhenTheLayoutChangesAndAfterARotationLikeRecreate() {
        runBlocking { settings.setLastScope(alpha) }
        launch()
        val ids = seeded.getValue(alpha.categoryId)
        compose.waitUntil(10_000) { listNode(alpha) != null }
        compose.onAllNodes(hasTestTag("article_list") and hasAnyAncestor(page(alpha))).onFirst().performScrollToIndex(20)
        compose.waitForIdle()
        Thread.sleep(300)
        val target = ids[20]
        assertTrue("the 21st article is on screen", firstArticleHeight(alpha, target) != null)
        for (mode in listOf(ListMode.COMPACT_LIST, ListMode.MAGAZINE, ListMode.LIST)) {
            compose.onNodeWithTag("display_open").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("layout_${mode.name}")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("layout_${mode.name}").performClick()
            compose.waitForIdle()
            device.pressBack()
            compose.waitForIdle()
            Thread.sleep(600)
            compose.waitForIdle()
            val visible = compose.onAllNodes(hasTestTag("article_${ids[20]}") and hasAnyAncestor(page(alpha))).fetchSemanticsNodes().isNotEmpty()
            val anyNear = (16..26).any { i -> compose.onAllNodes(hasTestTag("article_${ids[i]}") and hasAnyAncestor(page(alpha))).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("after $mode an article near the 21st is still in view (target visible: $visible)", anyNear)
            assertEquals("the layout of A is its own choice", mode, prefs().getValue(alpha.categoryId).listLayout)
        }
        assertEquals("the global default was never written by the panel", ListMode.LIST, runBlocking { settings.current().listMode })
        scenario!!.recreate()
        compose.waitUntil(10_000) { listNode(alpha) != null }
        Thread.sleep(600)
        assertTrue("after recreate an article near the 21st is in view", (16..26).any { i -> compose.onAllNodes(hasTestTag("article_${ids[i]}") and hasAnyAncestor(page(alpha))).fetchSemanticsNodes().isNotEmpty() })
        assertEquals(0, readOrSaved())
    }

    /** P1-T28: three columns asked for in a narrow window: the stored choice stays 3, the grid draws what fits. */
    @Test
    fun aThreeColumnChoiceIsKeptAsChosenWhateverFitsOnScreen() {
        override(alpha, ListMode.MAGAZINE, CardLayoutStyle.EVEN, columns = 3)
        runBlocking { settings.setLastScope(alpha) }
        launch()
        compose.waitUntil(10_000) { isGrid(alpha) }
        assertEquals(3, prefs().getValue(alpha.categoryId).cardColumns)
        assertEquals(0, runBlocking { settings.current().cardColumns })
        assertNotEquals(null, listNode(alpha))
    }

    /** The details sheet of a category: each of the three items has its own "use the default"; reset takes only these three back. */
    @Test
    fun theDetailsSheetEditsEachItemAndResetKeepsThePinAndTheOrder() {
        runBlocking {
            settings.setLastScope(beta)
            repo.setCategoryPinned(account(), beta.categoryId, true)
        }
        launch()
        compose.onNodeWithTag("display_open").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("layout_details")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("layout_details").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("category_layout_sheet")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cl_list_MAGAZINE").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cl_style_EVEN").performClick()
        compose.onNodeWithTag("cl_cols_0").performClick() // automatic: a choice, not inheritance
        compose.waitForIdle()
        Thread.sleep(300)
        var p = prefs().getValue(beta.categoryId)
        assertEquals(listOf<Any?>(ListMode.MAGAZINE, CardLayoutStyle.EVEN, 0), listOf(p.listLayout, p.cardStyle, p.cardColumns))
        compose.onNodeWithTag("cl_cols_INHERIT").performClick()
        compose.waitForIdle()
        Thread.sleep(300)
        p = prefs().getValue(beta.categoryId)
        assertEquals(null, p.cardColumns)
        assertEquals("the other two stay", ListMode.MAGAZINE, p.listLayout)
        compose.onNodeWithTag("cl_reset").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { !prefs().getValue(beta.categoryId).overridesLayout }
        p = prefs().getValue(beta.categoryId)
        assertFalse(p.overridesLayout)
        assertTrue("the pin stays", p.isPinned)
        assertEquals(0, readOrSaved())
    }
}
