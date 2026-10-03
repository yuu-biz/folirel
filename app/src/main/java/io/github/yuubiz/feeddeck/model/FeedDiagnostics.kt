package io.github.yuubiz.feeddeck.model

import io.github.yuubiz.feeddeck.backend.FeedResult

/**
 * What is known about one feed's fetching (DATA-01): the last request that targeted it, the last real HTTP
 * attempt, the last body that was parsed and applied, and what the device stores. Unknown values are null.
 */
data class FeedDiagnostics(
    val feedId: String,
    // Last request
    val requestReason: String? = null,
    val requestScope: String? = null,
    val requestForce: Boolean = false,
    val requestedAt: Long? = null,
    val result: FeedResult? = null,
    val resultAt: Long? = null,
    val pendingSince: Long? = null,
    // Last HTTP attempt
    val attemptStartedAt: Long? = null,
    val attemptFinishedAt: Long? = null,
    val httpStatus: Int? = null,
    val validatorsSent: Boolean = false,
    val error: FetchError? = null,
    val errorDetail: String? = null,
    // Last applied body
    val bodyAt: Long? = null,
    val bodyBytes: Long? = null,
    val rawEntries: Int? = null,
    val uniqueEntries: Int? = null,
    val undatedEntries: Int? = null,
    val oldestPublishedAt: Long? = null,
    val newestPublishedAt: Long? = null,
    val insertedArticles: Int? = null,
    val updatedArticles: Int? = null,
    val skippedPruned: Int? = null,
)

/** Fetch state and stored counts for the feed detail screen. */
data class FeedFetchInfo(
    val fetchUrl: String?,
    val siteUrl: String?,
    val feedTitle: String?,
    val userTitle: String?,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val nextEligibleAt: Long?,
    val retryAfterUntil: Long?,
    val failureCount: Int,
    val storedArticles: Int,
    val unreadArticles: Int,
    val savedArticles: Int,
    /** Set when the fetch URL was moved from HTTP to HTTPS after verification. */
    val httpsFromUrl: String?,
)
