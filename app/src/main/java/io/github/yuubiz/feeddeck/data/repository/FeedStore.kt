package io.github.yuubiz.feeddeck.data.repository

import androidx.room.withTransaction
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.data.db.AppDatabase
import io.github.yuubiz.feeddeck.data.db.ArticleEntity
import io.github.yuubiz.feeddeck.data.db.ArticleStateEntity
import io.github.yuubiz.feeddeck.data.db.FeedEntity
import io.github.yuubiz.feeddeck.data.db.FeedFetchStateEntity
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.model.NormalizedFeed
import java.time.Clock

/** HTTP validators and redirect information of a successful 200 response. */
data class FetchMetadata(
    val etag: String?,
    val lastModified: String?,
    /** The URL that returned the validators (final URL after redirects). */
    val validatorUrl: String,
    /** New fetch URL after permanent (301/308) redirects; applied only because this fetch succeeded. */
    val permanentRedirectUrl: String?,
)

sealed interface ApplyResult {
    data class Applied(val newArticles: Int, val updatedArticles: Int) : ApplyResult
    /** Feed was unsubscribed/paused/removed while the fetch was running; nothing was written. */
    data object SkippedInactive : ApplyResult
}

/**
 * The only writer of fetched content. Receives normalized results from a backend and applies them per feed
 * in one transaction. Reading state is created on first insert only and never overwritten here.
 */
