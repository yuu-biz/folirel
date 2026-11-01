package io.github.yuubiz.folirel.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.yuubiz.folirel.backend.local.parser.TextNormalizer
import io.github.yuubiz.folirel.data.db.AppDatabase
import io.github.yuubiz.folirel.data.db.ArticleEntity
import io.github.yuubiz.folirel.model.ArticleRow
import io.github.yuubiz.folirel.model.ArticleQuerySpec
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import java.time.Clock

/**
 * Builds list queries from constants only; every user-provided value (ids, search terms) is a bind argument.
 * Search and hidden keywords are LIKE substring matches (work for Japanese) with `%`, `_` and `\` escaped. The period and the
 * hidden keywords are part of the same WHERE as scope, read filter and search, so the list, "mark all as read" and the count
 * always see one set of articles.
 */
object ArticleQuery {
    /** Characters of the summary loaded for list rows (two lines at most are shown). */
    const val LIST_SUMMARY_CHARS = 300

    private const val ROW_COLUMNS =
        "a.id AS id, a.feedId AS feedId, a.title AS title, substr(a.summaryText, 1, $LIST_SUMMARY_CHARS) AS summaryText, " +
            "a.linkUrl AS linkUrl, a.imageUrl AS imageUrl, a.author AS author, a.publishedAt AS publishedAt, " +
            "a.sortAt AS sortAt, s.isRead AS isRead, s.isSaved AS isSaved, " +
            "COALESCE(f.userTitle, f.feedTitle) AS feedName, f.subscriptionUrl AS feedUrl, f.isSubscribed AS feedSubscribed"

    private const val FROM =
        " FROM articles a JOIN article_states s ON s.articleId = a.id JOIN feeds f ON f.id = a.feedId"

    /** Rows in list order; with [after], only the rows that follow it, and with [limit], at most that many. */
    fun rows(spec: ArticleQuerySpec, after: ArticleRow? = null, limit: Int? = null): SimpleSQLiteQuery {
        val (where, args) = where(spec, onlyUnread = false)
        val newest = spec.sort == SortOrder.NEWEST
        val order = if (newest) "a.sortAt DESC, a.id DESC" else "a.sortAt ASC, a.id ASC"
        val next = if (after == null) "" else " AND (a.sortAt, a.id) " + (if (newest) "<" else ">") + " (?, ?)"
        val allArgs = if (after == null) args else args + listOf(after.sortAt, after.id)
        return SimpleSQLiteQuery(
            "SELECT $ROW_COLUMNS$FROM WHERE $where$next ORDER BY $order" + (limit?.let { " LIMIT $it" } ?: ""),
            allArgs.toTypedArray(),
        )
    }

    /** Unread ids matching scope/filter/search: the fixed target set of "mark all as read". */
    fun unreadIds(spec: ArticleQuerySpec): SimpleSQLiteQuery {
        val (where, args) = where(spec, onlyUnread = true)
        return SimpleSQLiteQuery("SELECT a.id$FROM WHERE $where", args.toTypedArray())
    }

    fun unreadCount(spec: ArticleQuerySpec): SimpleSQLiteQuery {
        val (where, args) = where(spec.copy(filter = ReadFilter.ALL, search = ""), onlyUnread = true)
        return SimpleSQLiteQuery("SELECT COUNT(*)$FROM WHERE $where", args.toTypedArray())
    }

