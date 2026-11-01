package io.github.yuubiz.folirel.backend.local.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.testutil.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** T11–T15, T17, T19, T40: parser against the synthetic fixtures with subset assertions from fixtures/expected. */
@RunWith(AndroidJUnit4::class)
class FeedParserFixtureTest {

    private fun parseFixture(path: String, httpCharset: String? = null, responseUrl: String = "https://example.com/feed"): NormalizedFeed {
        val decoded = XmlCharset.decode(Fixtures.bytes(path), httpCharset)
        return FeedParser.parse(decoded.text, responseUrl)
    }

    private fun expected(name: String): JsonObject =
        Json.parseToJsonElement(Fixtures.text("expected/$name")).jsonObject.getValue("assertions").jsonObject

    /** Subset assertion: only the keys present in the expected JSON are compared. */
    private fun assertArticles(feed: NormalizedFeed, assertions: JsonObject) {
        assertions["articleCount"]?.let { assertEquals(it.jsonPrimitive.int, feed.entries.size) }
        assertions["feedTitle"]?.let { assertEquals(it.jsonPrimitive.content, feed.title) }
        val articles = assertions["articles"]?.jsonArray ?: return
        assertTrue(articles.isNotEmpty())
        for (exp in articles.map { it.jsonObject }) {
            val key = exp.getValue("entryKey").jsonPrimitive.content
            val actual: NormalizedEntry = feed.entries.singleOrNull { it.entryKey == key }
                ?: error("No entry with key $key in ${feed.entries.map { it.entryKey }}")
            for ((field, value) in exp) {
                val expectedValue = if (value is JsonNull) null else value.jsonPrimitive.contentOrNull
                val actualValue: Any? = when (field) {
                    "entryKey" -> actual.entryKey
                    "title" -> actual.title
                    "linkUrl" -> actual.linkUrl
                    "summaryText" -> actual.summaryText
                    "imageUrl" -> actual.imageUrl
                    "author" -> actual.author
                    "publishedAt" -> actual.publishedAt?.toString()
                    "publishedAtIso" -> actual.publishedAt?.let { Instant.ofEpochMilli(it).toString() }
                    else -> fail("Unknown expected field $field")
                }
                assertEquals("$key.$field", expectedValue, actualValue)
            }
        }
    }

    @Test
    fun rss2Basic() {
        val feed = parseFixture("feeds/rss2-basic.xml")
        assertArticles(feed, expected("rss2-basic.json"))
        val first = feed.entries.first { it.entryKey == "id:alpha-001" }
        assertFalse("script text must never reach the summary", first.summaryText.contains("NEVER_EXECUTE"))
        assertEquals("編集部", first.author)
        // guid isPermaLink=false is an ID only, never the article URL.
        assertNull(feed.entries.first { it.entryKey == "id:opaque-no-link" }.linkUrl)
        assertEquals("https://example.com/", feed.siteUrl)
    }

    @Test
    fun rss2UpdatedKeepsSameKey() {
        val feed = parseFixture("feeds/rss2-updated.xml")
        val exp = expected("rss2-updated.json")
        val entry = feed.entries.single()
        assertEquals(exp.getValue("entryKey").jsonPrimitive.content, entry.entryKey)
        assertEquals(exp.getValue("title").jsonPrimitive.content, entry.title)
    }

    @Test
    fun atomBasic() {
        val feed = parseFixture("feeds/atom-basic.xml")
        assertArticles(feed, expected("atom-basic.json"))
        assertEquals("架空のAtom", feed.title)
    }

    @Test
    fun atomNamespaceIsMatchedByUriNotPrefix() {
        // Same document with the default namespace instead of the "a:" prefix must parse identically.
        val prefixed = Fixtures.text("feeds/atom-basic.xml")
        val unprefixed = prefixed.replace("xmlns:a=", "xmlns=").replace("<a:", "<").replace("</a:", "</")
        val a = FeedParser.parse(prefixed, "https://example.com/feed")
        val b = FeedParser.parse(unprefixed, "https://example.com/feed")
        assertEquals(a, b)
    }

