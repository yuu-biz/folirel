package io.github.yuubiz.folirel.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccount(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE backendType = :backendType ORDER BY createdAt, id LIMIT 1")
    suspend fun firstAccountOfType(backendType: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun account(id: String): AccountEntity?

    // Feeds

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFeed(feed: FeedEntity)

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun feed(id: String): FeedEntity?

    @Query("SELECT * FROM feeds WHERE accountId = :accountId AND subscriptionUrl = :subscriptionUrl")
    suspend fun feedByUrl(accountId: String, subscriptionUrl: String): FeedEntity?

    @Query("SELECT * FROM feeds WHERE accountId = :accountId")
    suspend fun feeds(accountId: String): List<FeedEntity>

    /** A feed already fetched from [url] (e.g. moved to HTTPS or permanently redirected there). */
    @Query(
        """SELECT * FROM feeds WHERE accountId = :accountId AND (fetchUrl = :url OR id IN
           (SELECT feedId FROM feed_https_migrations WHERE httpsUrl = :url)) ORDER BY createdAt, id LIMIT 1""",
    )
    suspend fun feedByFetchUrl(accountId: String, url: String): FeedEntity?

    // HTTP → HTTPS migration records

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHttpsMigration(row: FeedHttpsMigrationEntity)

    @Query("SELECT * FROM feed_https_migrations WHERE feedId = :feedId")
    fun observeHttpsMigration(feedId: String): Flow<FeedHttpsMigrationEntity?>

    // URL moves chosen by the user after a discovery (v3)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUrlMove(row: FeedUrlMoveEntity)

    @Query("SELECT m.* FROM feed_url_moves m JOIN feeds f ON f.id = m.feedId WHERE f.accountId = :accountId ORDER BY m.movedAt, m.id")
    suspend fun urlMoves(accountId: String): List<FeedUrlMoveEntity>

    @Query("SELECT * FROM feed_url_moves WHERE feedId = :feedId ORDER BY movedAt DESC, id DESC")
    fun observeUrlMoves(feedId: String): Flow<List<FeedUrlMoveEntity>>

    /** After the fetch URL changed: the errors and waits of the old address do not apply to the new one. */
    @Query(
        "UPDATE feed_fetch_state SET lastErrorCode = NULL, failureCount = 0, nextEligibleAt = NULL, retryAfterUntil = NULL " +
            "WHERE feedId = :feedId",
    )
    suspend fun clearFetchFailure(feedId: String): Int

    /** Approved URL moves plus HTTPS migrations of the feed: whether its address was ever changed on purpose. */
    @Query("SELECT (SELECT COUNT(*) FROM feed_url_moves WHERE feedId = :feedId) + (SELECT COUNT(*) FROM feed_https_migrations WHERE feedId = :feedId)")
    suspend fun addressChangeCount(feedId: String): Int

    @Query("SELECT * FROM feeds WHERE id = :id")
    fun observeFeed(id: String): Flow<FeedEntity?>

    @Query("SELECT * FROM feed_fetch_state WHERE feedId = :feedId")
    fun observeFetchState(feedId: String): Flow<FeedFetchStateEntity?>

    @Query("SELECT m.* FROM feed_https_migrations m JOIN feeds f ON f.id = m.feedId WHERE f.accountId = :accountId")
    suspend fun httpsMigrations(accountId: String): List<FeedHttpsMigrationEntity>

    @Query("SELECT * FROM feeds WHERE accountId = :accountId")
    fun observeFeeds(accountId: String): Flow<List<FeedEntity>>

    @Query("UPDATE feeds SET isSubscribed = :subscribed WHERE id = :id")
    suspend fun setSubscribed(id: String, subscribed: Boolean): Int

    /** Only rows that are not in that state yet change; the return value is what really changed. */
    @Query("UPDATE feeds SET isSubscribed = :subscribed WHERE id IN (:ids) AND isSubscribed != :subscribed")
    suspend fun setSubscribedBulk(ids: List<String>, subscribed: Boolean): Int

    @Query("SELECT id FROM feeds WHERE id IN (:ids) AND isSubscribed = :subscribed")
    suspend fun idsWithSubscribed(ids: List<String>, subscribed: Boolean): List<String>

    @Query("UPDATE feeds SET isPaused = :paused WHERE id = :id")
    suspend fun setPaused(id: String, paused: Boolean): Int

    @Query("UPDATE feeds SET userTitle = :userTitle WHERE id = :id")
    suspend fun setUserTitle(id: String, userTitle: String?): Int

    @Query("UPDATE feeds SET feedTitle = :feedTitle, siteUrl = :siteUrl WHERE id = :id")
    suspend fun updateFeedMetadata(id: String, feedTitle: String?, siteUrl: String?): Int

    @Query("UPDATE feeds SET fetchUrl = :fetchUrl WHERE id = :id")
    suspend fun setFetchUrl(id: String, fetchUrl: String): Int

    // Fetch state

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFetchState(state: FeedFetchStateEntity)

    @Query("SELECT * FROM feed_fetch_state WHERE feedId = :feedId")
    suspend fun fetchState(feedId: String): FeedFetchStateEntity?

    @Update
    suspend fun updateFetchState(state: FeedFetchStateEntity): Int

    @Query("SELECT s.* FROM feed_fetch_state s JOIN feeds f ON f.id = s.feedId WHERE f.accountId = :accountId")
    fun observeFetchStates(accountId: String): Flow<List<FeedFetchStateEntity>>

    @Query("SELECT s.* FROM feed_fetch_state s JOIN feeds f ON f.id = s.feedId WHERE f.accountId = :accountId")
    suspend fun fetchStates(accountId: String): List<FeedFetchStateEntity>

    // Categories

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(category: CategoryEntity)

    @Update
    suspend fun updateCategory(category: CategoryEntity): Int

    @Query("SELECT * FROM categories WHERE accountId = :accountId")
    suspend fun categories(accountId: String): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE accountId = :accountId")
    fun observeCategories(accountId: String): Flow<List<CategoryEntity>>

    @Query("SELECT accountId FROM categories WHERE id = :id")
    suspend fun categoryAccount(id: String): String?

    @Query("DELETE FROM categories WHERE id IN (:ids)")
    suspend fun deleteCategories(ids: List<String>): Int

    // Category preferences (v4): tab order, pin and display overrides

    @Query("SELECT p.* FROM category_preferences p JOIN categories c ON c.id = p.categoryId WHERE c.accountId = :accountId")
    fun observeCategoryPreferences(accountId: String): Flow<List<CategoryPreferenceEntity>>

    @Query("SELECT p.* FROM category_preferences p JOIN categories c ON c.id = p.categoryId WHERE c.accountId = :accountId")
    suspend fun categoryPreferences(accountId: String): List<CategoryPreferenceEntity>

    /** Makes sure the row exists (all defaults) so that a partial update below has something to change. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPreferenceIfAbsent(row: CategoryPreferenceEntity): Long

    @Query("UPDATE category_preferences SET isPinned = :pinned WHERE categoryId = :categoryId")
    suspend fun setPinned(categoryId: String, pinned: Boolean): Int

    @Query("UPDATE category_preferences SET tabOrder = :order WHERE categoryId = :categoryId")
    suspend fun setTabOrder(categoryId: String, order: Int?): Int

    @Query("UPDATE category_preferences SET tabOrder = NULL WHERE categoryId IN (SELECT id FROM categories WHERE accountId = :accountId)")
    suspend fun clearTabOrders(accountId: String): Int

    @Query("UPDATE category_preferences SET listModeOverride = :value WHERE categoryId = :categoryId")
    suspend fun setListModeOverride(categoryId: String, value: String?): Int

    @Query("UPDATE category_preferences SET cardStyleOverride = :value WHERE categoryId = :categoryId")
    suspend fun setCardStyleOverride(categoryId: String, value: String?): Int

    @Query("UPDATE category_preferences SET cardColumnsOverride = :value WHERE categoryId = :categoryId")
    suspend fun setCardColumnsOverride(categoryId: String, value: Int?): Int

    @Query("UPDATE category_preferences SET listModeOverride = NULL, cardStyleOverride = NULL, cardColumnsOverride = NULL WHERE categoryId = :categoryId")
    suspend fun clearLayoutOverrides(categoryId: String): Int

    // Memberships

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembership(membership: FeedCategoryEntity): Long

    @Query("DELETE FROM feed_categories WHERE feedId = :feedId AND categoryId = :categoryId")
    suspend fun deleteMembership(feedId: String, categoryId: String): Int

    @Query("SELECT fc.* FROM feed_categories fc JOIN feeds f ON f.id = fc.feedId WHERE f.accountId = :accountId")
    suspend fun memberships(accountId: String): List<FeedCategoryEntity>

    @Query("SELECT fc.* FROM feed_categories fc JOIN feeds f ON f.id = fc.feedId WHERE f.accountId = :accountId")
    fun observeMemberships(accountId: String): Flow<List<FeedCategoryEntity>>
}
