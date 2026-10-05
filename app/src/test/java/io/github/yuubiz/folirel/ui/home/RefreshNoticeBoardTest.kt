package io.github.yuubiz.folirel.ui.home

import io.github.yuubiz.folirel.testutil.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** H02: the end of a notice belongs to its own request (UXR-08/09). Virtual time; the clock moves with it. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshNoticeBoardTest {
    private val clock = MutableClock(Instant.ofEpochMilli(0))
    private val scope = TestScope(StandardTestDispatcher())
    private val board = RefreshNoticeBoard(clock, scope, keepMillis = 5_000)

    private fun notice(id: Long, finishedAt: Long) = RefreshNotice(id, finishedAt, total = 3, failed = 1)

    private fun advance(ms: Long) {
        clock.now = clock.now.plusMillis(ms)
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test
    fun aNoticeShowsAndThenGoesAfterItsTime() {
        board.post(notice(1, finishedAt = 0))
        assertEquals(1L, board.current.value?.requestId)
        advance(4_999)
        assertEquals(1L, board.current.value?.requestId)
        advance(2)
        assertNull(board.current.value)
    }

    @Test
    fun theTimerOfAnOldNoticeNeverRemovesANewerOne() {
        board.post(notice(1, finishedAt = 0))
        advance(4_000)
        board.post(notice(2, finishedAt = 4_000))
        advance(1_100) // the first notice timer fires now
        assertEquals("the newer notice stays", 2L, board.current.value?.requestId)
        advance(4_000)
        assertNull(board.current.value)
    }

    @Test
    fun aNoticeThatFinishedLongAgoIsNotShownLate() {
        clock.now = Instant.ofEpochMilli(60_000)
        board.post(notice(1, finishedAt = 0))
        assertNull(board.current.value)
    }

    @Test
    fun closingAnotherRequestsNoticeChangesNothing() {
        board.post(notice(2, finishedAt = 0))
        board.dismiss(1)
        assertEquals(2L, board.current.value?.requestId)
        board.dismiss(2)
        assertNull(board.current.value)
    }

    @Test
    fun aClosedNoticeStaysClosedWhenItsTimerFires() {
        board.post(notice(1, finishedAt = 0))
        board.dismiss(1)
        advance(6_000)
        assertNull(board.current.value)
    }
}
