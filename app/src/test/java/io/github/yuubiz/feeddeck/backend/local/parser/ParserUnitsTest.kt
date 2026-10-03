package io.github.yuubiz.feeddeck.backend.local.parser

import io.github.yuubiz.feeddeck.testutil.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Pure JVM tests: dates (T18), fallback digest (T17), text limits (T19), charset precedence (T15). */
class ParserUnitsTest {

    private fun iso(raw: String): String? = FeedDates.parse(raw)?.let { Instant.ofEpochMilli(it).toString() }

    @Test
    fun rfc822Variants() {
        assertEquals("2026-10-03T00:00:00Z", iso("Sat, 03 Oct 2026 09:00:00 +0900"))
        assertEquals("2026-10-03T00:00:00Z", iso("03 Oct 2026 00:00:00 GMT"))
        assertEquals("2026-10-03T00:00:00Z", iso("Sat, 3 Oct 2026 00:00 UT"))
        assertEquals("2026-10-03T05:00:00Z", iso("Sat, 03 Oct 2026 00:00:00 EST"))
        assertEquals("2026-10-03T00:00:00Z", iso("Sat, 03 Oct 26 00:00:00 Z"))
        assertEquals("2026-10-03T00:00:00Z", iso("Saturday, 03 October 2026 09:00:00 JST"))
        assertEquals("2026-10-03T00:00:00Z", iso("Sat, 03 Oct 2026 09:00:00 +09:00"))
        assertEquals("1999-10-03T00:00:00Z", iso("Sun, 03 Oct 99 00:00:00 GMT"))
    }

    @Test
    fun isoVariants() {
        assertEquals("2026-10-02T10:00:00Z", iso("2026-10-02T19:00:00+09:00"))
        assertEquals("2026-10-03T01:00:00Z", iso("2026-10-03T01:00:00Z"))
        assertEquals("2026-10-03T01:00:00.500Z", iso("2026-10-03T01:00:00.5Z"))
        assertEquals("2026-10-02T23:00:00Z", iso("2026-10-03 08:00:00+0900"))
        assertEquals("2026-10-02T23:00:00Z", iso("2026-10-03T08:00+09:00"))
    }

    @Test
    fun invalidOrZonelessDatesAreNull() {
        for (raw in listOf("not-a-date", "", "2026-10-03T08:00:00", "2026-10-03", "Sat, 03 Oct 2026 09:00:00", "Sat, 32 Oct 2026 09:00:00 GMT", "Sat, 03 Foo 2026 09:00:00 GMT", "Sat, 03 Oct 2026 09:00:00 XYZ")) {
            assertNull(raw, FeedDates.parse(raw))
        }
    }

    @Test
    fun fallbackDigestMatchesContractInput() {
        val fixture = Json.parseToJsonElement(Fixtures.text("expected/rss2-idless.json")).jsonObject
        val input = fixture.getValue("contractInput").jsonArray
        val expectedKey = fixture.getValue("assertions").jsonObject.getValue("articles").jsonArray[0].jsonObject.getValue("entryKey").jsonPrimitive.content
        val title = input[0].jsonPrimitive.content
        val date = if (input[1] is JsonNull) null else input[1].jsonPrimitive.longOrNull
        val author = input[2].jsonPrimitive.content
        val summary = input[3].jsonPrimitive.content
        assertEquals(expectedKey, "fallback:" + EntryKeys.fallbackDigest(title, date, author, summary))
    }

    @Test
    fun compactJsonEscaping() {
        assertEquals("""["a\"b\\c\n\u0001日本",null,123,""]""", EntryKeys.compactJsonArray(listOf("a\"b\\c\n\u0001日本", null, 123L, "")))
    }

