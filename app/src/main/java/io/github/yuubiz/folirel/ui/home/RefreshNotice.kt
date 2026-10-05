package io.github.yuubiz.folirel.ui.home

import io.github.yuubiz.folirel.backend.FeedResult
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.sync.RefreshState
import io.github.yuubiz.folirel.sync.RequestStatus

/**
 * What a finished update asks the reader to look at, shown for a few seconds as a notice over the list (H02). It is
 * made once from the request that finished; it is not a view of the request's report, so removing it removes nothing:
 * the reasons and the way to retry stay in subscription management, and the per-feed errors stay in the database.
 *
 * Only updates the reader asked for (manual, import) make one; the automatic ones (startup, periodic) leave their
 * failures to the warning on the subscription entry, so a background failure is not announced again at every return.
 * A fully successful update makes none.
 */
internal data class RefreshNotice(
    val requestId: Long,
    val finishedAt: Long,
    val total: Int,
    val ok: Int = 0,
    val failed: Int = 0,
    val waiting: Int = 0,
    val notReached: Int = 0,
    val httpPending: Int = 0,
    val storage: Int = 0,
    /** The request ended without a result (the process was interrupted). */
    val interrupted: Boolean = false,
    /** Not updated because the device was offline. */
    val deferredOffline: Boolean = false,
) {
    /** Failed or not reached feeds can be asked for again; waiting ones are held back by the server or the failure backoff. */
    val canRetry: Boolean get() = failed > 0 || notReached > 0

    companion object {
        /** [state] is the coordinator's state after [request] finished: feeds the request left to a later one are judged by it (V31-C02). */
        fun of(request: RequestStatus, state: RefreshState): RefreshNotice? {
            if (request.running) return null
            if (request.reason != RefreshReason.MANUAL && request.reason != RefreshReason.IMPORT) return null
            val finishedAt = request.finishedAt ?: return null
            val report = request.report ?: return RefreshNotice(request.id, finishedAt, total = 0, interrupted = true)
            if (report.total <= 0) return null
            val results = report.perFeed.values
            // Feeds this request left to the request that followed an address change: their present state counts here, the request does not.
            val replacedNow = state.replacedNow(request)
            val failed = report.failed.size + replacedNow.failed
            val notReached = report.notAttempted + replacedNow.unreached
            val waiting = results.count { it.result == FeedResult.BACKOFF || it.result == FeedResult.OFFLINE }
            val storage = report.failed.values.count { it == FetchError.STORAGE }
            val deferredOffline = report.deferredConstraint > 0 && report.offline
            if (failed == 0 && notReached == 0 && waiting == 0 && report.httpPending == 0 && storage == 0 && !deferredOffline) return null
            return RefreshNotice(
                requestId = request.id,
                finishedAt = finishedAt,
                total = report.total,
                ok = results.count { it.result == FeedResult.UPDATED || it.result == FeedResult.NOT_MODIFIED },
                failed = failed,
                waiting = waiting,
                notReached = notReached,
                httpPending = report.httpPending,
                storage = storage,
                deferredOffline = deferredOffline,
            )
        }
    }
}
