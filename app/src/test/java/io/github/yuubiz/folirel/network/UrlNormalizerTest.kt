package io.github.yuubiz.folirel.network

import io.github.yuubiz.folirel.testutil.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T06: URL identity driven by fixtures/url-identity.json. */
class UrlNormalizerTest {
    private val identity = Json.parseToJsonElement(Fixtures.text("url-identity.json")).jsonObject

    private fun valid(raw: String): String {
        val result = UrlNormalizer.normalize(raw)
        assertTrue("expected valid: $raw but was $result", result is UrlNormalizer.Result.Valid)
        return (result as UrlNormalizer.Result.Valid).url
    }

    @Test
    fun equalPairsNormalizeToSameValue() {
        val pairs = identity.getValue("equalPairs").jsonArray
        assertTrue(pairs.isNotEmpty())
        for (pair in pairs) {
            val (a, b) = pair.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$a vs $b", valid(a), valid(b))
        }
    }

    @Test
    fun distinctPairsStayDistinct() {
        val pairs = identity.getValue("distinctPairs").jsonArray
        assertTrue(pairs.isNotEmpty())
        for (pair in pairs) {
            val (a, b) = pair.jsonArray.map { it.jsonPrimitive.content }
            assertNotEquals("$a vs $b", valid(a), valid(b))
        }
    }

    @Test
    fun rejectedInputsAreInvalid() {
        val rejected = identity.getValue("rejected").jsonArray
        assertTrue(rejected.isNotEmpty())
        for (raw in rejected.map { it.jsonPrimitive.content }) {
            assertTrue(raw, UrlNormalizer.normalize(raw) is UrlNormalizer.Result.Invalid)
        }
    }

    @Test
    fun concreteNormalizationRules() {
        assertEquals("https://example.com/", valid("HTTPS://EXAMPLE.COM:443"))
        assertEquals("http://example.com/", valid("http://Example.com:80"))
        assertEquals("https://example.com:8443/a", valid("https://example.com:8443/a"))
        assertEquals("https://example.com/a?y=2&x=1", valid("https://example.com/a?y=2&x=1#frag"))
        assertEquals("https://xn--r8jz45g.jp/", valid("https://例え.jp"))
        assertEquals(
            UrlNormalizer.Result.Invalid(UrlNormalizer.Reason.USERINFO),
            UrlNormalizer.normalize("https://user@example.com/feed"),
        )
        assertTrue(UrlNormalizer.normalize("https://example.com/" + "a".repeat(9000)) is UrlNormalizer.Result.Invalid)
    }

    @Test
    fun resolveRelativeAndRejectUnsafeReferences() {
        assertEquals(
            UrlNormalizer.Result.Valid("https://example.org/base/articles/1", true),
            UrlNormalizer.resolve("https://example.org/base/articles/", "1"),
        )
        assertTrue(UrlNormalizer.resolve("https://example.org/", "javascript:alert(1)") is UrlNormalizer.Result.Invalid)
        assertTrue(UrlNormalizer.resolve("https://example.org/", "data:image/png;base64,AAAA") is UrlNormalizer.Result.Invalid)
        assertTrue(UrlNormalizer.resolve(null, "relative/path") is UrlNormalizer.Result.Invalid)
    }
}
