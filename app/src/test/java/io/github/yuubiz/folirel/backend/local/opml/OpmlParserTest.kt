package io.github.yuubiz.folirel.backend.local.opml

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.testutil.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** T02, T04, T05, T40 (parser level). DB-level import is covered in SubscriptionRepositoryTest. */
@RunWith(AndroidJUnit4::class)
class OpmlParserTest {
    private fun assertions(name: String) =
        Json.parseToJsonElement(Fixtures.text("expected/$name")).jsonObject.getValue("assertions").jsonObject

    @Test
    fun subscriptionsHierarchy() {
        val doc = OpmlParser.parse(Fixtures.bytes("opml/subscriptions.opml"))
        val exp = assertions("subscriptions.json")
        assertEquals(exp.getValue("feedOccurrenceCount").jsonPrimitive.int, doc.occurrences.size)
        assertEquals(exp.getValue("uniqueFeedCount").jsonPrimitive.int, doc.occurrences.mapNotNull { it.url }.toSet().size)
        val expectedPaths = exp.getValue("categoryPaths").jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.content } }.toSet()
        assertEquals(expectedPaths, doc.categoryPaths.toSet())
        val expectedMemberships = exp.getValue("directMemberships").jsonArray.map { m ->
            m.jsonObject.getValue("url").jsonPrimitive.content to m.jsonObject.getValue("path").jsonArray.map { it.jsonPrimitive.content }
        }.toSet()
        val actualMemberships = doc.occurrences.filter { it.categoryPath.isNotEmpty() }.map { it.url!! to it.categoryPath }.toSet()
        assertEquals(expectedMemberships, actualMemberships)
        val withMembership = actualMemberships.map { it.first }.toSet()
        val unclassified = doc.occurrences.mapNotNull { it.url }.toSet() - withMembership
        assertEquals(exp.getValue("unclassifiedUrls").jsonArray.map { it.jsonPrimitive.content }.toSet(), unclassified)
        // Label: title → text
        assertEquals("Beta 日本語", doc.occurrences.first { it.url == "https://example.org/beta.atom" }.label)
        assertEquals("Gamma & News", doc.occurrences.first { it.url == "https://example.net/gamma.rdf" }.label)
        assertEquals("架空の購読", doc.title)
    }

    @Test
    fun warningsCandidates() {
        val doc = OpmlParser.parse(Fixtures.bytes("opml/warnings.opml"))
        val exp = assertions("warnings.json")
        val valid = doc.occurrences.filter { it.url != null }
        assertEquals(exp.getValue("candidateCount").jsonPrimitive.int, valid.size)
        assertEquals(exp.getValue("httpsCount").jsonPrimitive.int, valid.count { it.isHttps })
        assertEquals(exp.getValue("httpPendingCount").jsonPrimitive.int, valid.count { !it.isHttps })
        assertEquals(exp.getValue("invalidUrlCount").jsonPrimitive.int, doc.occurrences.count { it.url == null })
        assertEquals(exp.getValue("unsupportedLeafCount").jsonPrimitive.int, doc.unsupported.size)
        val expectedPaths = exp.getValue("categoryPaths").jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.content } }
        assertEquals(expectedPaths, doc.categoryPaths)
        assertEquals(listOf("Folder/with/slashes"), doc.occurrences.first { it.url == "https://example.com/slash.xml" }.categoryPath)
    }

    @Test
    fun malformedDoctypeAndLimitsRejectWholeFile() {
        val malformed = runCatching { OpmlParser.parse(Fixtures.bytes("opml/malformed.opml")) }.exceptionOrNull() as OpmlRejectedException
        assertEquals(OpmlRejectedException.Reason.MALFORMED, malformed.reason)
        val doctype = runCatching { OpmlParser.parse(Fixtures.bytes("opml/doctype.opml")) }.exceptionOrNull() as OpmlRejectedException
        assertEquals(OpmlRejectedException.Reason.SECURITY, doctype.reason)

        val tooMany = buildString {
            append("<opml version=\"2.0\"><body>")
            repeat(10_001) { append("<outline text=\"f$it\"/>") }
            append("</body></opml>")
        }
        assertEquals(OpmlRejectedException.Reason.LIMIT_EXCEEDED, (runCatching { OpmlParser.parse(tooMany.toByteArray()) }.exceptionOrNull() as OpmlRejectedException).reason)

        val tooManyFeeds = buildString {
            append("<opml version=\"2.0\"><body>")
            repeat(5_001) { append("<outline text=\"f\" xmlUrl=\"https://example.com/$it.xml\"/>") }
            append("</body></opml>")
        }
        assertEquals(OpmlRejectedException.Reason.LIMIT_EXCEEDED, (runCatching { OpmlParser.parse(tooManyFeeds.toByteArray()) }.exceptionOrNull() as OpmlRejectedException).reason)

        val deep = "<opml><body>" + "<outline text=\"d\">".repeat(40) + "</outline>".repeat(40) + "</body></opml>"
        assertEquals(OpmlRejectedException.Reason.LIMIT_EXCEEDED, (runCatching { OpmlParser.parse(deep.toByteArray()) }.exceptionOrNull() as OpmlRejectedException).reason)

        val big = ByteArray(5 * 1024 * 1024 + 1) { ' '.code.toByte() }
        assertEquals(OpmlRejectedException.Reason.LIMIT_EXCEEDED, (runCatching { OpmlParser.parse(big) }.exceptionOrNull() as OpmlRejectedException).reason)

        val notOpml = runCatching { OpmlParser.parse("<rss/>".toByteArray()) }.exceptionOrNull() as OpmlRejectedException
        assertEquals(OpmlRejectedException.Reason.NOT_OPML, notOpml.reason)
    }

    @Test
    fun nonStandardStructures() {
        val xml = """<opml version="2.0"><body>
            <outline text="">
              <outline text="Promoted" xmlUrl="https://example.com/p.xml"/>
            </outline>
            <outline text="Parent">
              <outline text="FeedWithKids" xmlUrl="https://example.com/k.xml">
                <outline text="Kid" xmlUrl="https://example.com/kid.xml"/>
              </outline>
            </outline>
            <outline text="Empty folder"/>
            <outline text=" Cafe${"́"} "><outline text="x" xmlUrl="https://example.com/x.xml"/></outline>
            <outline text="lower" xmlurl="https://example.com/lower.xml"/>
            <outline type="include" url="https://example.com/other.opml" text="inc"/>
            </body></opml>"""
        val doc = OpmlParser.parse(xml.toByteArray())
        assertEquals(emptyList<String>(), doc.occurrences.first { it.url == "https://example.com/p.xml" }.categoryPath)
        assertEquals(listOf("Parent"), doc.occurrences.first { it.url == "https://example.com/k.xml" }.categoryPath)
        assertEquals(listOf("Parent"), doc.occurrences.first { it.url == "https://example.com/kid.xml" }.categoryPath)
        assertEquals(listOf(OpmlWarning.FEED_OUTLINE_WITH_CHILDREN), doc.warnings)
        assertTrue(listOf("Empty folder") in doc.categoryPaths)
        // NFC + trim on segments
        assertTrue(doc.categoryPaths.toString(), listOf("Café") in doc.categoryPaths)
        // Attribute names are case-sensitive: "xmlurl" is not a feed; it is reported as unsupported.
        assertTrue(doc.occurrences.none { it.rawUrl.contains("lower") })
        assertEquals(2, doc.unsupported.size)
    }
}
