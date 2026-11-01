package io.github.yuubiz.folirel.backend.local.parser

import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.model.Limits
import io.github.yuubiz.folirel.model.NormalizedEntry
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.network.UrlNormalizer

/** [detail] names the limit or problem for diagnostics (e.g. ENTRY_COUNT, XML_DEPTH). */
class FeedParseException(val error: FetchError, message: String, val detail: String? = null) : Exception(message)

/**
 * RSS 2.0 / Atom 1.0 / RSS 1.0 (RDF) parser; a JSON object goes to [JsonFeedParser]. Elements are matched by namespace
 * URI + local name, never by prefix. Input is already-decoded text (see [XmlCharset]); [responseUrl] is the final
 * response URL used as the base when no xml:base applies.
 */
object FeedParser {
    const val ATOM = "http://www.w3.org/2005/Atom"
    const val ATOM_03 = "http://purl.org/atom/ns#"
    const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val RSS1 = "http://purl.org/rss/1.0/"
    const val DC = "http://purl.org/dc/elements/1.1/"
    const val CONTENT = "http://purl.org/rss/1.0/modules/content/"
    private val MEDIA = setOf("http://search.yahoo.com/mrss/", "http://search.yahoo.com/mrss")
    private const val XHTML = "http://www.w3.org/1999/xhtml"

    private val HTML_START = Regex("^\\s*(?:<!--[\\s\\S]*?-->\\s*)*<(?:!doctype\\s+html|html[\\s>])", RegexOption.IGNORE_CASE)
    private val LIMITS = XmlLimits(maxDepth = Limits.FEED_MAX_DEPTH)

    fun parse(text: String, responseUrl: String?): NormalizedFeed {
        if (text.firstOrNull { !it.isWhitespace() } == '{') return JsonFeedParser.parse(text, responseUrl)
        if (HTML_START.containsMatchIn(text.take(4096))) {
            throw FeedParseException(FetchError.NOT_A_FEED, "HTML page, not a feed")
        }
        val root = try {
            SafeXmlReader.read(text, LIMITS, responseUrl)
        } catch (e: XmlRejectedException) {
            val error = when (e.kind) {
                XmlRejectedException.Kind.SECURITY -> FetchError.SECURITY
                XmlRejectedException.Kind.LIMIT_EXCEEDED -> FetchError.LIMIT_EXCEEDED
                XmlRejectedException.Kind.MALFORMED -> FetchError.FORMAT
            }
            throw FeedParseException(error, e.message.orEmpty(), e.detail)
        }
        return when {
            root.namespace.isEmpty() && root.name == "rss" -> parseRss2(root)
            root.namespace == RDF && root.name == "RDF" -> parseRss1(root)
            root.namespace == ATOM && root.name == "feed" -> parseAtom(root)
            root.name.equals("html", ignoreCase = true) -> throw FeedParseException(FetchError.NOT_A_FEED, "HTML page")
            root.namespace == ATOM_03 -> throw FeedParseException(FetchError.UNSUPPORTED_FORMAT, "Atom 0.3 is not supported")
            else -> throw FeedParseException(FetchError.UNSUPPORTED_FORMAT, "Unsupported root element")
        }
    }

    // RSS 2.0

    private fun parseRss2(root: XmlElement): NormalizedFeed {
        val channel = root.child("", "channel") ?: throw FeedParseException(FetchError.UNSUPPORTED_FORMAT, "RSS without channel")
        val items = channel.children("", "item") + root.children("", "item")
        checkCount(items.size)
        val entries = items.map { item ->
            val guidEl = item.child("", "guid")
            val guid = guidEl?.text
            val linkCandidates = buildList {
                item.childText("", "link")?.let { add(it) }
                // A permalink guid is also the article URL; isPermaLink="false" makes it an ID only.
                if (guidEl != null && guidEl.attr("isPermaLink")?.trim()?.lowercase() != "false") add(guidEl.text)
            }
            val link = linkCandidates.firstNotNullOfOrNull { resolveUrl(item.base, it) }
            val description = item.childText("", "description")
            val encoded = item.childText(CONTENT, "encoded")
            buildEntry(
                rawId = guid,
                rawTitle = HtmlText.unescapeEntities(item.childText("", "title")),
                link = link,
                summaryHtml = description?.takeIf { it.isNotBlank() } ?: encoded,
                extraImageHtml = listOfNotNull(description, encoded),
                author = item.childText("", "author")?.takeIf { it.isNotBlank() } ?: item.childText(DC, "creator"),
                published = FeedDates.parse(item.childText("", "pubDate")) ?: FeedDates.parse(item.childText(DC, "date")),
                item = item,
            )
        }
        return NormalizedFeed(
            title = TextNormalizer.normalize(HtmlText.unescapeEntities(channel.childText("", "title")), Limits.TITLE_CODE_POINTS).ifEmpty { null },
            siteUrl = resolveUrl(channel.base, channel.childText("", "link")),
            entries = dedupe(entries),
            rawEntryCount = entries.size,
            iconUrl = channel.child("", "image")?.let { resolveUrl(it.base, it.childText("", "url")) },
        )
    }

