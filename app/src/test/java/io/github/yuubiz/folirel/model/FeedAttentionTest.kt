package io.github.yuubiz.folirel.model

import io.github.yuubiz.folirel.backend.FeedResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** H02: the small warning on the subscriptions entry counts what the stored state says, so it outlives the notice of an update. */
class FeedAttentionTest {
    private fun feed(id: String, error: FetchError? = null, paused: Boolean = false, subscribed: Boolean = true, initialized: Boolean = true, url: String = "https://example.com/$id") =
        FeedNode(id, id, url, url, subscribed, paused, error, initialized, emptySet())

    private fun structure(vararg feeds: FeedNode) = Structure(feeds = feeds.associateBy { it.id }, loaded = true)

    private fun count(s: Structure, d: Map<String, FeedDiagnostics> = emptyMap(), unfinished: Boolean = true) = FeedListFilter.needingAttention(s, d, unfinished)

    @Test
    fun healthyFeedsAreNotCounted() {
        assertEquals(0, count(structure(feed("a"), feed("b"))))
    }

    @Test
    fun aFailedFeedIsCountedUntilItIsFetchedAgain() {
        assertEquals(1, count(structure(feed("a", FetchError.SERVER), feed("b"))))
        assertEquals("fetched again: the stored error is gone", 0, count(structure(feed("a"), feed("b"))))
    }

    @Test
    fun aFeedHeldForHttpIsCounted() {
        val held = feed("h", initialized = false, url = "http://example.com/h")
        assertEquals(1, count(structure(held)))
        val diagnosticsHeld = mapOf("g" to FeedDiagnostics("g", result = FeedResult.HTTP_DISABLED))
        assertEquals(1, count(structure(feed("g")), diagnosticsHeld))
    }

    @Test
    fun aFeedLeftUnfinishedIsCountedOnceNoUpdateIsRunning() {
        val unfinished = mapOf("u" to FeedDiagnostics("u", pendingSince = 5L, result = FeedResult.CANCELLED))
        assertEquals("not reached and nothing running: retryable from the subscriptions entry", 1, count(structure(feed("u")), unfinished, unfinished = true))
        assertEquals("while an update runs its queued feeds are work in progress", 0, count(structure(feed("u")), unfinished, unfinished = false))
    }

    @Test
    fun pausedAndUnsubscribedFeedsAreNeverCounted() {
        val unfinished = mapOf("p" to FeedDiagnostics("p", pendingSince = 5L))
        assertEquals(0, count(structure(feed("p", FetchError.SERVER, paused = true), feed("x", FetchError.SERVER, subscribed = false)), unfinished))
    }

    @Test
    fun aFeedIsCountedOnceWhateverNumberOfReasonsItHas() {
        val d = mapOf("a" to FeedDiagnostics("a", pendingSince = 5L, result = FeedResult.HTTP_DISABLED))
        assertEquals(1, count(structure(feed("a", FetchError.HTTP_BLOCKED)), d))
    }
}
