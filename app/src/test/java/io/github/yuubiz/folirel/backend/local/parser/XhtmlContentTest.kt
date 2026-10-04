package io.github.yuubiz.folirel.backend.local.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** N11 / REV-03: Atom XHTML text keeps document order; inline images are found and resolved with xml:base. */
@RunWith(AndroidJUnit4::class)
class XhtmlContentTest {
    private fun atom(entryBody: String, entryBase: String = "https://example.com/base/") = """
        <feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://example.com/">
          <title>t</title>
          <entry xml:base="$entryBase"><id>urn:1</id><title>x</title>$entryBody</entry>
        </feed>
    """.trimIndent()

    @Test
    fun mixedContentKeepsOrder() {
        val feed = FeedParser.parse(
            atom("""<summary type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">A<b>B</b>C <i>D<b>E</b></i>F</div></summary>"""),
            "https://example.com/feed",
        )
        assertEquals("ABC DEF", feed.entries.single().summaryText)
    }

    @Test
    fun inlineImageIsFoundAndResolvedWithXmlBase() {
        val feed = FeedParser.parse(
            atom(
                """<content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
                     <p>text <img src="pixel.gif" width="1" height="1"/></p>
                     <p xml:base="https://cdn.example.net/media/">more <img src="photo.jpg" alt=""/></p>
                   </div></content>""",
            ),
            "https://example.com/feed",
        )
        val entry = feed.entries.single()
        assertEquals("https://cdn.example.net/media/photo.jpg", entry.imageUrl)
        assertEquals("text more", entry.summaryText)
    }

    @Test
    fun relativeImageUsesEntryBaseAndUnsafeSchemesAreIgnored() {
        val feed = FeedParser.parse(
            atom("""<summary type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><img src="javascript:alert(1)"/><img src="img/a.png"/>x &lt; y</div></summary>"""),
            "https://example.com/feed",
        )
        val entry = feed.entries.single()
        assertEquals("https://example.com/base/img/a.png", entry.imageUrl)
        assertEquals("x < y", entry.summaryText)
    }

    @Test
    fun textConstructsOtherThanXhtmlAreUnchanged() {
        val feed = FeedParser.parse(atom("""<summary type="text">a &lt;b&gt; c</summary>"""), "https://example.com/feed")
        assertEquals("a <b> c", feed.entries.single().summaryText)
        assertNull(feed.entries.single().imageUrl)
    }
}
