package io.github.yuubiz.feeddeck.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Feed fetch diagnostics and the pruned-entry record. Diagnostics are written with column-level UPDATEs so that
 * overlapping requests and fetches never overwrite each other's fields with stale copies.
 */
@Dao
interface DiagnosticsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiagnostics(rows: List<FeedDiagnosticsEntity>)

    @Query("SELECT * FROM feed_diagnostics WHERE feedId = :feedId")
    suspend fun diagnostics(feedId: String): FeedDiagnosticsEntity?

    @Query("SELECT * FROM feed_diagnostics WHERE feedId = :feedId")
    fun observeDiagnostics(feedId: String): Flow<FeedDiagnosticsEntity?>

    @Query("SELECT d.* FROM feed_diagnostics d JOIN feeds f ON f.id = d.feedId WHERE f.accountId = :accountId")
    fun observeAllDiagnostics(accountId: String): Flow<List<FeedDiagnosticsEntity>>

    @Query(
        """UPDATE feed_diagnostics SET requestId = :requestId, requestReason = :reason, requestScope = :scope,
           requestForce = :force, requestedAt = :now, result = NULL, resultAt = NULL,
           pendingSince = COALESCE(pendingSince, :now) WHERE feedId IN (:feedIds)""",
    )
    suspend fun markRequested(feedIds: List<String>, requestId: String, reason: String, scope: String, force: Boolean, now: Long)

    /** Not attempted for a reason known up front (backoff, HTTP disabled, offline). Pending state is unchanged. */
    @Query(
        """UPDATE feed_diagnostics SET requestId = :requestId, requestReason = :reason, requestScope = :scope,
           requestForce = :force, requestedAt = :now, result = :result, resultAt = :now WHERE feedId IN (:feedIds)""",
    )
    suspend fun markSkipped(feedIds: List<String>, result: String, requestId: String, reason: String, scope: String, force: Boolean, now: Long)

    /** The request stopped before a result (not started / cancelled / continuing elsewhere). Still pending. */
    @Query("UPDATE feed_diagnostics SET result = :result, resultAt = :now WHERE feedId IN (:feedIds) AND pendingSince IS NOT NULL")
    suspend fun markUnfinished(feedIds: List<String>, result: String, now: Long)

    @Query("UPDATE feed_diagnostics SET attemptStartedAt = :now, attemptFinishedAt = NULL WHERE feedId = :feedId")
    suspend fun attemptStarted(feedId: String, now: Long)

    @Query(
        """UPDATE feed_diagnostics SET attemptFinishedAt = :now, httpStatus = :httpStatus, validatorsSent = :validatorsSent,
           errorCode = :errorCode, errorDetail = :errorDetail, result = :result, resultAt = :now, pendingSince = NULL
           WHERE feedId = :feedId""",
    )
    suspend fun attemptFinished(
        feedId: String,
        now: Long,
        httpStatus: Int?,
        validatorsSent: Boolean,
        errorCode: String?,
        errorDetail: String?,
        result: String,
    )

    @Query(
        """UPDATE feed_diagnostics SET bodyAt = :now, bodyBytes = :bytes, rawEntries = :raw, uniqueEntries = :unique,
           undatedEntries = :undated, oldestPublishedAt = :oldest, newestPublishedAt = :newest,
           insertedArticles = :inserted, updatedArticles = :updated, skippedPruned = :skippedPruned WHERE feedId = :feedId""",
    )
    suspend fun bodyApplied(
        feedId: String,
        now: Long,
        bytes: Long,
        raw: Int,
        unique: Int,
        undated: Int,
        oldest: Long?,
        newest: Long?,
        inserted: Int,
        updated: Int,
        skippedPruned: Int,
    )

    @Query("SELECT d.feedId FROM feed_diagnostics d JOIN feeds f ON f.id = d.feedId WHERE f.accountId = :accountId AND d.pendingSince IS NOT NULL")
    suspend fun pendingFeedIds(accountId: String): List<String>

    // Pruned entries (retention cleanup)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPruned(rows: List<PrunedEntryEntity>)

    @Query("UPDATE pruned_entries SET lastSeenAt = :now WHERE feedId = :feedId AND entryKey = :entryKey")
    suspend fun touchPruned(feedId: String, entryKey: String, now: Long): Int

    @Query("DELETE FROM pruned_entries WHERE lastSeenAt < :before")
    suspend fun forgetPrunedBefore(before: Long): Int

    @Query("SELECT COUNT(*) FROM pruned_entries")
    suspend fun prunedCount(): Int
}