    @Test
    fun rss1Basic() {
        val feed = parseFixture("feeds/rss1-basic.xml")
        assertArticles(feed, expected("rss1-basic.json"))
        // channel rdf:about is not an item
        assertEquals(1, feed.entries.size)
        assertEquals("架空のRDF", feed.title)
        assertEquals("https://example.net/news/001", feed.entries.single().linkUrl)
    }

    @Test
    fun shiftJisDeclaredEncoding() {
        val feed = parseFixture("feeds/rss2-shiftjis.xml")
        assertArticles(feed, expected("rss2-shiftjis.json"))
    }

    @Test
    fun shiftJisDeclarationWinsOverWrongHttpCharset() {
        // V1 precedence: BOM → XML declaration → HTTP charset → UTF-8.
        val feed = parseFixture("feeds/rss2-shiftjis.xml", httpCharset = "utf-8")
        assertEquals("日本語配信", feed.title)
    }

    @Test
    fun idlessFallbackDigest() {
        val feed = parseFixture("feeds/rss2-idless.xml")
        assertArticles(feed, expected("rss2-idless.json"))
        // The key does not depend on receive time or position: parsing twice yields the same key.
        assertEquals(feed.entries.single().entryKey, parseFixture("feeds/rss2-idless.xml").entries.single().entryKey)
    }

    @Test
    fun unsafeHtmlIsReducedToTextAndSafeImage() {
        val feed = parseFixture("feeds/rss2-unsafe-html.xml")
        assertArticles(feed, expected("rss2-unsafe-html.json"))
        val entry = feed.entries.single()
        for (secret in listOf("SECRET_SCRIPT", "SECRET_STYLE", "SECRET_IFRAME")) {
            assertFalse(secret, entry.summaryText.contains(secret))
        }
    }

    @Test
    fun xxeFixtureIsRejectedAsSecurity() {
        val e = runCatching { parseFixture("feeds/rss2-xxe.xml") }.exceptionOrNull()
        assertTrue("expected FeedParseException but was $e", e is FeedParseException)
        assertEquals(FetchError.SECURITY, (e as FeedParseException).error)
    }

