package io.github.yuubiz.feeddeck.data.db

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

    @Query("SELECT * FROM feeds WHERE accountId = :accountId")
    fun observeFeeds(accountId: String): Flow<List<FeedEntity>>

    @Query("UPDATE feeds SET isSubscribed = :subscribed WHERE id = :id")
    suspend fun setSubscribed(id: String, subscribed: Boolean): Int

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

    @Query("DELETE FROM categories WHERE id IN (:ids)")
    suspend fun deleteCategories(ids: List<String>): Int

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
