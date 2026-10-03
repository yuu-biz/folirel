package io.github.yuubiz.feeddeck.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** One list row. Built by [io.github.yuubiz.feeddeck.data.repository.ArticleQuery]. */
data class ArticleRow(
    val id: String,
    val feedId: String,
    val title: String,
    val summaryText: String,
    val linkUrl: String?,
    val imageUrl: String?,
    val author: String?,
    val publishedAt: Long?,
    val sortAt: Long,
    val isRead: Boolean,
    val isSaved: Boolean,
    val feedName: String?,
    val feedUrl: String,
    val feedSubscribed: Boolean,
)

@Dao
interface ArticleDao {
    @Query("SELECT id FROM articles WHERE accountId = :accountId AND feedId = :feedId AND entryKey = :entryKey")
    suspend fun findId(accountId: String, feedId: String, entryKey: String): String?

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
