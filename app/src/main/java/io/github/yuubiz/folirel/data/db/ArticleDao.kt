package io.github.yuubiz.folirel.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.yuubiz.folirel.model.ArticleRow
import kotlinx.coroutines.flow.Flow

/** id and key of a stored article. */
data class ArticleKeyRow(val id: String, val entryKey: String)

@Dao
interface ArticleDao {
    @Query("SELECT id FROM articles WHERE accountId = :accountId AND feedId = :feedId AND entryKey = :entryKey")
    suspend fun findId(accountId: String, feedId: String, entryKey: String): String?

    /** The articles of one feed whose key is one of [keys] (spellings of the same address, V3-R02). */
    @Query("SELECT id, entryKey FROM articles WHERE accountId = :accountId AND feedId = :feedId AND entryKey IN (:keys)")
    suspend fun findByKeys(accountId: String, feedId: String, keys: List<String>): List<ArticleKeyRow>

    /** The articles of one feed that carry exactly this link, title, date and text (B3: a feed that rewrote its GUIDs). */
    @Query(
        """SELECT id, entryKey FROM articles WHERE accountId = :accountId AND feedId = :feedId AND linkUrl = :linkUrl
           AND title = :title AND publishedAt = :publishedAt AND summaryText = :summaryText""",
    )
    suspend fun findSameContent(accountId: String, feedId: String, linkUrl: String, title: String, publishedAt: Long, summaryText: String): List<ArticleKeyRow>

    /** ABORT on conflict. Never REPLACE: SQLite REPLACE deletes the row and cascades to article_states. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertArticle(article: ArticleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertState(state: ArticleStateEntity)

    /** Metadata-only update. id, firstSeenAt, sortAt and article_states are deliberately untouched. */
    @Query(
        """UPDATE articles SET externalEntryId = :externalEntryId, linkUrl = :linkUrl, title = :title,
           summaryText = :summaryText, author = :author, publishedAt = :publishedAt, imageUrl = :imageUrl,
           lastSeenAt = :lastSeenAt WHERE id = :id""",
    )
    suspend fun updateMetadata(
        id: String,
        externalEntryId: String?,
        linkUrl: String?,
        title: String,
        summaryText: String,
        author: String?,
        publishedAt: Long?,
        imageUrl: String?,
        lastSeenAt: Long,
    ): Int

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun article(id: String): ArticleEntity?

    @Query("SELECT * FROM articles WHERE feedId = :feedId ORDER BY sortAt DESC, id DESC")
    suspend fun articlesOfFeed(feedId: String): List<ArticleEntity>

    @Query(
        """SELECT COUNT(*) AS total, COALESCE(SUM(s.isRead = 0), 0) AS unread, COALESCE(SUM(s.isSaved = 1), 0) AS saved
           FROM articles a JOIN article_states s ON s.articleId = a.id WHERE a.feedId = :feedId""",
    )
    fun observeFeedCounts(feedId: String): Flow<FeedArticleCounts>

    /** Unread or saved: protected from the retention cleanup. */
    @Query(
        """SELECT COUNT(*) FROM articles a JOIN article_states s ON s.articleId = a.id
           WHERE a.accountId = :accountId AND (s.isRead = 0 OR s.isSaved = 1)""",
    )
    suspend fun countProtected(accountId: String): Int

    /** Oldest read, unsaved articles first. */
    @Query(
        """SELECT a.id AS id, a.feedId AS feedId, a.entryKey AS entryKey FROM articles a JOIN article_states s ON s.articleId = a.id
           WHERE a.accountId = :accountId AND s.isRead = 1 AND s.isSaved = 0 ORDER BY a.sortAt ASC, a.id ASC LIMIT :limit""",
    )
    suspend fun oldestRemovable(accountId: String, limit: Int): List<RemovableArticle>

    /** article_states rows go with them (ON DELETE CASCADE). */
    @Query("DELETE FROM articles WHERE id IN (:ids)")
    suspend fun deleteArticles(ids: List<String>): Int

    @Query("SELECT entryKey FROM articles WHERE feedId = :feedId ORDER BY sortAt DESC, id DESC LIMIT :limit")
    suspend fun recentEntryKeys(feedId: String, limit: Int): List<String>

    @Query("SELECT * FROM article_states WHERE articleId = :articleId")
    suspend fun state(articleId: String): ArticleStateEntity?

    @Query("UPDATE article_states SET isRead = :isRead, updatedAt = :now WHERE articleId = :articleId")
    suspend fun setRead(articleId: String, isRead: Boolean, now: Long): Int

    @Query("UPDATE article_states SET isSaved = :isSaved, updatedAt = :now WHERE articleId = :articleId")
    suspend fun setSaved(articleId: String, isSaved: Boolean, now: Long): Int

    @Query("UPDATE article_states SET isRead = 1, updatedAt = :now WHERE isRead = 0 AND articleId IN (:articleIds)")
    suspend fun markReadByIds(articleIds: List<String>, now: Long): Int

    @Query("SELECT COUNT(*) FROM articles WHERE accountId = :accountId")
    suspend fun countArticles(accountId: String): Int

    @Query("SELECT COUNT(*) FROM articles WHERE accountId = :accountId")
    fun observeArticleCount(accountId: String): Flow<Int>

    @RawQuery(
        observedEntities = [
            ArticleEntity::class, ArticleStateEntity::class, FeedEntity::class,
            FeedCategoryEntity::class, CategoryEntity::class,
        ],
    )
    fun observeRows(query: SupportSQLiteQuery): Flow<List<ArticleRow>>

    @RawQuery
    suspend fun rows(query: SupportSQLiteQuery): List<ArticleRow>

    @RawQuery
    suspend fun ids(query: SupportSQLiteQuery): List<String>

    @RawQuery(observedEntities = [ArticleEntity::class, ArticleStateEntity::class, FeedEntity::class, FeedCategoryEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>
}

data class FeedArticleCounts(val total: Int, val unread: Int, val saved: Int)

data class RemovableArticle(val id: String, val feedId: String, val entryKey: String)
