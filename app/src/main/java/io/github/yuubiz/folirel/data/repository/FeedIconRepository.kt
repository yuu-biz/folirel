package io.github.yuubiz.folirel.data.repository

import io.github.yuubiz.folirel.backend.local.icon.FeedIconResolver
import io.github.yuubiz.folirel.data.db.AppDatabase
import io.github.yuubiz.folirel.data.db.FeedIconEntity
import io.github.yuubiz.folirel.data.db.IconStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** A feed whose icon lookup is due, with everything the resolver needs. */
class IconLookup(val feedId: String, val target: FeedIconResolver.Target)

/**
 * Cache of resolved source icons and the schedule of lookups. A lookup runs once per feed (not per article, not
 * per redraw); a found icon is re-checked after [FOUND_RECHECK_DAYS], nothing found after [NONE_RECHECK_DAYS],
 * a temporary failure after 1 h, doubling up to [FAILED_MAX_HOURS]. A changed icon URL in the feed starts a new
 * lookup at once. The icon lookup never touches the feed's fetch state.
 */
class FeedIconRepository(private val db: AppDatabase, private val clock: Clock) {
    private val dao = db.iconDao()

    suspend fun due(accountId: String, limit: Int): List<IconLookup> {
        val now = clock.millis()
        return dao.due(accountId, now, limit).map { t ->
            IconLookup(
                t.feedId,
                FeedIconResolver.Target(
                    declaredUrl = t.declaredUrl,
                    siteUrl = t.siteUrl,
                    articleLinks = if (t.siteUrl == null) dao.recentLinks(t.feedId, RECENT_LINKS) else emptyList(),
                ),
            )
        }
    }

    suspend fun record(feedId: String, result: FeedIconResolver.Result) {
        val now = clock.millis()
        dao.insertIgnore(FeedIconEntity(feedId = feedId, status = IconStatus.PENDING.name))
        when (result) {
            is FeedIconResolver.Result.Found -> dao.markFound(
                feedId, result.kind, result.sourceUrl, result.icon.png, result.icon.width, result.icon.height,
                now, now + TimeUnit.DAYS.toMillis(FOUND_RECHECK_DAYS),
            )
            FeedIconResolver.Result.None -> dao.markNone(feedId, now + TimeUnit.DAYS.toMillis(NONE_RECHECK_DAYS))
            FeedIconResolver.Result.Retry -> {
                val failures = (dao.icon(feedId)?.failureCount ?: 0) + 1
                val hours = min(1L shl (failures - 1).coerceIn(0, 10), FAILED_MAX_HOURS)
                dao.markFailed(feedId, now + TimeUnit.HOURS.toMillis(hours))
            }
        }
    }

    /** feedId → time the stored icon was resolved (its version), for feeds that have an icon. */
    fun observeVersions(accountId: String): Flow<Map<String, Long>> =
        dao.observeVersions(accountId).map { rows -> rows.associate { it.feedId to it.resolvedAt } }

    suspend fun png(feedId: String): ByteArray? = dao.png(feedId)

    companion object {
        const val FOUND_RECHECK_DAYS = 30L
        const val NONE_RECHECK_DAYS = 14L
        const val FAILED_MAX_HOURS = 24L * 7
        private const val RECENT_LINKS = 20
    }
}
