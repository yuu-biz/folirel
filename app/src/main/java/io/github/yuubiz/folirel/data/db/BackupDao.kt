package io.github.yuubiz.folirel.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads for the full backup (all inside the caller's one transaction) and the replace of a restore. Nothing else uses it: everyday code
 * never deletes feeds or articles this way.
 */
@Dao
interface BackupDao {
    // Snapshot

    @Query("SELECT * FROM feeds WHERE accountId = :accountId ORDER BY createdAt, id")
    suspend fun feeds(accountId: String): List<FeedEntity>

    @Query("SELECT s.feedId FROM feed_fetch_state s JOIN feeds f ON f.id = s.feedId WHERE f.accountId = :accountId AND s.initialized = 1")
    suspend fun fetchedFeedIds(accountId: String): List<String>

    @Query("SELECT * FROM categories WHERE accountId = :accountId ORDER BY pathSegmentsJson, id")
    suspend fun categories(accountId: String): List<CategoryEntity>

    @Query("SELECT fc.* FROM feed_categories fc JOIN feeds f ON f.id = fc.feedId WHERE f.accountId = :accountId ORDER BY fc.categoryId, fc.feedId")
    suspend fun memberships(accountId: String): List<FeedCategoryEntity>

    /** Keyset pages by id, so a large table is never read at once. */
    @Query("SELECT * FROM articles WHERE accountId = :accountId AND id > :after ORDER BY id LIMIT :limit")
    suspend fun articlesAfter(accountId: String, after: String, limit: Int): List<ArticleEntity>

    @Query(
        """SELECT s.* FROM article_states s JOIN articles a ON a.id = s.articleId
           WHERE a.accountId = :accountId AND s.articleId > :after ORDER BY s.articleId LIMIT :limit""",
    )
    suspend fun statesAfter(accountId: String, after: String, limit: Int): List<ArticleStateEntity>

    @Query("SELECT p.* FROM pruned_entries p JOIN feeds f ON f.id = p.feedId WHERE f.accountId = :accountId ORDER BY p.feedId, p.entryKey")
    suspend fun pruned(accountId: String): List<PrunedEntryEntity>

    @Query("SELECT COUNT(*) FROM feeds WHERE accountId = :accountId")
    suspend fun countFeeds(accountId: String): Int

    @Query("SELECT COUNT(*) FROM feeds WHERE accountId = :accountId AND isSubscribed = 1")
    suspend fun countSubscribed(accountId: String): Int

    @Query("SELECT COUNT(*) FROM categories WHERE accountId = :accountId")
    suspend fun countCategories(accountId: String): Int

    @Query("SELECT COUNT(*) FROM feed_categories fc JOIN feeds f ON f.id = fc.feedId WHERE f.accountId = :accountId")
    suspend fun countMemberships(accountId: String): Int

    @Query("SELECT COUNT(*) FROM articles WHERE accountId = :accountId")
    suspend fun countArticles(accountId: String): Int

    @Query("SELECT COUNT(*) FROM article_states s JOIN articles a ON a.id = s.articleId WHERE a.accountId = :accountId AND s.isRead = 1")
    suspend fun countRead(accountId: String): Int

    @Query("SELECT COUNT(*) FROM article_states s JOIN articles a ON a.id = s.articleId WHERE a.accountId = :accountId AND s.isSaved = 1")
    suspend fun countSaved(accountId: String): Int

    @Query("SELECT COUNT(*) FROM pruned_entries p JOIN feeds f ON f.id = p.feedId WHERE f.accountId = :accountId")
    suspend fun countPruned(accountId: String): Int

    // Replace (children before parents: only article_states, category_preferences and feed_categories cascade)

    @Query("DELETE FROM article_states")
    suspend fun deleteArticleStates()

    @Query("DELETE FROM articles")
    suspend fun deleteArticles()

    @Query("DELETE FROM pruned_entries")
    suspend fun deletePruned()

    @Query("DELETE FROM feed_diagnostics")
    suspend fun deleteDiagnostics()

    @Query("DELETE FROM feed_fetch_state")
    suspend fun deleteFetchStates()

    @Query("DELETE FROM feed_icons")
    suspend fun deleteIcons()

    @Query("DELETE FROM feed_https_migrations")
    suspend fun deleteHttpsMigrations()

    @Query("DELETE FROM feed_url_moves")
    suspend fun deleteUrlMoves()

    @Query("DELETE FROM feed_categories")
    suspend fun deleteMemberships()

    @Query("DELETE FROM category_preferences")
    suspend fun deleteCategoryPreferences()

    @Query("DELETE FROM categories")
    suspend fun deleteCategories()

    @Query("DELETE FROM feeds")
    suspend fun deleteFeeds()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFeeds(rows: List<FeedEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFetchStates(rows: List<FeedFetchStateEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHttpsMigrations(rows: List<FeedHttpsMigrationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUrlMoves(rows: List<FeedUrlMoveEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategories(rows: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategoryPreferences(rows: List<CategoryPreferenceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemberships(rows: List<FeedCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertArticles(rows: List<ArticleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertArticleStates(rows: List<ArticleStateEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPruned(rows: List<PrunedEntryEntity>)
}
