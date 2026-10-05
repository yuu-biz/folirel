package io.github.yuubiz.folirel.ui.home

import io.github.yuubiz.folirel.backend.FeedRefreshResult
import io.github.yuubiz.folirel.backend.FeedResult
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshReport
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.sync.RefreshState
import io.github.yuubiz.folirel.sync.RequestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** H02: which finished updates leave a notice, and what it counts. Everything about it comes from the finished request. */
class RefreshNoticeTest {
    private fun request(
        id: Long = 1,
        reason: RefreshReason = RefreshReason.MANUAL,
        report: RefreshReport? = RefreshReport(total = 0),
        finishedAt: Long? = 1_000,
        scope: RefreshScope = RefreshScope.All,
    ) = RequestStatus(id, scope, reason, force = false, startedAt = 0, report = report, finishedAt = finishedAt)

    private fun results(vararg pairs: Pair<String, FeedResult>) = pairs.associate { (id, r) -> id to FeedRefreshResult(r) }

    private fun noticeOf(r: RequestStatus, vararg later: RequestStatus) = RefreshNotice.of(r, RefreshState(listOf(r) + later))

    @Test
    fun aFullySuccessfulUpdateLeavesNothing() {
        val r = request(report = RefreshReport(total = 3, updated = 2, notModified = 1, perFeed = results("a" to FeedResult.UPDATED, "b" to FeedResult.UPDATED, "c" to FeedResult.NOT_MODIFIED)))
        assertNull(noticeOf(r))
    }

    @Test
    fun aPartialFailureCountsWhatWorkedAndWhatFailedAndCanBeRetried() {
        val r = request(report = RefreshReport(total = 3, updated = 2, failed = mapOf("c" to FetchError.SERVER), perFeed = results("a" to FeedResult.UPDATED, "b" to FeedResult.UPDATED, "c" to FeedResult.FAILED)))
        val n = noticeOf(r)!!
        assertEquals(3, n.total)
        assertEquals(2, n.ok)
        assertEquals(1, n.failed)
        assertTrue(n.canRetry)
        assertEquals(1L, n.requestId)
    }

    @Test
    fun feedsLeftUnreachedCanBeRetried() {
        val r = request(report = RefreshReport(total = 2, notStarted = 1, updated = 1, perFeed = results("a" to FeedResult.UPDATED, "b" to FeedResult.NOT_STARTED)))
        val n = noticeOf(r)!!
        assertEquals(1, n.notReached)
        assertTrue(n.canRetry)
    }

    @Test
    fun waitingFeedsAreReportedButNotRetriedByTheReader() {
        val r = request(report = RefreshReport(total = 2, updated = 1, perFeed = results("a" to FeedResult.UPDATED, "b" to FeedResult.BACKOFF)))
        val n = noticeOf(r)!!
        assertEquals(1, n.waiting)
        assertFalse("held back by the server or the backoff: nothing to retry now", n.canRetry)
    }

    @Test
    fun httpPendingIsReported() {
        val n = noticeOf(request(report = RefreshReport(total = 2, updated = 1, httpPending = 1, perFeed = results("a" to FeedResult.UPDATED, "b" to FeedResult.HTTP_DISABLED))))!!
        assertEquals(1, n.httpPending)
    }

    @Test
    fun aStorageFailureIsKeptApart() {
        val r = request(report = RefreshReport(total = 1, failed = mapOf("a" to FetchError.STORAGE), perFeed = results("a" to FeedResult.FAILED)))
        val n = noticeOf(r)!!
        assertEquals(1, n.storage)
        assertEquals(1, n.failed)
    }

    @Test
    fun anUpdateThatWasInterruptedSaysSo() {
        val n = noticeOf(request(report = null))!!
        assertTrue(n.interrupted)
    }

    @Test
    fun offlineDeferralIsReported() {
        val n = noticeOf(request(report = RefreshReport(total = 2, deferredConstraint = 2, offline = true)))!!
        assertTrue(n.deferredOffline)
    }

    @Test
    fun automaticUpdatesLeaveTheirFailuresToTheWarningOnTheSubscriptionEntry() {
        val failing = RefreshReport(total = 2, failed = mapOf("b" to FetchError.SERVER), perFeed = results("a" to FeedResult.UPDATED, "b" to FeedResult.FAILED))
        assertNull(noticeOf(request(reason = RefreshReason.STARTUP, report = failing)))
        assertNull(noticeOf(request(reason = RefreshReason.PERIODIC, report = failing)))
        assertNotNull(noticeOf(request(reason = RefreshReason.IMPORT, report = failing)))
    }

    @Test
    fun aRunningRequestHasNoNoticeYet() {
        assertNull(RefreshNotice.of(request(finishedAt = null), RefreshState()))
    }

    // --- V31-C02: feeds this request left to the request that followed an address change are judged by the later request ---

    private fun replaced() = request(
        id = 1,
        report = RefreshReport(total = 2, updated = 1, replaced = 1, perFeed = results("a" to FeedResult.UPDATED, "moved" to FeedResult.REPLACED)),
    )

    private fun later(result: FeedResult, error: FetchError? = null) = RequestStatus(
        id = 2, scope = RefreshScope.Feeds(listOf("moved")), reason = RefreshReason.MANUAL, force = false, startedAt = 10,
        report = RefreshReport(
            total = 1,
            failed = if (error != null) mapOf("moved" to error) else emptyMap(),
            perFeed = mapOf("moved" to FeedRefreshResult(result, error)),
        ),
        finishedAt = 20,
    )

    @Test
    fun aReplacedFeedThatFailsAtItsNewAddressIsACurrentProblem() {
        val n = noticeOf(replaced(), later(FeedResult.FAILED, FetchError.SERVER))!!
        assertEquals(1, n.failed)
    }

    @Test
    fun aReplacedFeedThatWorksAtItsNewAddressLeavesNothing() {
        assertNull(noticeOf(replaced(), later(FeedResult.UPDATED)))
    }
}
