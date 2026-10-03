package io.github.yuubiz.feeddeck.backend

import io.github.yuubiz.feeddeck.model.FetchError

/**
 * Account/scope level refresh contract (docs/05 section 4). V1 has exactly one implementation (LOCAL).
 * The UI never talks to a backend directly; it goes through RefreshCoordinator and repositories.
 */
interface BackendSynchronizer {
    val backendType: String
    val capabilities: BackendCapabilities

    /**
     * Refreshes the feeds in [scope] for one request. The result describes this request's own target feeds: a feed
     * fetched for another overlapping request counts only if this request waited for that same fetch.
     * [force] revalidates without the stored HTTP validators (docs/FeedDeck_Next_Iteration_Mobile_v2.md SYNC-03).
     */
    suspend fun refresh(
        accountId: String,
        scope: RefreshScope,
        reason: RefreshReason,
        force: Boolean = false,
        onProgress: (RefreshProgress) -> Unit,
    ): RefreshReport
}

data class BackendCapabilities(
    /** Subscriptions are edited in the local DB. */
    val subscriptionEditing: Boolean,
    /** Reading state is synchronized with a remote service (false for LOCAL). */
    val remoteStateSync: Boolean,
)

sealed interface RefreshScope {
    data object All : RefreshScope
    data object Uncategorized : RefreshScope
    /** Includes descendant categories. */
    data class Category(val categoryId: String) : RefreshScope
    data class Feeds(val feedIds: List<String>) : RefreshScope
    /** Feeds whose last request did not finish (not started, cancelled, interrupted) or whose last attempt failed. */
    data object Pending : RefreshScope

    /** Stable text form for diagnostics. Feed ids are not included (count only). */
    fun describe(): String = when (this) {
        All -> "all"
        Uncategorized -> "uncategorized"
        is Category -> "category:$categoryId"
        is Feeds -> "feeds:${feedIds.size}"
        Pending -> "pending"
    }
}

enum class RefreshReason {
    MANUAL, IMPORT, STARTUP, PERIODIC;

    /** Automatic refreshes obey the auto-update network constraint and failure backoff. */
    val isAutomatic: Boolean get() = this == STARTUP || this == PERIODIC
}

data class RefreshProgress(val completed: Int, val total: Int)

/** What happened to one target feed within one request. Stored names are stable (feed diagnostics). */
enum class FeedResult {
    /** 200 applied (new and/or updated articles, possibly none). */
    UPDATED,
    /** 304 to a conditional request. */
    NOT_MODIFIED,
    FAILED,
    /** Waiting for failure backoff (automatic) or a valid Retry-After (any request). */
    BACKOFF,
    /** http:// feed while cleartext HTTP is disabled. */
    HTTP_DISABLED,
    /** No connectivity when the request started. */
    OFFLINE,
    /** Queued but never started before the request's time budget ended or it was cancelled. */
    NOT_STARTED,
    /** Started, then cancelled (budget or cancellation) before a result. Nothing was applied. */
    CANCELLED,
    /** This request stopped waiting, but the fetch continues for another request that also asked for it. */
    CONTINUING,
    /** Unsubscribed or paused while the fetch was running; nothing written. */
    INACTIVE,
    ;

    val unfinished: Boolean get() = this == NOT_STARTED || this == CANCELLED || this == CONTINUING

    companion object {
        fun fromStored(value: String?): FeedResult? = value?.let { v -> entries.firstOrNull { it.name == v } }
    }
}

data class FeedRefreshResult(
    val result: FeedResult,
    val error: FetchError? = null,
    val newArticles: Int = 0,
    val updatedArticles: Int = 0,
)

/** Outcome of one request. "Complete" is never claimed while any feed was deferred or not attempted. */
data class RefreshReport(
    val total: Int,
    val updated: Int = 0,
    val notModified: Int = 0,
    val failed: Map<String, FetchError> = emptyMap(),
    /** Waiting for a backoff / Retry-After window. */
    val deferredBackoff: Int = 0,
    /** Not attempted because of connectivity/metered constraints. */
    val deferredConstraint: Int = 0,
    /** Queued but not started when the time budget ended or the request was cancelled. */
    val notStarted: Int = 0,
    /** Started but cancelled (budget/cancellation/OS stop) before a result. */
    val cancelled: Int = 0,
    /** Still being fetched for another request when this one stopped waiting. */
    val continuing: Int = 0,
    val skippedInactive: Int = 0,
    /** http:// feeds not fetched because HTTP is disabled in settings (registered, waiting for opt-in). */
    val httpPending: Int = 0,
    val newArticles: Int = 0,
    val offline: Boolean = false,
    val requestId: String = "",
    val force: Boolean = false,
    /** Per target feed. */
    val perFeed: Map<String, FeedRefreshResult> = emptyMap(),
    /** Articles inserted for this request (for the new-articles banner; bounded by the feeds' own sizes). */
    val newArticleIds: List<String> = emptyList(),
) {
    /** Everything this request wanted but did not get a result for. */
    val notAttempted: Int get() = notStarted + cancelled + continuing

    val isComplete: Boolean get() = notAttempted == 0 && deferredConstraint == 0 && failed.isEmpty()
}
