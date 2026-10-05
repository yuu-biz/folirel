package io.github.yuubiz.folirel.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.opml.OpmlParser
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.model.Structure
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P1-ORG on the real repository and Room (in memory): order, pins, display overrides, and what they must not touch. */
@RunWith(AndroidJUnit4::class)
class CategoryPreferencesTest {
    private val env = TestEnv()
    private val dao = env.db.subscriptionDao()
    private val repo = env.subscriptions

    @After
    fun tearDown() = env.close()

    private suspend fun import(account: String, vararg paths: List<String>) {
        fun feed(i: Int, path: List<String>): String {
            val leaf = """<outline text="f$i" xmlUrl="https://example.com/f$i.xml"/>"""
            return path.foldRight(leaf) { seg, inner -> """<outline text="$seg">$inner</outline>""" }
        }
        val opml = """<opml version="2.0"><body>${paths.mapIndexed { i, p -> feed(i, p) }.joinToString("")}</body></opml>"""
        repo.commitImport(account, OpmlParser.parse(opml.toByteArray()))
    }

    private suspend fun structure(account: String): Structure = repo.observeStructure(account).first()

    private suspend fun tabIds(account: String) = structure(account).tabCategories.map { it.display }

    private suspend fun idOf(account: String, display: String) = structure(account).categories.first { it.display == display }.id

    @Test
    fun nothingChangesOnTheFirstStartAndOrderFollowsPins() = runBlocking {
        val account = repo.ensureLocalAccount()
        import(account, listOf("A"), listOf("B"), listOf("C"))
        assertEquals(listOf("A", "B", "C"), tabIds(account))
        assertTrue("no row until the reader chooses", dao.categoryPreferences(account).isEmpty())
        assertTrue(repo.setCategoryPinned(account, idOf(account, "C"), true))
        assertEquals(listOf("C", "A", "B"), tabIds(account))
        // Unpinning gives the normal place back.
        assertTrue(repo.setCategoryPinned(account, idOf(account, "C"), false))
        assertEquals(listOf("A", "B", "C"), tabIds(account))
    }

    @Test
    fun reorderingKeepsTheHierarchyMembershipsAndStateAndSurvivesRenameAndReimport() = runBlocking {
        val account = repo.ensureLocalAccount()
        import(account, listOf("A"), listOf("A", "x"), listOf("B"), listOf("B", "x"))
        val pathsBefore = dao.categories(account).associate { it.id to it.pathSegmentsJson }
        val membershipsBefore = dao.memberships(account).toSet()
        val feed = dao.feeds(account).first()
        env.store.applySuccess(feed.id, NormalizedFeed("t", null, listOf(NormalizedEntry("id:1", "t", null, "", null, null, null))), FetchMetadata(null, null, "u", null))
        val article = env.db.articleDao().articlesOfFeed(feed.id).single().id
        env.articles.setSaved(article, true)

        val b = idOf(account, "B")
        assertTrue(repo.reorderCategoryTabs(account, pinned = false, orderedIds = listOf(b, idOf(account, "A"), idOf(account, "A / x"), idOf(account, "B / x"))))
        assertEquals(listOf("B", "A", "A / x", "B / x"), tabIds(account))
        assertEquals("paths are the tree and do not move", pathsBefore, dao.categories(account).associate { it.id to it.pathSegmentsJson })
        assertEquals(membershipsBefore, dao.memberships(account).toSet())
        assertTrue(env.db.articleDao().state(article)!!.isSaved)

        // A rename keeps the id and so the place; the new name does not matter.
        repo.renameCategory(b, "Z")
        assertEquals(listOf("Z", "A", "A / x", "Z / x"), tabIds(account))
        // The same OPML again adds nothing and resets nothing.
        import(account, listOf("A"), listOf("A", "x"), listOf("Z"), listOf("Z", "x"))
        assertEquals(listOf("Z", "A", "A / x", "Z / x"), tabIds(account))
    }

    @Test
    fun aNewCategoryGoesToTheEndAndTheSavedOrderIsNotRecomputed() = runBlocking {
        val account = repo.ensureLocalAccount()
        import(account, listOf("A"), listOf("B"), listOf("C"))
        repo.reorderCategoryTabs(account, false, listOf(idOf(account, "C"), idOf(account, "B"), idOf(account, "A")))
        import(account, listOf("0 newcomer"), listOf("1 newcomer"))
        // Both come after the reader's three, in path order, though "0 newcomer" sorts before "A".
        assertEquals(listOf("C", "B", "A", "0 newcomer", "1 newcomer"), tabIds(account))
    }

