package io.github.yuubiz.folirel.data.repository

import androidx.room.withTransaction
import io.github.yuubiz.folirel.data.db.AppDatabase
import io.github.yuubiz.folirel.data.db.PrunedEntryEntity
import java.time.Clock

/** What a retention limit would do now. Unread and saved articles are protected and never counted as removable. */
data class RetentionPreview(
    val limit: Int,
    val total: Int,
    /** Unread or saved: never removed. */
    val protectedCount: Int,
    /** Oldest read, unsaved articles that would be removed now. */
    val toRemove: Int,
) {
    /** After removal, still above the limit because of protected articles. */
    val overLimitBecauseProtected: Boolean get() = limit > 0 && total - toRemove > limit
}

/**
 * "Articles to keep on the device" (CFG-02). Removes only the oldest read, unsaved articles above the limit of
 * the whole local account (each article counted once, whatever its categories). Each removed entry is recorded
 * in pruned_entries so that a feed still returning it does not bring it back as a new unread article; records
 * not seen in any response for [PrunedEntryEntity.PRUNED_TTL_DAYS] days are forgotten.
 */
class RetentionManager(private val db: AppDatabase, private val clock: Clock) {
    private val dao = db.articleDao()
    private val diagnostics = db.diagnosticsDao()

    suspend fun preview(accountId: String, limit: Int): RetentionPreview {
        val total = dao.countArticles(accountId)
        val protected = dao.countProtected(accountId)
        val removable = total - protected
        val toRemove = if (limit <= 0) 0 else (total - limit).coerceAtLeast(0).coerceAtMost(removable)
        return RetentionPreview(limit, total, protected, toRemove)
    }

    /** Applies [limit] now. Returns the number of removed articles. */
    suspend fun apply(accountId: String, limit: Int): Int {
        if (limit <= 0) return 0
        return db.withTransaction {
            val now = clock.millis()
            val count = preview(accountId, limit).toRemove
            var removed = 0
            if (count > 0) {
                val victims = dao.oldestRemovable(accountId, count)
                diagnostics.insertPruned(victims.map { PrunedEntryEntity(it.feedId, it.entryKey, prunedAt = now, lastSeenAt = now) })
                removed = victims.chunked(500).sumOf { chunk -> dao.deleteArticles(chunk.map { it.id }) }
            }
            diagnostics.forgetPrunedBefore(now - PrunedEntryEntity.PRUNED_TTL_DAYS * DAY_MS)
            removed
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