class FeedStore(
    private val db: AppDatabase,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    private val subscriptions = db.subscriptionDao()
    private val articles = db.articleDao()

    suspend fun feed(feedId: String): FeedEntity? = subscriptions.feed(feedId)

    suspend fun fetchState(feedId: String): FeedFetchStateEntity =
        subscriptions.fetchState(feedId) ?: FeedFetchStateEntity(feedId = feedId)

    /** Subscribed, not paused feeds in [scope] (category scopes include descendants). */
    suspend fun activeFeeds(accountId: String, scope: RefreshScope = RefreshScope.All): List<FeedEntity> {
        val active = subscriptions.feeds(accountId).filter { it.isSubscribed && !it.isPaused }
        return when (scope) {
            RefreshScope.All -> active
            is RefreshScope.Feeds -> active.filter { it.id in scope.feedIds.toSet() }
            RefreshScope.Uncategorized -> {
                val categorized = subscriptions.memberships(accountId).map { it.feedId }.toSet()
                active.filter { it.id !in categorized }
            }
            is RefreshScope.Category -> {
                val categories = subscriptions.categories(accountId).associate { it.id to CategoryPaths.decode(it.pathSegmentsJson) }
                val root = categories[scope.categoryId] ?: return emptyList()
                val ids = categories.filterValues { CategoryPaths.isSameOrDescendant(it, root) }.keys
                val feedIds = subscriptions.memberships(accountId).filter { it.categoryId in ids }.map { it.feedId }.toSet()
                active.filter { it.id in feedIds }
            }
        }
    }

    suspend fun applySuccess(feedId: String, feed: NormalizedFeed, meta: FetchMetadata): ApplyResult = db.withTransaction {
        val now = clock.millis()
        val current = subscriptions.feed(feedId)
        // Re-check right before applying: an unsubscribe/pause during the fetch wins.
        if (current == null || !current.isSubscribed || current.isPaused) return@withTransaction ApplyResult.SkippedInactive
        subscriptions.updateFeedMetadata(feedId, feed.title ?: current.feedTitle, feed.siteUrl ?: current.siteUrl)
        var inserted = 0
        var updated = 0
        for (entry in feed.entries) {
            val existingId = articles.findId(current.accountId, feedId, entry.entryKey)
            if (existingId != null) {
                articles.updateMetadata(
                    id = existingId,
                    externalEntryId = null,
                    linkUrl = entry.linkUrl,
                    title = entry.title,
                    summaryText = entry.summaryText,
                    author = entry.author,
                    publishedAt = entry.publishedAt,
                    imageUrl = entry.imageUrl,
                    lastSeenAt = now,
                )
                updated++
            } else {
                val id = ids.newId()
                val published = entry.publishedAt
                val sortAt = if (published != null && published <= now + FUTURE_TOLERANCE_MS) published else now
                articles.insertArticle(
                    ArticleEntity(
                        id = id, accountId = current.accountId, feedId = feedId, entryKey = entry.entryKey,
                        externalEntryId = null, linkUrl = entry.linkUrl, title = entry.title,
                        summaryText = entry.summaryText, author = entry.author, publishedAt = published,
                        firstSeenAt = now, sortAt = sortAt, imageUrl = entry.imageUrl, lastSeenAt = now,
                    ),
                )
                articles.insertState(ArticleStateEntity(id, isRead = false, isSaved = false, updatedAt = now))
                inserted++
            }
        }
        if (meta.permanentRedirectUrl != null && meta.permanentRedirectUrl != current.fetchUrl) {
            subscriptions.setFetchUrl(feedId, meta.permanentRedirectUrl)
        }
        subscriptions.insertFetchState(FeedFetchStateEntity(feedId = feedId))
        subscriptions.updateFetchState(
            FeedFetchStateEntity(
                feedId = feedId,
                etag = meta.etag,
                lastModified = meta.lastModified,
                validatorUrl = if (meta.etag != null || meta.lastModified != null) meta.validatorUrl else null,
                lastAttemptAt = now,
                lastSuccessAt = now,
                lastErrorCode = null,
                failureCount = 0,
                nextEligibleAt = null,
                initialized = true,
            ),
        )
        ApplyResult.Applied(inserted, updated)
    }

    /** 304 on an initialized feed: content unchanged, only the success time moves. */
    suspend fun recordNotModified(feedId: String) = db.withTransaction {
        val now = clock.millis()
        val state = fetchState(feedId)
        subscriptions.insertFetchState(FeedFetchStateEntity(feedId = feedId))
        subscriptions.updateFetchState(
            state.copy(lastAttemptAt = now, lastSuccessAt = now, lastErrorCode = null, failureCount = 0, nextEligibleAt = null),
        )
    }

    /** Failure keeps all previously fetched articles. Validators are kept; backoff is scheduled. */
    suspend fun recordFailure(feedId: String, error: FetchError, retryAfterMillis: Long?) = db.withTransaction {
        val now = clock.millis()
        val state = fetchState(feedId)
        val failures = state.failureCount + 1
        subscriptions.insertFetchState(FeedFetchStateEntity(feedId = feedId))
        subscriptions.updateFetchState(
            state.copy(
                lastAttemptAt = now,
                lastErrorCode = error.name,
                failureCount = failures,
                nextEligibleAt = now + RetryPolicy.delayMillis(error, failures, retryAfterMillis),
            ),
        )
    }

    companion object {
        const val FUTURE_TOLERANCE_MS = 24L * 60 * 60 * 1000
    }
}

object RetryPolicy {
    const val MIN_DELAY_MS = 15L * 60 * 1000
    const val MAX_DELAY_MS = 24L * 60 * 60 * 1000
    private const val MIN_RETRY_AFTER_MS = 60L * 1000

    /**
     * 429: a valid Retry-After (already converted to a delay) clamped to [1 min, 24 h]; missing/invalid/past → 15 min.
     * Others: 15 min × 2^(failures-1), capped at 24 h.
     */
    fun delayMillis(error: FetchError, failureCount: Int, retryAfterMillis: Long?): Long {
        if (error == FetchError.RATE_LIMITED) {
            return if (retryAfterMillis == null || retryAfterMillis <= 0) MIN_DELAY_MS
            else retryAfterMillis.coerceIn(MIN_RETRY_AFTER_MS, MAX_DELAY_MS)
        }
        val exponent = (failureCount - 1).coerceIn(0, 10)
        return (MIN_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
    }
}