    private fun where(spec: ArticleQuerySpec, onlyUnread: Boolean): Pair<String, List<Any>> {
        val clauses = mutableListOf("a.accountId = ?")
        val args = mutableListOf<Any>(spec.accountId)
        // Unsubscribed feeds are hidden from normal lists; their saved articles stay visible under the saved filter.
        val subscribedOnly = spec.filter != ReadFilter.SAVED
        when (val scope = spec.scope) {
            ArticleScope.All -> if (subscribedOnly) clauses += "f.isSubscribed = 1"
            ArticleScope.Uncategorized -> {
                clauses += "NOT EXISTS (SELECT 1 FROM feed_categories fc WHERE fc.feedId = a.feedId)"
                if (subscribedOnly) clauses += "f.isSubscribed = 1"
            }
            is ArticleScope.Category -> {
                val ids = spec.categoryIds.ifEmpty { listOf(scope.categoryId) }
                // EXISTS, not JOIN: an article whose feed is in several selected categories appears once.
                clauses += "EXISTS (SELECT 1 FROM feed_categories fc WHERE fc.feedId = a.feedId AND fc.categoryId IN (" +
                    ids.joinToString(",") { "?" } + "))"
                args.addAll(ids)
                if (subscribedOnly) clauses += "f.isSubscribed = 1"
            }
            is ArticleScope.Feed -> {
                clauses += "a.feedId = ?"
                args += scope.feedId
            }
        }
        when (spec.filter) {
            ReadFilter.ALL -> Unit
            ReadFilter.UNREAD -> clauses += "s.isRead = 0"
            ReadFilter.SAVED -> clauses += "s.isSaved = 1"
        }
        if (onlyUnread && spec.filter != ReadFilter.UNREAD) clauses += "s.isRead = 0"
        spec.periodCutoff?.let {
            clauses += "a.sortAt >= ?"
            args += it
        }
        // Hidden keywords only hide: they never touch read/saved state. Title and summary only (not source, URL or author).
        for (keyword in spec.hiddenKeywords) {
            clauses += "NOT (a.title LIKE ? ESCAPE '\\' OR a.summaryText LIKE ? ESCAPE '\\')"
            val pattern = "%" + escapeLike(keyword) + "%"
            args += pattern
            args += pattern
        }
        for (term in searchTerms(spec.search)) {
            clauses += "(a.title LIKE ? ESCAPE '\\' OR a.summaryText LIKE ? ESCAPE '\\' OR COALESCE(f.userTitle, f.feedTitle, '') LIKE ? ESCAPE '\\')"
            val pattern = "%" + escapeLike(term) + "%"
            args += pattern
            args += pattern
            args += pattern
        }
        return clauses.joinToString(" AND ") to args
    }

    /** Whitespace separates terms (all must match). Terms are NFC-normalized like stored text. */
    fun searchTerms(query: String): List<String> =
        TextNormalizer.normalize(query).split(' ').filter { it.isNotEmpty() }.take(8)

    fun escapeLike(term: String): String =
        term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

class ArticleRepository(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val dao = db.articleDao()

    /**
     * Every row of the list again after each change of a table it reads. The chunks are not one snapshot: a write between two of them
     * invalidates the tables again, so a complete emission follows.
     */
    fun observe(spec: ArticleQuerySpec): Flow<List<ArticleRow>> =
        db.invalidationTracker.createFlow(*LIST_TABLES).conflate().map { rows(spec) }

    /**
     * Read in chunks: one cursor costs more per row the more rows it holds (each further window steps the query again from its first
     * row), so 30,000 rows in one query cost about three times as much as the same rows in chunks (ArticleListCostProbe).
     */
    suspend fun rows(spec: ArticleQuerySpec): List<ArticleRow> {
        val rows = ArrayList<ArticleRow>()
        do {
            val chunk = dao.rows(ArticleQuery.rows(spec, after = rows.lastOrNull(), limit = CHUNK_ROWS))
            rows += chunk
        } while (chunk.size == CHUNK_ROWS)
        return rows
    }

    fun observeUnreadCount(spec: ArticleQuerySpec): Flow<Int> = dao.observeCount(ArticleQuery.unreadCount(spec))

    suspend fun article(id: String): ArticleEntity? = dao.article(id)

    suspend fun countArticles(accountId: String): Int = dao.countArticles(accountId)

    /** Snapshot of unread ids at confirmation time; later arrivals are not included. */
    suspend fun unreadIdsSnapshot(spec: ArticleQuerySpec): List<String> = dao.ids(ArticleQuery.unreadIds(spec))

    /** Marks exactly [ids] as read in one transaction. Returns the number of rows changed. */
    suspend fun markRead(ids: List<String>): Int = db.withTransaction {
        val now = clock.millis()
        ids.chunked(500).sumOf { dao.markReadByIds(it, now) }
    }

    /** Returns false when nothing was changed (e.g. the article does not exist). */
    suspend fun setRead(articleId: String, read: Boolean): Boolean = dao.setRead(articleId, read, clock.millis()) == 1

    suspend fun setSaved(articleId: String, saved: Boolean): Boolean = dao.setSaved(articleId, saved, clock.millis()) == 1

    private companion object {
        const val CHUNK_ROWS = 1_000
        val LIST_TABLES = arrayOf("articles", "article_states", "feeds", "feed_categories", "categories")
    }
}
