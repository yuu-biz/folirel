package io.github.yuubiz.folirel.network

import io.github.yuubiz.folirel.network.SharedUrlExtractor.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3B-T01..T08: web addresses out of shared text. */
class SharedUrlExtractorTest {
    private fun urls(text: String?): List<String> = (SharedUrlExtractor.extract(text) as? Result.Found)?.urls.orEmpty()

    @Test
    fun urlOnly() {
        assertEquals(listOf("https://example.com/feed.xml"), urls("https://example.com/feed.xml"))
    }

    @Test
    fun textBeforeAndAfterTheAddress() {
        assertEquals(listOf("https://example.com/posts/123"), urls("面白い記事\nhttps://example.com/posts/123"))
        assertEquals(listOf("https://example.com/posts/123"), urls("Look at this https://example.com/posts/123 it is great"))
    }

    @Test
    fun addressInsideJapaneseText() {
        assertEquals(listOf("https://example.com/a"), urls("この記事がおすすめです。https://example.com/a。ぜひ読んでください"))
        assertEquals(listOf("https://example.com/a"), urls("「https://example.com/a」を見て"))
        assertEquals(listOf("https://example.com/a"), urls("（https://example.com/a）"))
        assertEquals(listOf("https://example.com/a"), urls("【速報】https://example.com/a　続きは本文で"))
        // Japanese text that touches the address without a space does not hide it.
        assertEquals(listOf("https://example.com/a"), urls("記事https://example.com/a"))
    }

    @Test
    fun severalAddressesAreAllReturnedInOrderAndNeverReducedToTheFirst() {
        val found = urls("https://a.example/ と https://b.example/feed と http://c.example/x")
        assertEquals(listOf("https://a.example/", "https://b.example/feed", "http://c.example/x"), found)
    }

    @Test
    fun duplicatesAreRemovedAfterNormalizing() {
        assertEquals(
            listOf("https://example.com/a"),
            urls("https://example.com/a https://EXAMPLE.com/a https://example.com:443/a#top"),
        )
    }

    @Test
    fun trailingPunctuationIsNotPartOfTheAddress() {
        assertEquals(listOf("https://example.com/a"), urls("See https://example.com/a."))
        assertEquals(listOf("https://example.com/a"), urls("Is it https://example.com/a?"))
        assertEquals(listOf("https://example.com/a"), urls("(https://example.com/a)"))
        assertEquals(listOf("https://example.com/a"), urls("<https://example.com/a>, and more"))
        assertEquals(listOf("https://example.com/a"), urls("\"https://example.com/a\"; next"))
        assertEquals(listOf("https://example.com/a?x=1"), urls("https://example.com/a?x=1!!!"))
    }

    @Test
    fun aBracketThatClosesOneOpenedInsideTheAddressIsKept() {
        assertEquals(listOf("https://en.wikipedia.org/wiki/Kotlin_(programming_language)"), urls("https://en.wikipedia.org/wiki/Kotlin_(programming_language)"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Kotlin_(programming_language)"), urls("(https://en.wikipedia.org/wiki/Kotlin_(programming_language))."))
    }

    @Test
    fun onlyHttpAndHttpsAreCandidates() {
        val none = listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://com.example/x",
            "data:text/html,<b>x</b>",
            "ftp://example.com/feed",
            "intent://scan/#Intent;scheme=zxing;end",
            "mailto:a@example.com",
            "view-source:https://example.com/",
            "xhttps://example.com/",
            "not a url at all",
        )
        none.forEach { assertEquals(it, Result.None, SharedUrlExtractor.extract(it)) }
        // An address next to a refused scheme is still found on its own.
        assertEquals(listOf("https://ok.example/"), urls("javascript:void(0) https://ok.example/ ftp://x.example/"))
    }

    @Test
    fun addressesThatFailTheUrlRulesAreDropped() {
        assertEquals(emptyList<String>(), urls("https://user:pass@example.com/"))
        assertEquals(emptyList<String>(), urls("http:// https://"))
        assertEquals(listOf("https://ok.example/"), urls("https://user:pass@example.com/ https://ok.example/"))
    }

    @Test
    fun missingAndBlankTextIsSafe() {
        assertEquals(Result.None, SharedUrlExtractor.extract(null))
        assertEquals(Result.None, SharedUrlExtractor.extract(""))
        assertEquals(Result.None, SharedUrlExtractor.extract("   \n\t"))
    }

    @Test
    fun theNumberOfAddressesIsLimited() {
        val text = (1..20).joinToString(" ") { "https://site$it.example/" }
        assertEquals(SharedUrlExtractor.MAX_URLS, urls(text).size)
        assertEquals("https://site1.example/", urls(text).first())
    }

    @Test
    fun oversizedTextIsRefusedWithoutReadingIt() {
        val big = "https://example.com/a " + "x".repeat(SharedUrlExtractor.MAX_TEXT_CHARS)
        assertEquals(Result.TooLarge, SharedUrlExtractor.extract(big))
        // Exactly at the limit is still read.
        val atLimit = "https://example.com/a " + "x".repeat(SharedUrlExtractor.MAX_TEXT_CHARS - "https://example.com/a ".length)
        assertEquals(SharedUrlExtractor.MAX_TEXT_CHARS, atLimit.length)
        assertTrue(SharedUrlExtractor.extract(atLimit) is Result.Found)
    }

    @Test
    fun anOverlongSingleAddressAndPathologicalInputsAreHandledQuickly() {
        val started = System.nanoTime()
        assertEquals(Result.None, SharedUrlExtractor.extract("https://example.com/" + ")".repeat(60_000)))
        assertEquals(Result.None, SharedUrlExtractor.extract("http://".repeat(8_000)))
        assertEquals(Result.None, SharedUrlExtractor.extract("://".repeat(20_000)))
        assertTrue("took too long", (System.nanoTime() - started) / 1_000_000 < 3_000)
    }
}