    @Test
    fun doctypeWithoutEntityIsStillRejected() {
        val xml = """<?xml version="1.0"?><!DOCTYPE rss><rss version="2.0"><channel><title>t</title></channel></rss>"""
        val e = runCatching { FeedParser.parse(xml, null) }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.SECURITY, e.error)
        assertNull(e.detail)
    }

    @Test
    fun htmlNamedEntitiesAreDecodedWithoutReadingAnyDtd() {
        val feed = parseFixture("feeds/rss2-html-entities.xml")
        // The no-break space is ordinary whitespace once the text is normalised; it still separates the words.
        assertEquals("Fictional Notes © 2026", feed.title)
        val entry = feed.entries.single()
        assertEquals("Draft one — it’s here…", entry.title)
        assertEquals("Plain text “quoted” & done… © ½", entry.summaryText)
    }

    @Test
    fun nbspIsReadAsANoBreakSpace() {
        val root = SafeXmlReader.read(Fixtures.text("feeds/rss2-html-entities.xml"), XmlLimits(maxDepth = 64))
        assertEquals("Fictional Notes © 2026", root.child("", "channel")!!.childText("", "title"))
    }

    @Test
    fun anUnknownEntityIsAFormatErrorNotASecurityOne() {
        val xml = """<rss version="2.0"><channel><title>a &notarealentity; b</title></channel></rss>"""
        val e = runCatching { FeedParser.parse(xml, null) }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.FORMAT, e.error)
    }

    @Test
    fun anHtmlNameInsideAnAttributeIsLeftAsLiteralText() {
        // Attribute values are resolved by the platform parser, which knows only the XML built-ins and keeps any other
        // reference as it was written (docs/fetch-and-security.md). Known limit, unchanged by the decoding of text.
        val xml = """<rss version="2.0"><channel><title>t</title><item><guid>1</guid><enclosure url="https://example.com/a&nbsp;b.jpg" type="image/jpeg"/></item></channel></rss>"""
        val v = SafeXmlReader.read(xml, XmlLimits(maxDepth = 64)).descendants().first { it.name == "enclosure" }.attr("url")
        assertEquals("https://example.com/a&nbsp;b.jpg", v)
    }

    @Test
    fun netscapeRss091DoctypeIsRejectedAndSaysWhy() {
        val e = runCatching { parseFixture("feeds/rss091-netscape-doctype.xml") }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.SECURITY, e.error)
        assertEquals("DOCTYPE_RSS091", e.detail)
    }

    @Test
    fun aNetscapeDoctypeWithItsOwnDeclarationsGetsNoSpecialLabel() {
        val xml = """<?xml version="1.0"?><!DOCTYPE rss PUBLIC "-//Netscape Communications//DTD RSS 0.91//EN" "https://example.invalid/rss-0.91.dtd" [<!ENTITY x SYSTEM "https://example.invalid/x">]><rss version="0.91"><channel><title>&x;</title></channel></rss>"""
        val e = runCatching { FeedParser.parse(xml, null) }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.SECURITY, e.error)
        assertNull(e.detail)
    }

    @Test
    fun utf16DoctypeCannotBypassCheck() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss version="2.0"><channel><title>&x;</title></channel></rss>"""
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bytes = xml.toByteArray(charset)
            val decoded = XmlCharset.decode(bytes, null)
            val e = runCatching { FeedParser.parse(decoded.text, null) }.exceptionOrNull() as FeedParseException
            assertEquals(FetchError.SECURITY, e.error)
        }
    }

    @Test
    fun xincludeIsRejected() {
        val xml = """<rss version="2.0" xmlns:xi="http://www.w3.org/2001/XInclude"><channel><xi:include href="file:///etc/passwd"/></channel></rss>"""
        val e = runCatching { FeedParser.parse(xml, null) }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.SECURITY, e.error)
    }

    @Test
    fun malformedFeedIsFormatError() {
        val e = runCatching { parseFixture("feeds/malformed.xml") }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.FORMAT, e.error)
    }

    @Test
    fun htmlPageIsNotAFeed() {
        for (html in listOf("<!DOCTYPE html><html><body>hi</body></html>", "\n<html lang=\"ja\"><head></head></html>")) {
            val e = runCatching { FeedParser.parse(html, null) }.exceptionOrNull() as FeedParseException
            assertEquals(FetchError.NOT_A_FEED, e.error)
        }
    }

    @Test
    fun entryLimitAndDepthLimit() {
        fun rss(items: Int) = buildString {
            append("<rss version=\"2.0\"><channel><title>t</title>")
            repeat(items) { append("<item><guid>g$it</guid><title>x</title></item>") }
            append("</channel></rss>")
        }
        assertEquals(2000, FeedParser.parse(rss(2000), null).entries.size)
        val tooMany = runCatching { FeedParser.parse(rss(2001), null) }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.LIMIT_EXCEEDED, tooMany.error)

        val deep = "<rss><channel>" + "<x>".repeat(70) + "</x>".repeat(70) + "</channel></rss>"
        val tooDeep = runCatching { FeedParser.parse(deep, null) }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.LIMIT_EXCEEDED, tooDeep.error)

        val hugeAttr = "<rss version=\"2.0\"><channel a=\"" + "x".repeat(70_000) + "\"></channel></rss>"
        val attr = runCatching { FeedParser.parse(hugeAttr, null) }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.LIMIT_EXCEEDED, attr.error)
    }

    @Test
    fun missingFieldsDoNotDropEntries() {
        val xml = """<rss version="2.0"><channel><title>t</title>
            <item><description>only text</description></item>
            <item><title>bad link</title><link>javascript:alert(1)</link><pubDate>Mon, 99 Foo 2026</pubDate></item>
            <item><title>rel</title><link>/a/b?q=1#frag</link></item>
            </channel></rss>"""
        val feed = FeedParser.parse(xml, "https://example.com/dir/feed.xml")
        assertEquals(3, feed.entries.size)
        assertEquals("", feed.entries[0].title)
        assertNull(feed.entries[1].linkUrl)
        assertNull(feed.entries[1].publishedAt)
        assertEquals("url:https://example.com/a/b?q=1", feed.entries[2].entryKey)
        assertEquals("https://example.com/a/b?q=1", feed.entries[2].linkUrl)
    }

    @Test
    fun imagePriorityMediaThenEnclosureThenHtml() {
        val xml = """<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>t</title>
            <item><guid>1</guid><description>&lt;img src="html.jpg"&gt;</description><enclosure url="enc.jpg" type="image/jpeg"/><media:content url="mc.jpg" medium="image"/></item>
            <item><guid>2</guid><description>&lt;img src="html.jpg"&gt;</description><enclosure url="enc.jpg" type="image/jpeg"/></item>
            <item><guid>3</guid><description>&lt;img src="html.jpg"&gt;</description><enclosure url="a.mp3" type="audio/mpeg"/></item>
            <item><guid>4</guid><media:thumbnail url="px.gif" width="1" height="1"/><description>&lt;img src="data:image/gif;base64,AA"&gt;</description></item>
            <item><guid>5</guid><enclosure url="pic.svg" type="image/svg+xml"/></item>
            </channel></rss>"""
        val images = FeedParser.parse(xml, "https://img.example.com/f/").entries.map { it.imageUrl }
        assertEquals(
            listOf("https://img.example.com/f/mc.jpg", "https://img.example.com/f/enc.jpg", "https://img.example.com/f/html.jpg", null, null),
            images,
        )
    }

    @Test
    fun xmlBaseInheritanceAndUnsafeBaseIgnored() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://a.example.com/root/">
            <title>t</title>
            <entry xml:base="javascript:alert(1)"><id>1</id><title>x</title><link href="p1"/></entry>
            <entry xml:base="sub/"><id>2</id><title>y</title><link rel="alternate" type="text/html" href="p2"/></entry>
            <entry><id>3</id><title>z</title><link href="https://other.example.com/abs"/></entry>
            </feed>"""
        val entries = FeedParser.parse(xml, "https://ignored.example.com/feed").entries
        assertEquals("https://a.example.com/root/p1", entries[0].linkUrl)
        assertEquals("https://a.example.com/root/sub/p2", entries[1].linkUrl)
        assertEquals("https://other.example.com/abs", entries[2].linkUrl)
    }

    @Test
    fun relativeLinkUsesFinalResponseUrlWithoutXmlBase() {
        val xml = """<rss version="2.0"><channel><title>t</title><item><guid>1</guid><link>../x.html</link></item></channel></rss>"""
        val entry = FeedParser.parse(xml, "https://final.example.com/a/b/feed.xml").entries.single()
        assertEquals("https://final.example.com/a/x.html", entry.linkUrl)
    }

    @Test
    fun duplicateKeyInOneResponseKeepsFirstAndFillsMissing() {
        val xml = """<rss version="2.0"><channel><title>t</title>
            <item><guid>dup</guid><title>first</title></item>
            <item><guid>dup</guid><title>second</title><link>https://example.com/l</link></item>
            </channel></rss>"""
        val entry = FeedParser.parse(xml, null).entries.single()
        assertEquals("first", entry.title)
        assertEquals("https://example.com/l", entry.linkUrl)
    }

    @Test
    fun rss2DcFallbacks() {
        val xml = """<rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><title>t</title>
            <item><guid>1</guid><title>x</title><dc:date>2026-10-03T08:00:00+09:00</dc:date><dc:creator>DC作者</dc:creator></item>
            </channel></rss>"""
        val entry = FeedParser.parse(xml, null).entries.single()
        assertEquals("2026-10-02T23:00:00Z", Instant.ofEpochMilli(entry.publishedAt!!).toString())
        assertEquals("DC作者", entry.author)
        assertNotNull(entry.entryKey)
    }

    @Test
    fun jsonFeed10Minimal() {
        val feed = parseFixture("feeds/jsonfeed-1.0-minimal.json")
        assertArticles(feed, expected("jsonfeed-1.0-minimal.json"))
        assertEquals("https://example.com/", feed.siteUrl)
    }

    @Test
    fun jsonFeed11Full() {
        val feed = parseFixture("feeds/jsonfeed-1.1-full.json", responseUrl = "https://example.org/feeds/feed.json")
        assertArticles(feed, expected("jsonfeed-1.1-full.json"))
        assertEquals("https://example.org/", feed.siteUrl)
        assertEquals("https://example.org/favicon-64.png", feed.iconUrl)
        assertFalse("script text must never reach the summary", feed.entries.any { it.summaryText.contains("NEVER_EXECUTE") })
        assertEquals("a non-object item is skipped", 3, feed.rawEntryCount)
    }

    @Test
    fun jsonFeedWithoutIdsUsesLinkThenFallbackKey() {
        assertArticles(parseFixture("feeds/jsonfeed-idless.json"), expected("jsonfeed-idless.json"))
    }

    @Test
    fun brokenJsonIsFormatErrorAndOtherJsonIsUnsupported() {
        val broken = runCatching { parseFixture("feeds/jsonfeed-invalid.json") }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.FORMAT, broken.error)
        val future = runCatching { parseFixture("feeds/jsonfeed-wrong-version.json") }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.UNSUPPORTED_FORMAT, future.error)
        val cases = mapOf(
            """{"items": []}""" to FetchError.UNSUPPORTED_FORMAT,
            """{"version": 1, "items": []}""" to FetchError.UNSUPPORTED_FORMAT,
            """{"version": "https://jsonfeed.org/version/1.1", "items": {}}""" to FetchError.FORMAT,
        )
        for ((json, error) in cases) {
            assertEquals(json, error, (runCatching { FeedParser.parse(json, null) }.exceptionOrNull() as FeedParseException).error)
        }
    }

    @Test
    fun jsonFeedWithByteOrderMarkAndHeaderCharset() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + Fixtures.bytes("feeds/jsonfeed-1.0-minimal.json")
        assertEquals(1, FeedParser.parse(XmlCharset.decode(bytes, "utf-8").text, null).entries.size)
    }

    @Test
    fun jsonEntryLimitAndDepthLimit() {
        fun json(items: Int, title: String = "x") = buildString {
            append("""{"version":"https://jsonfeed.org/version/1.1","title":"$title","items":[""")
            append((0 until items).joinToString(",") { """{"id":"g$it","title":"x"}""" })
            append("]}")
        }
        assertEquals(2000, FeedParser.parse(json(2000), null).entries.size)
        val tooMany = runCatching { FeedParser.parse(json(2001), null) }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.LIMIT_EXCEEDED, tooMany.error)
        assertEquals("ENTRY_COUNT", tooMany.detail)

        val deep = """{"version":"https://jsonfeed.org/version/1.1","x":""" + "[".repeat(10_000) + "]".repeat(10_000) + "}"
        val tooDeep = runCatching { FeedParser.parse(deep, null) }.exceptionOrNull() as FeedParseException
        assertEquals(FetchError.LIMIT_EXCEEDED, tooDeep.error)
        assertEquals("JSON_DEPTH", tooDeep.detail)

        val bracketsInText = "[{\\\"".repeat(100)
        assertEquals("brackets inside strings are text", bracketsInText.replace("\\", ""), FeedParser.parse(json(1, bracketsInText), null).title)
    }
}
