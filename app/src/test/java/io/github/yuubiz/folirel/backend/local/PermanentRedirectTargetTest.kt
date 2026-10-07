package io.github.yuubiz.folirel.backend.local

import io.github.yuubiz.folirel.network.RedirectHop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which redirect target becomes the feed's fetch address (docs/fetch-and-security.md section 4, 301/308). */
class PermanentRedirectTargetTest {
    private fun target(start: String, vararg hops: Pair<Int, String>) = FeedFetcher.permanentTarget(start, hops.map { RedirectHop(it.first, it.second) })

    @Test
    fun theLeadingRunOfPermanentHopsIsAdopted() {
        assertEquals("https://b.example/f", target("https://a.example/f", 301 to "https://b.example/f"))
        assertEquals("https://c.example/f", target("https://a.example/f", 308 to "https://b.example/f", 301 to "https://c.example/f"))
        // A temporary hop ends the run; what follows it is not adopted.
        assertEquals("https://b.example/f", target("https://a.example/f", 301 to "https://b.example/f", 302 to "https://c.example/f", 301 to "https://d.example/f"))
        assertNull(target("https://a.example/f", 302 to "https://b.example/f"))
        assertNull(target("https://a.example/f"))
        // http:// moving to https:// is adopted (an upgrade).
        assertEquals("https://a.example/f", target("http://a.example/f", 301 to "https://a.example/f"))
    }

    @Test
    fun aPermanentStepFromHttpsDownToHttpIsNeverAdopted() {
        assertNull(target("https://a.example/f", 301 to "http://a.example/f"))
        // The run stops before the downgrade: the https:// part before it is still adopted, nothing after it.
        assertEquals("https://b.example/f", target("https://a.example/f", 301 to "https://b.example/f", 308 to "http://c.example/f", 301 to "https://d.example/f"))
        assertNull(target("HTTPS://a.example/f", 308 to "HTTP://a.example/f"))
        // A feed that is already on http:// may move to another http:// address (no downgrade happens there).
        assertEquals("http://b.example/f", target("http://a.example/f", 301 to "http://b.example/f"))
    }
}
