package io.github.yuubiz.feeddeck.backend.local.parser

import io.github.yuubiz.feeddeck.network.UrlNormalizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * HTML → plain text for summaries, using jsoup (never rendered, never executed, never stored as HTML).
 * Also extracts the first usable `<img src>` as a thumbnail candidate.
 */
object HtmlText {
    private const val REMOVED =
        "script, style, iframe, frame, frameset, object, embed, noscript, template, svg, math, canvas, " +
            "audio, video, form, button, select, textarea, head, title"

    class Extracted(val text: String, val firstImage: String?)

    fun extract(html: String?, baseUrl: String?, maxCodePoints: Int): Extracted {
        if (html.isNullOrBlank()) return Extracted("", null)
        val doc: Document = Jsoup.parseBodyFragment(html)
        val image = firstImage(doc, baseUrl)
        doc.select(REMOVED).remove()
        return Extracted(TextNormalizer.normalize(doc.body().text(), maxCodePoints), image)
    }

    fun toText(html: String?, maxCodePoints: Int): String = extract(html, null, maxCodePoints).text

    /** Entity-decodes a plain-text field (e.g. double-escaped RSS titles) without interpreting tags. */
    fun unescapeEntities(text: String?): String = if (text.isNullOrEmpty()) "" else Parser.unescapeEntities(text, false)

    private fun firstImage(doc: Document, baseUrl: String?): String? {
        for (img in doc.select("img[src]")) {
            if (isTrackingPixel(img.attr("width"), img.attr("height"))) continue
            val resolved = UrlNormalizer.resolve(baseUrl, img.attr("src"))
            if (resolved is UrlNormalizer.Result.Valid) return resolved.url
        }
        return null
    }

    fun isTrackingPixel(width: String?, height: String?): Boolean {
        fun dim(v: String?): Int? = v?.trim()?.removeSuffix("px")?.toIntOrNull()
        val w = dim(width)
        val h = dim(height)
        return (w != null && w <= 1) || (h != null && h <= 1)
    }
}
