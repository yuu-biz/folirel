package io.github.yuubiz.feeddeck.backend

import io.github.yuubiz.feeddeck.model.FetchError

/**
 * Account/scope level refresh contract (docs/05 section 4). V1 has exactly one implementation (LOCAL).
 * The UI never talks to a backend directly; it goes through RefreshCoordinator and repositories.
 */
interface BackendSynchronizer {
    val backendType: String
    val capabilities: BackendCapabilities

    suspend fun refresh(
        accountId: String,
        scope: RefreshScope,
        reason: RefreshReason,
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
}

enum class RefreshReason {
    MANUAL, IMPORT, STARTUP, PERIODIC;

    /** Automatic refreshes obey the auto-update network constraint and failure backoff. */
    val isAutomatic: Boolean get() = this == STARTUP || this == PERIODIC
}

data class RefreshProgress(val completed: Int, val total: Int)

/** Outcome per category. "Complete" is never claimed while any feed was deferred or not attempted. */
data class RefreshReport(
    val total: Int,
    val updated: Int = 0,
    val notModified: Int = 0,
    val failed: Map<String, FetchError> = emptyMap(),
    /** Waiting for a backoff / Retry-After window. */
    val deferredBackoff: Int = 0,
    /** Not attempted because of connectivity/metered/HTTP-disabled constraints. */
    val deferredConstraint: Int = 0,
    /** Not attempted because of the time budget, cancellation, or OS interruption. */
    val notAttempted: Int = 0,
    val skippedInactive: Int = 0,
    /** http:// feeds not fetched because HTTP is disabled in settings (registered, waiting for opt-in). */
    val httpPending: Int = 0,
    val newArticles: Int = 0,
    val offline: Boolean = false,
) {
    val isComplete: Boolean get() = notAttempted == 0 && deferredConstraint == 0 && failed.isEmpty()

    operator fun plus(other: RefreshReport) = RefreshReport(
        total = total + other.total,
        updated = updated + other.updated,
        notModified = notModified + other.notModified,
        failed = failed + other.failed,
        deferredBackoff = deferredBackoff + other.deferredBackoff,
        deferredConstraint = deferredConstraint + other.deferredConstraint,
        notAttempted = notAttempted + other.notAttempted,
        skippedInactive = skippedInactive + other.skippedInactive,
        httpPending = httpPending + other.httpPending,
        newArticles = newArticles + other.newArticles,
        offline = offline || other.offline,
    )
}
