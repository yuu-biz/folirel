package io.github.yuubiz.feeddeck.backend.local.opml

import io.github.yuubiz.feeddeck.backend.local.parser.SafeXmlReader
import io.github.yuubiz.feeddeck.backend.local.parser.TextNormalizer
import io.github.yuubiz.feeddeck.backend.local.parser.XmlCharset
import io.github.yuubiz.feeddeck.backend.local.parser.XmlElement
import io.github.yuubiz.feeddeck.backend.local.parser.XmlLimits
import io.github.yuubiz.feeddeck.backend.local.parser.XmlRejectedException
import io.github.yuubiz.feeddeck.model.Limits
import io.github.yuubiz.feeddeck.network.UrlNormalizer

class OpmlRejectedException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { SECURITY, LIMIT_EXCEEDED, MALFORMED, NOT_OPML }
}

/** One `xmlUrl` occurrence. The same URL may occur several times (multiple categories). */
data class OpmlFeedOccurrence(
    val rawUrl: String,
    /** Normalized URL, or null when invalid/unsafe (see [invalidReason]). */
    val url: String?,
    val isHttps: Boolean,
    val invalidReason: UrlNormalizer.Reason?,
    /** title → text; null when both are empty (the host is used as a display fallback). */
    val label: String?,
    val siteUrl: String?,
    /** Category path segments; empty for top level (uncategorized). */
    val categoryPath: List<String>,
)

data class OpmlUnsupported(val label: String?, val type: String?)

enum class OpmlWarning { FEED_OUTLINE_WITH_CHILDREN }

data class OpmlDocument(
    val title: String?,
    val occurrences: List<OpmlFeedOccurrence>,
    /** Every folder path seen (including empty folders), in document order, without duplicates. */
    val categoryPaths: List<List<String>>,
    val unsupported: List<OpmlUnsupported>,
    val warnings: List<OpmlWarning>,
)

/**
 * OPML 1.x/2.0 `body/outline` reader (docs/data-and-import.md section 4). The whole file is rejected on DOCTYPE, limit
 * violations or malformed XML. Nothing referenced by the file (include/link targets) is fetched.
 */
object OpmlParser {
    private val LIMITS = XmlLimits(maxDepth = Limits.OPML_MAX_DEPTH)
    private val FOLDER_TYPES = setOf("", "folder", "category")
    private val ITEM_ATTRIBUTES = setOf("xmlurl", "htmlurl", "url")

    fun parse(bytes: ByteArray): OpmlDocument {
        if (bytes.size > Limits.OPML_MAX_BYTES) {
            throw OpmlRejectedException(OpmlRejectedException.Reason.LIMIT_EXCEEDED, "OPML larger than 5 MiB")
        }
        val text = XmlCharset.decode(bytes, httpCharset = null).text
        val root = try {
            SafeXmlReader.read(text, LIMITS)
        } catch (e: XmlRejectedException) {
            val reason = when (e.kind) {
                XmlRejectedException.Kind.SECURITY -> OpmlRejectedException.Reason.SECURITY
                XmlRejectedException.Kind.LIMIT_EXCEEDED -> OpmlRejectedException.Reason.LIMIT_EXCEEDED
                XmlRejectedException.Kind.MALFORMED -> OpmlRejectedException.Reason.MALFORMED
            }
            throw OpmlRejectedException(reason, e.message.orEmpty())
        }
        if (root.name != "opml") throw OpmlRejectedException(OpmlRejectedException.Reason.NOT_OPML, "Root is not <opml>")
        val body = root.child("", "body") ?: throw OpmlRejectedException(OpmlRejectedException.Reason.NOT_OPML, "No <body>")
        val title = root.child("", "head")?.childText("", "title")?.let { TextNormalizer.normalize(it) }?.ifEmpty { null }

        val occurrences = ArrayList<OpmlFeedOccurrence>()
        val paths = LinkedHashSet<List<String>>()
        val unsupported = ArrayList<OpmlUnsupported>()
        val warnings = ArrayList<OpmlWarning>()
        var outlineCount = 0

        fun walk(parent: XmlElement, path: List<String>) {
            for (outline in parent.children("", "outline")) {
                if (++outlineCount > Limits.OPML_MAX_OUTLINES) {
                    throw OpmlRejectedException(OpmlRejectedException.Reason.LIMIT_EXCEEDED, "More than ${Limits.OPML_MAX_OUTLINES} outlines")
                }
                val label = TextNormalizer.nfcTrim(outline.attr("title")).ifEmpty { TextNormalizer.nfcTrim(outline.attr("text")) }
                val xmlUrl = outline.attr("xmlUrl")
                val hasChildren = outline.children("", "outline").isNotEmpty()
                when {
                    xmlUrl != null -> {
                        if (occurrences.size + 1 > Limits.OPML_MAX_FEED_CANDIDATES) {
                            throw OpmlRejectedException(OpmlRejectedException.Reason.LIMIT_EXCEEDED, "More than ${Limits.OPML_MAX_FEED_CANDIDATES} feeds")
                        }
                        occurrences += occurrence(xmlUrl, label, outline.attr("htmlUrl"), path)
                        if (hasChildren) {
                            // Non-standard: a feed outline with children. Children stay at the same parent level.
                            warnings += OpmlWarning.FEED_OUTLINE_WITH_CHILDREN
                            walk(outline, path)
                        }
                    }
                    hasChildren -> {
                        if (label.isEmpty()) {
                            walk(outline, path) // nameless folder: promote children to the parent level
                        } else {
                            val childPath = path + label
                            paths += childPath
                            walk(outline, childPath)
                        }
                    }
                    else -> {
                        val type = outline.attr("type")?.trim()?.lowercase().orEmpty()
                        // Attribute names are case-sensitive; a mis-cased "xmlurl" is not a feed, but it is an item.
                        val itemAttributes = outline.plainAttributeNames.map { it.lowercase() }.intersect(ITEM_ATTRIBUTES)
                        val looksLikeItem = type !in FOLDER_TYPES || itemAttributes.isNotEmpty()
                        if (looksLikeItem) {
                            unsupported += OpmlUnsupported(label.ifEmpty { null }, type.ifEmpty { null })
                        } else if (label.isNotEmpty()) {
                            paths += path + label // empty category
                        }
                    }
                }
            }
        }
        walk(body, emptyList())
        return OpmlDocument(title, occurrences, paths.toList(), unsupported, warnings)
    }

    private fun occurrence(rawUrl: String, label: String, htmlUrl: String?, path: List<String>): OpmlFeedOccurrence {
        val siteUrl = UrlNormalizer.normalizedOrNull(htmlUrl)
        return when (val result = UrlNormalizer.normalize(rawUrl)) {
            is UrlNormalizer.Result.Valid -> OpmlFeedOccurrence(rawUrl, result.url, result.isHttps, null, label.ifEmpty { null }, siteUrl, path)
            is UrlNormalizer.Result.Invalid -> OpmlFeedOccurrence(rawUrl, null, false, result.reason, label.ifEmpty { null }, siteUrl, path)
        }
    }
}
