package io.github.yuubiz.feeddeck.data.repository

import androidx.room.withTransaction
import io.github.yuubiz.feeddeck.backend.FeedResult
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.data.db.AppDatabase
import io.github.yuubiz.feeddeck.data.db.ArticleEntity
import io.github.yuubiz.feeddeck.data.db.ArticleStateEntity
import io.github.yuubiz.feeddeck.data.db.FeedDiagnosticsEntity
import io.github.yuubiz.feeddeck.data.db.FeedEntity
import io.github.yuubiz.feeddeck.data.db.FeedFetchStateEntity
import io.github.yuubiz.feeddeck.data.db.FeedIconEntity
import io.github.yuubiz.feeddeck.data.db.IconStatus
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

/** What one response body contained before parsing/merging, recorded with the applied result (DATA-01). */
data class BodyStats(val bytes: Long, val rawEntries: Int)

/** Diagnostics of one HTTP attempt. */
data class AttemptInfo(val httpStatus: Int?, val validatorsSent: Boolean, val errorDetail: String? = null)

sealed interface ApplyResult {
    data class Applied(
        val newArticles: Int,
        val updatedArticles: Int,
        val insertedIds: List<String> = emptyList(),
        /** Entries not inserted again because the retention cleanup removed them earlier (as read). */
        val skippedPruned: Int = 0,
    ) : ApplyResult

    /** Feed was unsubscribed/paused/removed while the fetch was running; nothing was written. */
    data object SkippedInactive : ApplyResult

    /** The fetch URL changed (approved move, HTTPS migration) while the answer was on its way; nothing was written. */
    data object Superseded : ApplyResult
}

/**
 * The only writer of fetched content. Receives normalized results from a backend and applies them per feed
 * in one transaction. Reading state is created on first insert only and never overwritten here.
 * Also keeps the per-feed diagnostics (requested / attempted / applied) that explain each request's result.
 */