    // RSS 1.0 (RDF)

    private fun parseRss1(root: XmlElement): NormalizedFeed {
        val channel = root.child(RSS1, "channel")
        val items = root.children(RSS1, "item")
        checkCount(items.size)
        val entries = items.map { item ->
            val description = item.childText(RSS1, "description")
            val encoded = item.childText(CONTENT, "encoded")
            buildEntry(
                rawId = item.attr("about", RDF),
                rawTitle = HtmlText.unescapeEntities(item.childText(RSS1, "title")),
                link = resolveUrl(item.base, item.childText(RSS1, "link")),
                summaryHtml = description?.takeIf { it.isNotBlank() } ?: encoded,
                extraImageHtml = listOfNotNull(description, encoded),
                author = item.childText(DC, "creator"),
                published = FeedDates.parse(item.childText(DC, "date")),
                item = item,
            )
        }
        return NormalizedFeed(
            title = channel?.let { TextNormalizer.normalize(HtmlText.unescapeEntities(it.childText(RSS1, "title")), Limits.TITLE_CODE_POINTS) }?.ifEmpty { null },
            siteUrl = channel?.let { resolveUrl(it.base, it.childText(RSS1, "link")) },
            entries = dedupe(entries),
            rawEntryCount = entries.size,
            iconUrl = root.child(RSS1, "image")?.let { resolveUrl(it.base, it.childText(RSS1, "url")) },
        )
    }

    // Atom 1.0

    private fun parseAtom(root: XmlElement): NormalizedFeed {
        val entriesEl = root.children(ATOM, "entry")
        checkCount(entriesEl.size)
        val feedAuthor = root.child(ATOM, "author")?.childText(ATOM, "name")
        val entries = entriesEl.map { entry ->
            val summaryEl = entry.child(ATOM, "summary")
            val contentEl = entry.child(ATOM, "content")
            val summaryHtml = atomTextAsHtml(summaryEl)?.takeIf { it.isNotBlank() } ?: atomTextAsHtml(contentEl)
            buildEntry(
                rawId = entry.childText(ATOM, "id"),
                rawTitle = atomTextAsPlain(entry.child(ATOM, "title")),
                link = atomAlternateLink(entry),
                summaryHtml = summaryHtml,
                extraImageHtml = listOfNotNull(atomTextAsHtml(summaryEl), atomTextAsHtml(contentEl)),
                inlineImage = xhtmlImage(summaryEl) ?: xhtmlImage(contentEl),
                author = entry.child(ATOM, "author")?.childText(ATOM, "name")?.takeIf { it.isNotBlank() }
                    ?: entry.child(ATOM, "source")?.child(ATOM, "author")?.childText(ATOM, "name")?.takeIf { it.isNotBlank() }
                    ?: feedAuthor,
                published = FeedDates.parse(entry.childText(ATOM, "published")) ?: FeedDates.parse(entry.childText(ATOM, "updated")),
                item = entry,
                atomEnclosures = entry.children(ATOM, "link").filter { it.attr("rel") == "enclosure" },
            )
        }
        return NormalizedFeed(
            title = TextNormalizer.normalize(atomTextAsPlain(root.child(ATOM, "title")), Limits.TITLE_CODE_POINTS).ifEmpty { null },
            siteUrl = atomAlternateLink(root),
            entries = dedupe(entries),
            rawEntryCount = entries.size,
            // atom:icon is meant to be square; atom:logo is usually a wide banner, so it is only the second choice.
            iconUrl = listOf("icon", "logo").firstNotNullOfOrNull { n -> root.child(ATOM, n)?.let { resolveUrl(it.base, it.text) } },
        )
    }

    private fun atomAlternateLink(owner: XmlElement): String? {
        val alternates = owner.children(ATOM, "link").filter { (it.attr("rel")?.trim() ?: "alternate") == "alternate" }
        val preferred = alternates.sortedBy { if (it.attr("type").isNullOrBlank() || it.attr("type") == "text/html") 0 else 1 }
        return preferred.firstNotNullOfOrNull { resolveUrl(it.base, it.attr("href")) }
    }

    /** Atom text construct rendered as HTML-ish input for [HtmlText]. XHTML: its text in document order. */
    private fun atomTextAsHtml(el: XmlElement?): String? {
        el ?: return null
        return when (el.attr("type")?.trim()?.lowercase() ?: "text") {
            "html" -> el.text
            "xhtml" -> escapeForHtml(el.deepText())
            "text" -> escapeForHtml(el.text)
            else -> if (el.attr("src") != null) null else escapeForHtml(el.text)
        }
    }

    private fun atomTextAsPlain(el: XmlElement?): String {
        el ?: return ""
        return when (el.attr("type")?.trim()?.lowercase() ?: "text") {
            "html" -> HtmlText.toText(el.text, Int.MAX_VALUE)
            "xhtml" -> el.deepText()
            else -> el.text
        }
    }

