package io.github.yuubiz.folirel.backend.local.parser

import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.model.Limits
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON Feed 1.0 and 1.1, reached through [FeedParser.parse] when the text is a JSON object. Same limits, URL rules and
 * summary extraction as the XML formats. `feed_url` is not read: like Atom `rel="self"`, a feed never picks its own address.
 */
internal object JsonFeedParser {
    private val VERSIONS = setOf("https://jsonfeed.org/version/1", "https://jsonfeed.org/version/1.1")

    fun parse(text: String, responseUrl: String?): NormalizedFeed {
        checkDepth(text)
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            throw FeedParseException(FetchError.FORMAT, "Malformed JSON")
        }
        if (root !is JsonObject || root.string("version") !in VERSIONS) {
            throw FeedParseException(FetchError.UNSUPPORTED_FORMAT, "Not JSON Feed version 1 or 1.1")
        }
        val items = when (val el = root["items"]) {
            null -> emptyList()
            is JsonArray -> el
            else -> throw FeedParseException(FetchError.FORMAT, "JSON Feed items is not an array")
        }
        FeedParser.checkCount(items.size)
        val feedAuthor = authorName(root)
        val entries = items.filterIsInstance<JsonObject>().map { entry(it, responseUrl, feedAuthor) }
        return NormalizedFeed(
            title = TextNormalizer.normalize(root.string("title"), Limits.TITLE_CODE_POINTS).ifEmpty { null },
            siteUrl = FeedParser.resolveUrl(responseUrl, root.string("home_page_url")),
            entries = FeedParser.dedupe(entries),
            rawEntryCount = entries.size,
            // favicon (64 px) is closer to the icon size the app wants than icon (512 px).
            iconUrl = listOf("favicon", "icon").firstNotNullOfOrNull { FeedParser.resolveUrl(responseUrl, root.string(it)) },
        )
    }

    private fun entry(item: JsonObject, base: String?, feedAuthor: String?): NormalizedEntry {
        val link = listOf("url", "external_url").firstNotNullOfOrNull { FeedParser.resolveUrl(base, item.string(it)) }
        val contentHtml = item.string("content_html")
        val summaryHtml = item.string("summary")?.takeIf { it.isNotBlank() }?.let(FeedParser::escapeForHtml)
            ?: contentHtml?.takeIf { it.isNotBlank() }
            ?: item.string("content_text")?.let(FeedParser::escapeForHtml)
        val summary = HtmlText.extract(summaryHtml, base, Limits.SUMMARY_CODE_POINTS)
        val title = TextNormalizer.normalize(item.string("title"), Limits.TITLE_CODE_POINTS)
        val author = TextNormalizer.normalize(authorName(item) ?: feedAuthor, Limits.AUTHOR_CODE_POINTS)
        val published = FeedDates.parse(item.string("date_published")) ?: FeedDates.parse(item.string("date_modified"))
        // Feeds in the wild write numeric ids; JSON Feed asks for strings but says to read numbers as their text.
        val id = (item["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        return NormalizedEntry(
            entryKey = EntryKeys.of(id, link, title, published, author, summary.text),
            title = title,
            linkUrl = link,
            summaryText = summary.text,
            author = author.ifEmpty { null },
            publishedAt = published,
            imageUrl = listOf("image", "banner_image").firstNotNullOfOrNull { FeedParser.resolveUrl(base, item.string(it)) }
                ?: summary.firstImage
                ?: HtmlText.extract(contentHtml, base, 0).firstImage,
        )
    }

    /** First named author of 1.1 `authors`, then the 1.0 `author` object. */
    private fun authorName(owner: JsonObject): String? =
        (owner["authors"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.string("name")?.takeIf(String::isNotBlank) }
            ?: (owner["author"] as? JsonObject)?.string("name")?.takeIf(String::isNotBlank)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Nesting is counted before the tree is built, so a body of brackets fails as a limit instead of costing a frame or a heap
     * object per level. Brackets inside strings do not count.
     */
    private fun checkDepth(text: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> escaped = false
                inString -> if (c == '\\') escaped = true else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '{' || c == '[' -> if (++depth > Limits.FEED_MAX_DEPTH) {
                    throw FeedParseException(FetchError.LIMIT_EXCEEDED, "JSON depth > ${Limits.FEED_MAX_DEPTH}", "JSON_DEPTH")
                }
                c == '}' || c == ']' -> depth--
            }
        }
    }
}