    @Test
    fun entryKeyPrecedence() {
        assertEquals("id:abc", EntryKeys.of("  abc ", "https://e.com/", "t", null, null, "s"))
        assertEquals("id:HTTPS://E.COM/X#y", EntryKeys.of("HTTPS://E.COM/X#y", null, "t", null, null, "s"))
        assertEquals("url:https://e.com/", EntryKeys.of("  ", "https://e.com/", "t", null, null, "s"))
        assertTrue(EntryKeys.of(null, null, "t", null, null, "s").startsWith("fallback:"))
    }

    @Test
    fun truncationKeepsSurrogatePairs() {
        val emoji = "😀" // U+1F600, two UTF-16 chars
        val text = "a" + emoji.repeat(3)
        assertEquals("a$emoji", TextNormalizer.truncateCodePoints(text, 2))
        assertEquals("a" + emoji.repeat(3), TextNormalizer.truncateCodePoints(text, 4))
        val long = "あ".repeat(2100)
        assertEquals(2000, HtmlText.toText("<p>$long</p>", 2000).codePointCount(0, 2000))
    }

    @Test
    fun whitespaceAndNfcNormalization() {
        assertEquals("IDのない 記事", TextNormalizer.normalize(" IDのない   記事 "))
        assertEquals("が", TextNormalizer.normalize("が")) // NFD → NFC
        assertEquals("a b", TextNormalizer.normalize("a \n\tb"))
    }

    @Test
    fun charsetPrecedence() {
        val body = "<rss><channel><title>日本語</title></channel></rss>"
        // BOM beats declaration and header
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + ("<?xml version=\"1.0\" encoding=\"Shift_JIS\"?>$body").toByteArray(Charsets.UTF_8)
        XmlCharset.decode(bom, "EUC-JP").let {
            assertEquals(XmlCharset.Source.BOM, it.source)
            assertTrue(it.text.contains("日本語"))
            assertTrue(it.text.startsWith("<?xml"))
        }
        // Declaration beats header
        val decl = ("<?xml version=\"1.0\" encoding=\"EUC-JP\"?>$body").toByteArray(charset("EUC-JP"))
        XmlCharset.decode(decl, "utf-8").let {
            assertEquals(XmlCharset.Source.XML_DECLARATION, it.source)
            assertTrue(it.text.contains("日本語"))
        }
        // Header used when there is no declared encoding
        val noDecl = body.toByteArray(charset("EUC-JP"))
        XmlCharset.decode(noDecl, "EUC-JP").let {
            assertEquals(XmlCharset.Source.HTTP_HEADER, it.source)
            assertTrue(it.text.contains("日本語"))
        }
        // Default UTF-8
        XmlCharset.decode(body.toByteArray(Charsets.UTF_8), null).let {
            assertEquals(XmlCharset.Source.DEFAULT, it.source)
            assertTrue(it.text.contains("日本語"))
        }
        // Unknown declared name falls through to the header
        val bogus = ("<?xml version=\"1.0\" encoding=\"x-not-a-charset\"?>$body").toByteArray(Charsets.UTF_8)
        assertEquals(XmlCharset.Source.HTTP_HEADER, XmlCharset.decode(bogus, "utf-8").source)
        // UTF-16 BOM, both endians, BOM stripped
        for (cs in listOf(Charsets.UTF_16BE, Charsets.UTF_16LE)) {
            val bytes = ("﻿<?xml version=\"1.0\"?>$body").toByteArray(cs)
            val decoded = XmlCharset.decode(bytes, null)
            assertEquals(XmlCharset.Source.BOM, decoded.source)
            assertTrue(decoded.text.startsWith("<?xml"))
        }
    }

    @Test
    fun trackingPixelDetection() {
        assertTrue(HtmlText.isTrackingPixel("1", "1"))
        assertTrue(HtmlText.isTrackingPixel("1px", null))
        assertTrue(HtmlText.isTrackingPixel(null, "0"))
        assertEquals(false, HtmlText.isTrackingPixel("100", "80"))
        assertEquals(false, HtmlText.isTrackingPixel(null, null))
    }
}