class FeedStore(
    private val db: AppDatabase,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    private val subscriptions = db.subscriptionDao()
    private val articles = db.articleDao()
    private val diagnostics = db.diagnosticsDao()
    private val icons = db.iconDao()

    suspend fun feed(feedId: String): FeedEntity? = subscriptions.feed(feedId)

    suspend fun fetchState(feedId: String): FeedFetchStateEntity =
        subscriptions.fetchState(feedId) ?: FeedFetchStateEntity(feedId = feedId)

    /** Subscribed, not paused feeds in [scope] (category scopes include descendants; each feed once). */
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
            RefreshScope.Pending -> {
                val pending = diagnostics.pendingFeedIds(accountId).toSet()
                val failed = subscriptions.fetchStates(accountId).filter { it.lastErrorCode != null }.map { it.feedId }.toSet()
                active.filter { it.id in pending || it.id in failed }
            }
        }
    }

    /**
     * Applies every entry of one parsed response (no count or date cut-off). Existing entries are updated,
     * new ones inserted as unread; reading state of existing articles is never touched.
     *
     * [fetchedFrom]: the fetch URL this request was started with. An answer is applied only while the feed is still
     * fetched from that URL; after a move to another address it belongs to the old address and is dropped as a whole
     * (a redirect target the old address announces included). A normal redirect inside the request does not matter:
     * only the start URL is compared. null = content not tied to a request (fixtures, import of known content).
     */
    suspend fun applySuccess(
        feedId: String,
        feed: NormalizedFeed,
        meta: FetchMetadata,
        body: BodyStats = BodyStats(0, feed.rawEntryCount),
        attempt: AttemptInfo = AttemptInfo(200, false),
        fetchedFrom: String? = null,
    ): ApplyResult = db.withTransaction {
        val now = clock.millis()
        val current = subscriptions.feed(feedId)
        // Re-check right before applying: an unsubscribe/pause during the fetch wins.
        if (current == null || !current.isSubscribed || current.isPaused) return@withTransaction ApplyResult.SkippedInactive
        if (fetchedFrom != null && current.fetchUrl != fetchedFrom) return@withTransaction ApplyResult.Superseded
        subscriptions.updateFeedMetadata(feedId, feed.title ?: current.feedTitle, feed.siteUrl ?: current.siteUrl)
        // Only the icon URL the feed declares is recorded here; looking it up is the icon coordinator's job.
        icons.insertIgnore(FeedIconEntity(feedId = feedId, declaredUrl = feed.iconUrl, status = IconStatus.PENDING.name))
        icons.changeDeclared(feedId, feed.iconUrl)
        // A feed that now names its site, or has its first articles, may reveal an icon an earlier lookup could not find.
        if ((current.siteUrl == null && feed.siteUrl != null) || !subscriptions.fetchState(feedId)?.initialized.orFalse()) icons.retryIfNoIcon(feedId)
        var updated = 0
        var skippedPruned = 0
        val insertedIds = ArrayList<String>()
        for (entry in feed.entries) {
            // Same article whose URL-like id/link only changed between http:// and https:// (e.g. after the feed
            // moved to HTTPS) is the same article, not a new unread one.
            val existingId = articles.findId(current.accountId, feedId, entry.entryKey)
                ?: schemeVariant(entry.entryKey)?.let { articles.findId(current.accountId, feedId, it) }
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
            } else if (touchPruned(feedId, entry.entryKey, now)) {
                // Removed earlier by the retention cleanup (read, not saved): not resurrected as a new unread article.
                skippedPruned++
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
                insertedIds += id
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
                retryAfterUntil = null,
            ),
        )
        val dates = feed.entries.mapNotNull { it.publishedAt }
        ensureDiagnostics(listOf(feedId))
        diagnostics.attemptFinished(feedId, now, attempt.httpStatus, attempt.validatorsSent, null, null, FeedResult.UPDATED.name)
        diagnostics.bodyApplied(
            feedId = feedId, now = now, bytes = body.bytes, raw = body.rawEntries, unique = feed.entries.size,
            undated = feed.entries.size - dates.size, oldest = dates.minOrNull(), newest = dates.maxOrNull(),
            inserted = insertedIds.size, updated = updated, skippedPruned = skippedPruned,
        )
        ApplyResult.Applied(insertedIds.size, updated, insertedIds, skippedPruned)
    }

    /**
     * 304 to a conditional request: content unchanged, only the success time moves. The last body statistics
     * (entry counts) are kept as they were; a 304 never turns them into zero.
     */
    suspend fun recordNotModified(feedId: String, fetchedFrom: String? = null): Boolean = db.withTransaction {
        if (isSuperseded(feedId, fetchedFrom)) return@withTransaction false
        val now = clock.millis()
        val state = fetchState(feedId)
        subscriptions.insertFetchState(FeedFetchStateEntity(feedId = feedId))
        subscriptions.updateFetchState(
            state.copy(lastAttemptAt = now, lastSuccessAt = now, lastErrorCode = null, failureCount = 0, nextEligibleAt = null, retryAfterUntil = null),
        )
        ensureDiagnostics(listOf(feedId))
        diagnostics.attemptFinished(feedId, now, 304, true, null, null, FeedResult.NOT_MODIFIED.name)
        true
    }

    /** True when the feed is no longer fetched from [fetchedFrom], the URL the finished request started with. */
    private suspend fun isSuperseded(feedId: String, fetchedFrom: String?): Boolean =
        fetchedFrom != null && subscriptions.feed(feedId)?.fetchUrl.let { it != null && it != fetchedFrom }

    /**
     * Failure keeps all previously fetched articles. Validators are kept; backoff is scheduled. A valid server
     * Retry-After (429/5xx) also sets a hard wait that manual and forced requests respect.
     */
    suspend fun recordFailure(
        feedId: String,
        error: FetchError,
        retryAfterMillis: Long?,
        attempt: AttemptInfo = AttemptInfo(null, false),
        fetchedFrom: String? = null,
    ): Boolean = db.withTransaction {
        if (isSuperseded(feedId, fetchedFrom)) return@withTransaction false
        val now = clock.millis()
        val state = fetchState(feedId)
        val failures = state.failureCount + 1
        val delay = RetryPolicy.delayMillis(error, failures, retryAfterMillis)
        val serverWait = retryAfterMillis != null && retryAfterMillis > 0 && (error == FetchError.RATE_LIMITED || error == FetchError.SERVER)
        subscriptions.insertFetchState(FeedFetchStateEntity(feedId = feedId))
        subscriptions.updateFetchState(
            state.copy(
                lastAttemptAt = now,
                lastErrorCode = error.name,
                failureCount = failures,
                nextEligibleAt = now + delay,
                retryAfterUntil = if (serverWait) now + delay else null,
            ),
        )
        ensureDiagnostics(listOf(feedId))
        diagnostics.attemptFinished(feedId, now, attempt.httpStatus, attempt.validatorsSent, error.name, attempt.errorDetail, FeedResult.FAILED.name)
        true
    }

    /** The fetch finished but the feed was unsubscribed/paused meanwhile: nothing applied, no longer pending. */
    suspend fun recordInactive(feedId: String) {
        ensureDiagnostics(listOf(feedId))
        diagnostics.attemptFinished(feedId, clock.millis(), null, false, null, null, FeedResult.INACTIVE.name)
    }

    /** The pruned record of [entryKey], or of its http/https twin (same rule as for stored articles), seen again now. */
    private suspend fun touchPruned(feedId: String, entryKey: String, now: Long): Boolean =
        diagnostics.touchPruned(feedId, entryKey, now) > 0 ||
            (schemeVariant(entryKey)?.let { diagnostics.touchPruned(feedId, it, now) > 0 } ?: false)

    // Request bookkeeping: diagnostics only. lastAttemptAt / lastSuccessAt are never moved by these.

    private suspend fun ensureDiagnostics(feedIds: List<String>) {
        diagnostics.insertDiagnostics(feedIds.map { FeedDiagnosticsEntity(feedId = it) })
    }

    suspend fun markRequested(feedIds: List<String>, requestId: String, reason: String, scope: String, force: Boolean) {
        if (feedIds.isEmpty()) return
        val now = clock.millis()
        db.withTransaction {
            feedIds.chunked(CHUNK).forEach { chunk ->
                ensureDiagnostics(chunk)
                diagnostics.markRequested(chunk, requestId, reason, scope, force, now)
            }
        }
    }

    suspend fun markSkipped(feedIds: List<String>, result: FeedResult, requestId: String, reason: String, scope: String, force: Boolean) {
        if (feedIds.isEmpty()) return
        val now = clock.millis()
        db.withTransaction {
            feedIds.chunked(CHUNK).forEach { chunk ->
                ensureDiagnostics(chunk)
                diagnostics.markSkipped(chunk, result.name, requestId, reason, scope, force, now)
            }
        }
    }

    suspend fun markUnfinished(feedIds: List<String>, result: FeedResult) {
        if (feedIds.isEmpty()) return
        val now = clock.millis()
        db.withTransaction { feedIds.chunked(CHUNK).forEach { diagnostics.markUnfinished(it, result.name, now) } }
    }

    suspend fun attemptStarted(feedId: String) {
        ensureDiagnostics(listOf(feedId))
        diagnostics.attemptStarted(feedId, clock.millis())
    }

    companion object {
        const val FUTURE_TOLERANCE_MS = 24L * 60 * 60 * 1000

        /** `url:`/`id:` key with http:// and https:// swapped, or null for other keys. */
        fun schemeVariant(key: String): String? = when {
            key.startsWith("url:https://") || key.startsWith("id:https://") -> key.replaceFirst("https://", "http://")
            key.startsWith("url:http://") || key.startsWith("id:http://") -> key.replaceFirst("http://", "https://")
            else -> null
        }
        private const val CHUNK = 500
    }
}

