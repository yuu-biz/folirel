package io.github.yuubiz.folirel.model

import io.github.yuubiz.folirel.backend.FeedResult

/**
 * Why a feed's last fetch failed, grouped for people (SUB-BULK). A missing page (404/410) is shown as "not found"
 * and never as "the site is closed": only the user can decide that, so nothing is removed automatically.
 */
enum class FeedProblem {
    /** 404 / 410: the feed address no longer answers with a feed. The site may have moved or closed. */
    MISSING,

    /** 401 / 403. */
    ACCESS,

    /** 429. */
    BUSY,

    /** 5xx. */
    SERVER,

    /** Certificate / hostname verification failed. */
    TLS,

    /** No connection, DNS failure or timeout. Often temporary. */
    NETWORK,

    /** The answer is not a readable RSS/Atom feed (HTML page, broken XML, unsupported format, too large). */
    UNREADABLE,

    /** Unsafe address, redirect loop or too many redirects. */
    BLOCKED,

    /** Anything else (unexpected status, storage). */
    OTHER,
    ;

    /** Failures that tend to pass by themselves; they are never a reason to unsubscribe on their own. */
    val likelyTemporary: Boolean get() = this == NETWORK || this == BUSY || this == SERVER

    companion object {
        fun of(error: FetchError): FeedProblem = when (error) {
            FetchError.NOT_FOUND, FetchError.GONE -> MISSING
            FetchError.ACCESS_DENIED -> ACCESS
            FetchError.RATE_LIMITED -> BUSY
            FetchError.SERVER -> SERVER
            FetchError.TLS -> TLS
            FetchError.NETWORK, FetchError.TIMEOUT -> NETWORK
            FetchError.FORMAT, FetchError.NOT_A_FEED, FetchError.UNSUPPORTED_FORMAT, FetchError.LIMIT_EXCEEDED,
            FetchError.UNEXPECTED_NOT_MODIFIED,
            -> UNREADABLE
            FetchError.SECURITY, FetchError.REDIRECT_LOOP, FetchError.TOO_MANY_REDIRECTS -> BLOCKED
            FetchError.HTTP_BLOCKED, FetchError.HTTP_STATUS, FetchError.STORAGE -> OTHER
        }
    }
}

/** Filters of the subscription management list. They only narrow what is shown; they never change anything. */
enum class FeedListFilter {
    ALL,

    /** The last attempt failed. */
    FAILED,

    /** Never fetched successfully and not failed yet, and not waiting for the HTTP setting. */
    NEVER_FETCHED,

    /** An http:// feed that is not fetched because cleartext HTTP is not allowed. */
    HTTP_PENDING,

    /** Waiting for a retry time (failure backoff or the server's Retry-After). */
    WAITING,

    /** Updates stopped by the user. */
    PAUSED,
    ;

    fun matches(feed: FeedNode, diagnostics: FeedDiagnostics?, now: Long): Boolean {
        if (!feed.subscribed) return false
        val httpPending = isHttpPending(feed, diagnostics)
        return when (this) {
            ALL -> true
            FAILED -> !feed.paused && feed.lastError != null
            NEVER_FETCHED -> !feed.paused && !feed.initialized && feed.lastError == null && !httpPending
            HTTP_PENDING -> !feed.paused && httpPending
            WAITING -> !feed.paused && (feed.isWaiting(now) || diagnostics?.result == FeedResult.BACKOFF)
            PAUSED -> feed.paused
        }
    }

    companion object {
        fun isHttpPending(feed: FeedNode, diagnostics: FeedDiagnostics?): Boolean =
            diagnostics?.result == FeedResult.HTTP_DISABLED || (!feed.initialized && feed.fetchUrl.startsWith("http://", ignoreCase = true))

        /**
         * How many feeds the home screen marks on the subscriptions entry: the ones the "Failed", "HTTP pending" and unfinished
         * (queued, never completed) lists show. It follows the stored state, so it outlives any notice and ends when a feed is
         * fetched again. [countUnfinished] is false while an update runs: every feed it has queued is then unfinished, which is
         * work in progress, not something to look at.
         */
        fun needingAttention(structure: Structure, diagnostics: Map<String, FeedDiagnostics>, countUnfinished: Boolean): Int =
            structure.subscribedFeeds.count { f ->
                !f.paused && (f.lastError != null || isHttpPending(f, diagnostics[f.id]) || (countUnfinished && diagnostics[f.id]?.pendingSince != null))
            }
    }
}
