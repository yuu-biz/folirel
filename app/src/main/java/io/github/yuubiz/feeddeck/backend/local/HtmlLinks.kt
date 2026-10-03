package io.github.yuubiz.feeddeck.backend.local

import io.github.yuubiz.feeddeck.network.UrlNormalizer
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
