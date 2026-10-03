package io.github.yuubiz.feeddeck.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A feed whose icon lookup is due, with what the lookup needs. */
data class IconLookupTarget(
    val feedId: String,
    val subscriptionUrl: String,
    val fetchUrl: String,
    val siteUrl: String?,
    val declaredUrl: String?,
)

/** (feedId, resolvedAt) of a stored icon: the UI loads the bytes lazily and caches by this version. */
data class IconVersion(val feedId: String, val resolvedAt: Long)

@Dao
interface FeedIconDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: FeedIconEntity): Long

    @Query("SELECT * FROM feed_icons WHERE feedId = :feedId")
    suspend fun icon(feedId: String): FeedIconEntity?

    @Query("SELECT png FROM feed_icons WHERE feedId = :feedId AND status = 'OK'")
    suspend fun png(feedId: String): ByteArray?

    /** Declared URL changed (including appearing or disappearing): look up again. */
    @Query(
        """UPDATE feed_icons SET declaredUrl = :url, status = 'PENDING', nextAttemptAt = 0, failureCount = 0
           WHERE feedId = :feedId AND COALESCE(declaredUrl, '') != COALESCE(:url, '')""",
    )
    suspend fun changeDeclared(feedId: String, url: String?): Int

    /**
     * The feed now tells more than before (its site, or its first articles): a lookup that found nothing, or failed,
     * is worth another try at once. A stored icon is left alone.
     */
    @Query("UPDATE feed_icons SET status = 'PENDING', nextAttemptAt = 0 WHERE feedId = :feedId AND png IS NULL AND status IN ('NONE', 'FAILED')")
    suspend fun retryIfNoIcon(feedId: String): Int

    /**
     * Subscribed, active feeds without a row, or whose next lookup time has come. A feed with a stored icon is
     * looked at again after a long while only to notice a changed site icon.
     */
    @Query(
        """SELECT f.id AS feedId, f.subscriptionUrl AS subscriptionUrl, f.fetchUrl AS fetchUrl, f.siteUrl AS siteUrl,
                  i.declaredUrl AS declaredUrl
           FROM feeds f LEFT JOIN feed_icons i ON i.feedId = f.id
           WHERE f.accountId = :accountId AND f.isSubscribed = 1 AND f.isPaused = 0
             AND (i.feedId IS NULL OR i.nextAttemptAt <= :now)
           ORDER BY f.createdAt, f.id LIMIT :limit""",
    )
    suspend fun due(accountId: String, now: Long, limit: Int): List<IconLookupTarget>

    @Query(
        """UPDATE feed_icons SET status = 'OK', sourceKind = :kind, sourceUrl = :sourceUrl, png = :png, width = :width,
           height = :height, resolvedAt = :now, nextAttemptAt = :next, failureCount = 0 WHERE feedId = :feedId""",
    )
    suspend fun markFound(feedId: String, kind: String, sourceUrl: String, png: ByteArray, width: Int, height: Int, now: Long, next: Long): Int

    /** No usable icon on the site. A stored icon is kept (a later lookup that finds nothing does not erase it). */
    @Query(
        """UPDATE feed_icons SET status = CASE WHEN png IS NULL THEN 'NONE' ELSE status END, nextAttemptAt = :next,
           failureCount = 0 WHERE feedId = :feedId""",
    )
    suspend fun markNone(feedId: String, next: Long): Int

    @Query(
        """UPDATE feed_icons SET status = CASE WHEN png IS NULL THEN 'FAILED' ELSE status END, nextAttemptAt = :next,
           failureCount = failureCount + 1 WHERE feedId = :feedId""",
    )
    suspend fun markFailed(feedId: String, next: Long): Int

    @Query(
        """SELECT i.feedId AS feedId, i.resolvedAt AS resolvedAt FROM feed_icons i JOIN feeds f ON f.id = i.feedId
           WHERE f.accountId = :accountId AND i.status = 'OK' AND i.png IS NOT NULL AND i.resolvedAt IS NOT NULL""",
    )
    fun observeVersions(accountId: String): Flow<List<IconVersion>>

    /** Link URLs of the newest articles: where the site really is when the feed declares no site. */
    @Query("SELECT linkUrl FROM articles WHERE feedId = :feedId AND linkUrl IS NOT NULL ORDER BY sortAt DESC, id DESC LIMIT :limit")
    suspend fun recentLinks(feedId: String, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM feed_icons WHERE status = :status")
    suspend fun count(status: String): Int
}