private fun Boolean?.orFalse() = this ?: false

object RetryPolicy {
    const val MIN_DELAY_MS = 15L * 60 * 1000
    const val MAX_DELAY_MS = 24L * 60 * 60 * 1000
    /** Upper bound of a server-specified wait (Retry-After); longer values count as this long. */
    const val MAX_SERVER_WAIT_MS = 30L * 24 * 60 * 60 * 1000
    private const val MIN_RETRY_AFTER_MS = 60L * 1000

    /**
     * 429: a valid Retry-After (already converted to a delay) is kept, within [1 min, 30 days]; missing/invalid/past → 15 min.
     * 5xx with a valid Retry-After: the same. The server's time is never shortened to the app's own 24 h backoff cap.
     * Others: 15 min × 2^(failures-1), capped at 24 h.
     */
    fun delayMillis(error: FetchError, failureCount: Int, retryAfterMillis: Long?): Long {
        if (error == FetchError.RATE_LIMITED) {
            return if (retryAfterMillis == null || retryAfterMillis <= 0) MIN_DELAY_MS
            else retryAfterMillis.coerceIn(MIN_RETRY_AFTER_MS, MAX_SERVER_WAIT_MS)
        }
        if (error == FetchError.SERVER && retryAfterMillis != null && retryAfterMillis > 0) {
            return retryAfterMillis.coerceIn(MIN_RETRY_AFTER_MS, MAX_SERVER_WAIT_MS)
        }
        val exponent = (failureCount - 1).coerceIn(0, 10)
        return (MIN_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
    }
}
