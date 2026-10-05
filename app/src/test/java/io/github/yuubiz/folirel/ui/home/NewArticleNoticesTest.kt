package io.github.yuubiz.folirel.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * H03: the new-article notice is answered once. Acknowledging removes the whole answered set (not only what was on
 * screen), a request that finishes later and lists the same articles again does not bring them back, and articles that
 * really arrive afterwards are announced.
 */
class NewArticleNoticesTest {
    private fun ids(range: IntRange) = range.map { "a$it" }

    @Test
    fun announcedArticlesStayUntilAnswered() {
        val n = NewArticleNotices()
        n.announce(ids(0..4))
        n.announce(ids(3..7))
        assertEquals(ids(0..7), n.pending.value.toList())
    }

    @Test
    fun acknowledgingOneSetClearsItAtOnceAndLeavesOthers() {
        val n = NewArticleNotices()
        n.announce(ids(0..119))
        n.announce(listOf("other-category"))
        // The banner of a list that shows the 120 answers exactly those.
        n.acknowledge(ids(0..119))
        assertEquals(listOf("other-category"), n.pending.value.toList())
    }

    @Test
    fun aLateReportOfTheSameArticlesDoesNotBringThemBack() {
        val n = NewArticleNotices()
        n.announce(ids(0..119))
        n.acknowledge(ids(0..119))
        // A second request that shared the feed finishes afterwards with the same inserted ids, plus three truly new ones.
        n.announce(ids(0..119) + ids(120..122))
        assertEquals(ids(120..122), n.pending.value.toList())
    }

    @Test
    fun anArrivalAfterTheAnswerIsAnnouncedAsNew() {
        val n = NewArticleNotices()
        n.announce(ids(0..9))
        n.acknowledge(ids(0..9))
        n.announce(listOf("late"))
        assertEquals(listOf("late"), n.pending.value.toList())
    }

    @Test
    fun acknowledgingNothingOrUnknownIdsChangesNothing() {
        val n = NewArticleNotices()
        n.announce(ids(0..2))
        val before = n.pending.value
        n.acknowledge(emptyList())
        n.acknowledge(listOf("never-announced"))
        assertEquals(before, n.pending.value)
    }

    @Test
    fun pendingIsBoundedKeepingTheNewestAnnouncements() {
        val n = NewArticleNotices(maxPending = 10)
        n.announce(ids(0..14))
        assertEquals(ids(5..14), n.pending.value.toList())
    }

    @Test
    fun theMemoryOfAnsweredIdsIsBoundedAndForgetsTheOldest() {
        val n = NewArticleNotices(maxAcknowledged = 5)
        n.announce(ids(0..9))
        n.acknowledge(ids(0..9))
        // a5..a9 are still remembered; a0 was forgotten, so a report that lists it announces it again.
        n.announce(listOf("a9", "a0"))
        assertEquals(listOf("a0"), n.pending.value.toList())
    }
}