    @Test
    fun resetForgetsOnlyThePlaces() = runBlocking {
        val account = repo.ensureLocalAccount()
        import(account, listOf("A"), listOf("B"), listOf("C"))
        val c = idOf(account, "C")
        repo.setCategoryPinned(account, c, true)
        repo.setCategoryListLayout(account, c, ListMode.MAGAZINE)
        repo.reorderCategoryTabs(account, false, listOf(idOf(account, "B"), idOf(account, "A")))
        repo.resetCategoryTabOrder(account)
        val pref = structure(account).preferences.getValue(c)
        assertTrue(pref.isPinned)
        assertEquals(ListMode.MAGAZINE, pref.listLayout)
        assertNull(pref.tabOrder)
        assertEquals(listOf("C", "A", "B"), tabIds(account))
    }

    @Test
    fun partialUpdatesKeepTheOtherFieldsAndAutomaticColumnsIsNotInheritance() = runBlocking {
        val account = repo.ensureLocalAccount()
        import(account, listOf("A"))
        val a = idOf(account, "A")
        repo.setCategoryPinned(account, a, true)
        repo.setCategoryListLayout(account, a, ListMode.COMPACT_LIST)
        repo.setCategoryCardStyle(account, a, CardLayoutStyle.EVEN)
        repo.setCategoryCardColumns(account, a, 0)
        var p = structure(account).preferences.getValue(a)
        assertEquals(listOf<Any?>(true, ListMode.COMPACT_LIST, CardLayoutStyle.EVEN, 0), listOf(p.isPinned, p.listLayout, p.cardStyle, p.cardColumns))
        repo.setCategoryCardColumns(account, a, null)
        p = structure(account).preferences.getValue(a)
        assertNull("null inherits", p.cardColumns)
        assertEquals(ListMode.COMPACT_LIST, p.listLayout)
        // Reset of the display settings leaves the pin and the place.
        repo.reorderCategoryTabs(account, true, listOf(a))
        repo.resetCategoryLayout(account, a)
        p = structure(account).preferences.getValue(a)
        assertTrue(p.isPinned)
        assertEquals(0, p.tabOrder)
        assertFalse(p.overridesLayout)
    }

    @Test
    fun anotherAccountsCategoryIsRefused() = runBlocking {
        val account = repo.ensureLocalAccount()
        import(account, listOf("A"))
        val a = idOf(account, "A")
        assertFalse(repo.setCategoryPinned("someone-else", a, true))
        assertFalse(repo.setCategoryListLayout("someone-else", a, ListMode.MAGAZINE))
        assertFalse(repo.reorderCategoryTabs("someone-else", false, listOf(a)))
        assertFalse(repo.resetCategoryLayout("someone-else", a))
        assertTrue("nothing was written", dao.categoryPreferences(account).isEmpty())
    }

    @Test
    fun noFeedLeftKeepsTheSettingsAndDeletingTheCategoryRemovesThem() = runBlocking {
        val account = repo.ensureLocalAccount()
        import(account, listOf("A"), listOf("B"))
        val a = idOf(account, "A")
        repo.setCategoryPinned(account, a, true)
        repo.setCategoryListLayout(account, a, ListMode.MAGAZINE)
        // Unsubscribing the only feed: no tab any more, but the settings stay with the category.
        val feed = dao.feeds(account).first { f -> dao.memberships(account).any { it.feedId == f.id && it.categoryId == a } }
        repo.unsubscribe(feed.id)
        assertEquals(listOf("B"), tabIds(account))
        assertTrue(structure(account).preferences.getValue(a).isPinned)
        repo.resubscribe(feed.id)
        assertEquals(listOf("A", "B"), tabIds(account))
        assertEquals(ListMode.MAGAZINE, structure(account).preferences.getValue(a).listLayout)
        // Deleting the category removes its row (the feed and its articles stay).
        repo.deleteCategory(a)
        assertTrue(dao.categoryPreferences(account).isEmpty())
        assertEquals(2, dao.feeds(account).size)
    }
}
