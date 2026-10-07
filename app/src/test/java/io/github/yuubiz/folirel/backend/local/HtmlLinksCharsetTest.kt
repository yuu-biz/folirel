package io.github.yuubiz.folirel.backend.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The charset passed to [HtmlLinks.parse] comes from the response's Content-Type as the server wrote it. A quoted label (legal in
 * HTTP), an unknown one or a malformed one must not end the page reading (Share to Subscribe, the moved-feed search, the icon lookup).
 */
class HtmlLinksCharsetTest {
    private val page = """<html><head><meta charset="utf-8"><title>日本語</title>
        <link rel="alternate" type="application/rss+xml" title="Feed" href="/feed.xml"></head><body></body></html>""".toByteArray(Charsets.UTF_8)

    private fun links(charset: String?) = HtmlLinks.feedLinks(HtmlLinks.parse(page, charset, "https://example.com/a/")).map { it.url }

    @Test
    fun theDeclaredFeedIsFoundWhateverTheHeaderSaysAboutTheCharset() {
        val expected = listOf("https://example.com/feed.xml")
        for (label in listOf(null, "utf-8", "UTF-8", "\"utf-8\"", "'utf-8'", "no-such-charset", "utf 8", "", "\"\"")) {
            assertEquals("charset=$label", expected, links(label))
        }
        // A usable header label is still applied (the page's own meta then agrees).
        assertEquals("日本語", HtmlLinks.parse(page, "\"UTF-8\"", "https://example.com/").title())
        assertEquals("日本語", HtmlLinks.parse(page, "no-such-charset", "https://example.com/").title())
    }
}
