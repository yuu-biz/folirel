package io.github.yuubiz.folirel.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.db.ArticleEntity
import io.github.yuubiz.folirel.data.db.ArticleStateEntity
import io.github.yuubiz.folirel.data.db.CategoryEntity
import io.github.yuubiz.folirel.data.db.FeedCategoryEntity
import io.github.yuubiz.folirel.data.db.FeedEntity
import io.github.yuubiz.folirel.model.ArticleQuerySpec
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import io.github.yuubiz.folirel.testutil.TestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B2: the list is read in chunks. A list several chunks long, with many articles sharing one time (so ties fall on chunk borders),
 * still holds every matching article once, in list order, and its observed form follows each change.
 */
@RunWith(AndroidJUnit4::class)
class ArticleListChunksTest {
    private val env = TestEnv()

    @After
    fun tearDown() = env.close()

    private data class Seeded(val id: String, val feed: Int, val sortAt: Long, val read: Boolean)

    private val count = 3_500
    private val seeded = (1..count).map { n ->
        // Seven articles per minute: ties on sortAt everywhere, ordered among themselves by id.
        Seeded("a-%05d".format(n), n % 3, 1_760_000_000_000L + (n / 7) * 60_000L, read = n % 4 != 0)
    }

    private suspend fun seed(): String {
        val account = env.subscriptions.ensureLocalAccount()
        val dao = env.db.backupDao()
        dao.insertCategories(listOf(CategoryEntity("cat", account, "Cat", "[\"Cat\"]")))
        dao.insertFeeds((0..2).map { FeedEntity("feed-$it", account, "https://f$it.example/rss", "https://f$it.example/rss", null, "Feed $it", null, true, false, 0) })
        dao.insertMemberships(listOf(FeedCategoryEntity("feed-1", "cat")))
        dao.insertArticles(seeded.map { ArticleEntity(it.id, account, "feed-${it.feed}", "id:${it.id}", null, null, "Title ${it.id}", "", null, it.sortAt, it.sortAt, it.sortAt, null, it.sortAt) })
        dao.insertArticleStates(seeded.map { ArticleStateEntity(it.id, isRead = it.read, isSaved = false, updatedAt = 0) })
        return account
    }

    private fun expected(sort: SortOrder, keep: (Seeded) -> Boolean = { true }): List<String> {
        val ordered = seeded.filter(keep).sortedWith(compareBy<Seeded> { it.sortAt }.thenBy { it.id }).map { it.id }
        return if (sort == SortOrder.NEWEST) ordered.reversed() else ordered
    }

    private fun spec(account: String, sort: SortOrder, scope: ArticleScope = ArticleScope.All, filter: ReadFilter = ReadFilter.ALL) =
        ArticleQuerySpec(account, scope, filter, sort, categoryIds = if (scope is ArticleScope.Category) listOf(scope.categoryId) else emptyList())

    @Test
    fun everyArticleOnceInListOrderAcrossChunks() = runBlocking(Dispatchers.IO) {
        val account = seed()
        for (sort in SortOrder.entries) {
            assertEquals(expected(sort), env.articles.rows(spec(account, sort)).map { it.id })
            assertEquals(expected(sort) { !it.read }, env.articles.rows(spec(account, sort, filter = ReadFilter.UNREAD)).map { it.id })
            assertEquals(expected(sort) { it.feed == 1 }, env.articles.rows(spec(account, sort, ArticleScope.Category("cat"))).map { it.id })
        }
    }

    @Test
    fun theObservedListFollowsAReadToggleAndAnArrival() = runBlocking(Dispatchers.IO) {
        val account = seed()
        val emissions = Channel<List<ArticleRow>>(Channel.UNLIMITED)
        val job = launch { env.articles.observe(spec(account, SortOrder.NEWEST)).collect { emissions.send(it) } }
        suspend fun next(until: (List<ArticleRow>) -> Boolean): List<ArticleRow> {
            while (true) withTimeout(30_000) { emissions.receive() }.let { if (until(it)) return it }
        }
        assertEquals(expected(SortOrder.NEWEST), next { true }.map { it.id })

        // The oldest article is in the last chunk.
        val oldest = expected(SortOrder.OLDEST).first()
        val wasRead = seeded.first { it.id == oldest }.read
        env.articles.setRead(oldest, !wasRead)
        val toggled = next { rows -> rows.last().isRead != wasRead }
        assertEquals(expected(SortOrder.NEWEST), toggled.map { it.id })

        env.db.backupDao().insertArticles(listOf(ArticleEntity("z-new", account, "feed-0", "id:z-new", null, null, "New", "", null, 1_800_000_000_000L, 1_800_000_000_000L, 1_800_000_000_000L, null, 1_800_000_000_000L)))
        env.db.backupDao().insertArticleStates(listOf(ArticleStateEntity("z-new", isRead = false, isSaved = false, updatedAt = 0)))
        val arrived = next { rows -> rows.firstOrNull()?.id == "z-new" }
        assertEquals(listOf("z-new") + expected(SortOrder.NEWEST), arrived.map { it.id })
        assertTrue(arrived.last().isRead != wasRead)
        job.cancel()
    }
}
