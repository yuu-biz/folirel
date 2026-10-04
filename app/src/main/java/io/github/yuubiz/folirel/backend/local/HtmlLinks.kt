package io.github.yuubiz.folirel.backend.local

import io.github.yuubiz.folirel.network.UrlNormalizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.ln

/**
 * What a site's HTML page points to: icon links and feed (RSS/Atom) alternates. jsoup only parses markup into a
 * tree; nothing is rendered, no script runs, no resource is loaded from here. URLs are resolved against the
 * response URL and `<base href>`, then validated like every other URL (http/https only, no userinfo).
 */
object HtmlLinks {
    /** An icon the page declares. [sizePx] is the larger side of its first "sizes" entry, null for "any"/missing. */
    data class IconLink(val url: String, val sizePx: Int?, val apple: Boolean)

    data class FeedLink(val url: String, val title: String?, val type: String)

    /** Parses [bytes] (the caller already limited them to Limits.SITE_PAGE_MAX_BYTES); [charset] is from the response, jsoup also sniffs `<meta>`. */
    fun parse(bytes: ByteArray, charset: String?, baseUrl: String): Document =
        Jsoup.parse(ByteArrayInputStream(bytes), charset, baseUrl)

    /** Whether [bytes] look like an HTML page (not an image or a feed that a misconfigured server returned). */
    fun looksLikeHtml(bytes: ByteArray, contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && (type.contains("html") || type == "text/plain")) return true
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).lowercase()
        return head.contains("<html") || head.contains("<!doctype html") || head.contains("<head")
    }


    /**
     * The local name of the root element (lower case, namespace prefix removed) of an XML/HTML document, or null when
     * none is found in the first 64 KiB. A byte order mark, whitespace, `<?xml ...?>` and other processing
     * instructions, comments and `<!DOCTYPE ...>` (with an internal subset) in front of it are skipped. Nothing is
     * parsed beyond the name, so no entity or DTD is ever read.
     */
    fun rootElement(bytes: ByteArray): String? {
        val head = bytes.copyOfRange(0, minOf(bytes.size, ROOT_SCAN_BYTES))
        val utf16 = head.size >= 2 && ((head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) || (head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()))
        val text = if (utf16) String(head, Charsets.UTF_16) else String(head, Charsets.ISO_8859_1)
        var i = 0
        while (i < text.length && (text[i].isWhitespace() || text[i].code == 0xFEFF || (!utf16 && i < 3 && text[i].code in BOM_BYTES))) i++
        while (i < text.length) {
            when {
                text[i].isWhitespace() -> i++
                text.startsWith("<?", i) -> i = text.indexOf("?>", i + 2).takeIf { it >= 0 }?.plus(2) ?: return null
                text.startsWith("<!--", i) -> i = text.indexOf("-->", i + 4).takeIf { it >= 0 }?.plus(3) ?: return null
                text.startsWith("<!", i) -> i = declarationEnd(text, i) ?: return null
                text[i] == '<' -> {
                    var j = i + 1
                    while (j < text.length && !text[j].isWhitespace() && text[j] != '>' && text[j] != '/') j++
                    val name = text.substring(i + 1, j).substringAfterLast(':').lowercase()
                    return name.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' } }
                }
                else -> return null
            }
        }
        return null
    }

    /** End (exclusive) of a `<!...>` declaration that starts at [start]; brackets of an internal subset and quotes are respected. */
    private fun declarationEnd(text: String, start: Int): Int? {
        var depth = 0
        var quote = NO_QUOTE
        var k = start + 2
        while (k < text.length) {
            val c = text[k]
            when {
                quote != NO_QUOTE -> if (c == quote) quote = NO_QUOTE
                c == '"' || c == '\'' -> quote = c
                c == '[' -> depth++
                c == ']' -> depth--
                c == '>' && depth <= 0 -> return k + 1
            }
            k++
        }
        return null
    }

    /** Whether the document is an RSS, Atom or RDF feed by its root element (and not an XHTML page that begins with `<?xml`). */
    fun isFeedDocument(bytes: ByteArray): Boolean = rootElement(bytes) in FEED_ROOTS

    private const val ROOT_SCAN_BYTES = 64 * 1024
    private const val NO_QUOTE = '\u0000'
    private val BOM_BYTES = setOf(0xEF, 0xBB, 0xBF)
    private val FEED_ROOTS = setOf("rss", "feed", "rdf")

    /** Usable (raster) icons of the page, best first: close to 48 px, `icon` before `apple-touch-icon`. */
    fun iconLinks(doc: Document): List<IconLink> =
        doc.select("link[href]").mapNotNull { el ->
            val rels = el.attr("rel").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val apple = rels.any { it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" }
            val icon = rels.contains("icon")
            if (!apple && !icon) return@mapNotNull null
            val type = el.attr("type").lowercase()
            if (type.contains("svg")) return@mapNotNull null
            val url = valid(el.absUrl("href")) ?: return@mapNotNull null
            if (url.substringBefore('?').lowercase().endsWith(".svg")) return@mapNotNull null
            IconLink(url, sizeOf(el.attr("sizes")), apple)
        }.distinctBy { it.url }.sortedBy { score(it) }

    /** RSS / Atom / RDF `rel=alternate` links. The page's own order is kept (the first is usually the main feed). */
    fun feedLinks(doc: Document): List<FeedLink> =
        doc.select("link[href]").mapNotNull { el ->
            val rels = el.attr("rel").lowercase().split(Regex("\\s+"))
            if (!rels.contains("alternate")) return@mapNotNull null
            val type = el.attr("type").lowercase().substringBefore(';').trim()
            if (type !in FEED_TYPES) return@mapNotNull null
            val url = valid(el.absUrl("href")) ?: return@mapNotNull null
            FeedLink(url, el.attr("title").trim().ifEmpty { null }, type)
        }.distinctBy { it.url }

    private val FEED_TYPES = setOf("application/rss+xml", "application/atom+xml", "application/rdf+xml")

    private fun valid(raw: String): String? = if (raw.isBlank()) null else UrlNormalizer.normalizedOrNull(raw)

    /** "32x32 16x16" -> 32; "any" or nothing -> null. */
    internal fun sizeOf(sizes: String): Int? =
        sizes.lowercase().split(Regex("\\s+")).firstNotNullOfOrNull { token ->
            Regex("^(\\d+)x(\\d+)$").find(token)?.let { maxOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        }?.takeIf { it > 0 }

    /** Lower is better. The target is 48 px; an unknown size counts as 32 px for `icon` (often a multi-size .ico). */
    private fun score(link: IconLink): Double {
        val size = (link.sizePx ?: if (link.apple) 180 else 32).toDouble()
        return abs(ln(size / TARGET_PX)) + if (link.apple) 0.15 else 0.0
    }

    private const val TARGET_PX = 48.0
}