    /**
     * First usable `img/@src` inside an XHTML text construct (images are lost when it is turned into text).
     * Relative URLs resolve against the img element's own xml:base chain; tracking pixels and unsafe URLs are skipped.
     */
    private fun xhtmlImage(el: XmlElement?): String? {
        if (el == null || el.attr("type")?.trim()?.lowercase() != "xhtml") return null
        return el.descendants()
            .filter { it.name.equals("img", ignoreCase = true) && (it.namespace == XHTML || it.namespace.isEmpty()) }
            .firstNotNullOfOrNull { img -> imageUrl(img.base, img.attr("src"), img.attr("width"), img.attr("height")) }
    }

    internal fun escapeForHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // Shared

    private fun buildEntry(
        rawId: String?,
        rawTitle: String,
        link: String?,
        summaryHtml: String?,
        extraImageHtml: List<String>,
        author: String?,
        published: Long?,
        item: XmlElement,
        atomEnclosures: List<XmlElement> = emptyList(),
        inlineImage: String? = null,
    ): NormalizedEntry {
        val base = item.base
        val title = TextNormalizer.normalize(rawTitle, Limits.TITLE_CODE_POINTS)
        val summary = HtmlText.extract(summaryHtml, base, Limits.SUMMARY_CODE_POINTS)
        val normalizedAuthor = TextNormalizer.normalize(author, Limits.AUTHOR_CODE_POINTS)
        val image = mediaImage(item)
            ?: enclosureImage(item, atomEnclosures)
            ?: summary.firstImage
            ?: inlineImage
            ?: extraImageHtml.firstNotNullOfOrNull { HtmlText.extract(it, base, 0).firstImage }
        return NormalizedEntry(
            entryKey = EntryKeys.of(rawId, link, title, published, normalizedAuthor, summary.text),
            title = title,
            linkUrl = link,
            summaryText = summary.text,
            author = normalizedAuthor.ifEmpty { null },
            publishedAt = published,
            imageUrl = image,
        )
    }

    /** media:thumbnail, then image-typed media:content (also inside media:group). */
    private fun mediaImage(item: XmlElement): String? {
        val containers = listOf(item) + item.children.filter { it.namespace in MEDIA && it.name == "group" }
        for (c in containers) {
            c.children.filter { it.namespace in MEDIA && it.name == "thumbnail" }.forEach { thumb ->
                imageUrl(thumb.base, thumb.attr("url"), thumb.attr("width"), thumb.attr("height"))?.let { return it }
            }
        }
        for (c in containers) {
            c.children.filter { it.namespace in MEDIA && it.name == "content" }.forEach { content ->
                val type = content.attr("type")?.lowercase()
                val isImage = content.attr("medium") == "image" || (type != null && type.startsWith("image/") && type != "image/svg+xml")
                if (isImage) imageUrl(content.base, content.attr("url"), content.attr("width"), content.attr("height"))?.let { return it }
            }
        }
        return null
    }

    private fun enclosureImage(item: XmlElement, atomEnclosures: List<XmlElement>): String? {
        item.children("", "enclosure").forEach { e ->
            if (isRasterImageType(e.attr("type"))) imageUrl(e.base, e.attr("url"), null, null)?.let { return it }
        }
        atomEnclosures.forEach { e ->
            if (isRasterImageType(e.attr("type"))) imageUrl(e.base, e.attr("href"), null, null)?.let { return it }
        }
        return null
    }

    private fun isRasterImageType(type: String?): Boolean {
        val t = type?.trim()?.lowercase() ?: return false
        return t.startsWith("image/") && t != "image/svg+xml"
    }

    private fun imageUrl(base: String?, url: String?, width: String?, height: String?): String? {
        if (url.isNullOrBlank() || HtmlText.isTrackingPixel(width, height)) return null
        return resolveUrl(base, url)
    }

    internal fun resolveUrl(base: String?, raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return (UrlNormalizer.resolve(base, raw) as? UrlNormalizer.Result.Valid)?.url
    }

    internal fun checkCount(count: Int) {
        if (count > Limits.FEED_MAX_ENTRIES) {
            throw FeedParseException(FetchError.LIMIT_EXCEEDED, "More than ${Limits.FEED_MAX_ENTRIES} entries", "ENTRY_COUNT")
        }
    }

    /** Same key twice in one response: the first wins, later items only fill missing fields. */
    internal fun dedupe(entries: List<NormalizedEntry>): List<NormalizedEntry> {
        val byKey = LinkedHashMap<String, NormalizedEntry>()
        for (e in entries) {
            val existing = byKey[e.entryKey]
            byKey[e.entryKey] = if (existing == null) {
                e
            } else {
                existing.copy(
                    title = existing.title.ifEmpty { e.title },
                    linkUrl = existing.linkUrl ?: e.linkUrl,
                    summaryText = existing.summaryText.ifEmpty { e.summaryText },
                    author = existing.author ?: e.author,
                    publishedAt = existing.publishedAt ?: e.publishedAt,
                    imageUrl = existing.imageUrl ?: e.imageUrl,
                )
            }
        }
        return byKey.values.toList()
    }
}
