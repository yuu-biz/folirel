package io.github.yuubiz.folirel.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** P1-T22 (order against hierarchy) and P1-T23 (which display settings a list uses), on the JVM. */
class TabOrderTest {
    private fun node(id: String, vararg path: String) =
        CategoryNode(id, path.toList(), path.joinToString(" / "), path.size - 1, subtreeFeedCount = 1, directFeedIds = emptyList())

    /** In path order, as Structure keeps them. */
    private val a = node("a", "A")
    private val aChild = node("a1", "A", "共通")
    private val b = node("b", "B")
    private val bChild = node("b1", "B", "共通")
    private val slash = node("s", "a/b")
    private val all = listOf(a, aChild, b, bChild, slash)

    private fun ids(list: List<CategoryNode>) = list.map { it.id }

    @Test
    fun withoutPreferencesThePathOrderStays() {
        assertEquals(listOf("a", "a1", "b", "b1", "s"), ids(TabOrder.resolve(all, emptyMap())))
    }

    @Test
    fun pinnedFirstThenTheRest_eachInTheNormalOrder() {
        val prefs = mapOf("b1" to CategoryPreference("b1", isPinned = true), "s" to CategoryPreference("s", isPinned = true))
        assertEquals(listOf("b1", "s", "a", "a1", "b"), ids(TabOrder.resolve(all, prefs)))
    }

    @Test
    fun savedPlacesWinAndNewCategoriesGoToTheEndInPathOrder() {
        // The reader placed three categories; the other two are new since.
        val prefs = mapOf(
            "b" to CategoryPreference("b", tabOrder = 0),
            "a" to CategoryPreference("a", tabOrder = 1),
            "s" to CategoryPreference("s", tabOrder = 2),
        )
        assertEquals(listOf("b", "a", "s", "a1", "b1"), ids(TabOrder.resolve(all, prefs)))
    }

    @Test
    fun unpinningGivesTheNormalPlaceBack() {
        val placed = mapOf(
            "s" to CategoryPreference("s", tabOrder = 0),
            "a" to CategoryPreference("a", tabOrder = 1),
            "b" to CategoryPreference("b", tabOrder = 2, isPinned = true),
        )
        assertEquals(listOf("b", "s", "a", "a1", "b1"), ids(TabOrder.resolve(all, placed)))
        val unpinned = placed + ("b" to placed.getValue("b").copy(isPinned = false))
        assertEquals(listOf("s", "a", "b", "a1", "b1"), ids(TabOrder.resolve(all, unpinned)))
    }

    @Test
    fun reorderingAGroupLeavesTheOtherGroupAndTheTreeAlone() {
        val prefs = mapOf("b" to CategoryPreference("b", isPinned = true), "s" to CategoryPreference("s", isPinned = true))
        // Pinned group is [b, s] (normal order); the reader swaps them.
        val sequence = TabOrder.reorderGroup(all, prefs, pinned = true, wanted = listOf("s", "b"))
        // They exchange the two places they held; a, a1, b1 stay where they were.
        assertEquals(listOf("a", "a1", "s", "b1", "b"), sequence)
        // The tree is untouched by construction: paths are not an input of the result, only their order.
        assertEquals(listOf("A", "共通"), aChild.path)
    }

    @Test
    fun aMemberThatArrivedMeanwhileFollowsTheGivenOnes() {
        val sequence = TabOrder.reorderGroup(all, emptyMap(), pinned = false, wanted = listOf("s", "b1"))
        // 'a', 'a1' and 'b' were not in the request: they follow, in their old order, in the places left over.
        assertEquals(listOf("s", "b1", "a", "a1", "b"), sequence)
    }

    @Test
    fun foreignIdsAreIgnored() {
        val sequence = TabOrder.reorderGroup(all, emptyMap(), pinned = false, wanted = listOf("zzz", "b", "a"))
        assertEquals(listOf("b", "a", "a1", "b1", "s"), sequence)
    }

    // Display settings

    private val global = ResolvedLayout(ListMode.LIST, CardLayoutStyle.MIXED, 0)

    @Test
    fun onlyACategoryHasItsOwnSettings() {
        val prefs = mapOf("a" to CategoryPreference("a", listLayout = ListMode.MAGAZINE, cardStyle = CardLayoutStyle.EVEN, cardColumns = 3))
        assertSame(global, CategoryDisplay.resolve(ArticleScope.All, global, prefs))
        assertSame(global, CategoryDisplay.resolve(ArticleScope.Uncategorized, global, prefs))
        assertSame(global, CategoryDisplay.resolve(ArticleScope.Feed("f"), global, prefs))
        assertEquals(ResolvedLayout(ListMode.MAGAZINE, CardLayoutStyle.EVEN, 3), CategoryDisplay.resolve(ArticleScope.Category("a"), global, prefs))
    }

    @Test
    fun aChildDoesNotInheritFromItsParentAndAnInheritedItemFollowsTheGlobalOne() {
        val prefs = mapOf("a" to CategoryPreference("a", listLayout = ListMode.MAGAZINE), "a1" to CategoryPreference("a1", cardColumns = 2))
        assertEquals(ResolvedLayout(ListMode.LIST, CardLayoutStyle.MIXED, 2), CategoryDisplay.resolve(ArticleScope.Category("a1"), global, prefs))
        val changed = global.copy(listMode = ListMode.COMPACT_LIST, cardStyle = CardLayoutStyle.EVEN)
        assertEquals(ResolvedLayout(ListMode.MAGAZINE, CardLayoutStyle.EVEN, 0), CategoryDisplay.resolve(ArticleScope.Category("a"), changed, prefs))
    }

    @Test
    fun automaticColumnsIsAChoiceNotInheritance() {
        val globalThree = global.copy(cardColumns = 3)
        val auto = mapOf("a" to CategoryPreference("a", cardColumns = 0))
        val inherit = mapOf("a" to CategoryPreference("a", cardColumns = null))
        assertEquals(0, CategoryDisplay.resolve(ArticleScope.Category("a"), globalThree, auto).cardColumns)
        assertEquals(3, CategoryDisplay.resolve(ArticleScope.Category("a"), globalThree, inherit).cardColumns)
    }
}
