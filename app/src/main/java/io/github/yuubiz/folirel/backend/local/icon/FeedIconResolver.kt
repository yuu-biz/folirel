package io.github.yuubiz.folirel.backend.local.icon

import io.github.yuubiz.folirel.backend.local.HtmlLinks
import io.github.yuubiz.folirel.backend.local.ResourceResult
import io.github.yuubiz.folirel.backend.local.SmallResourceFetcher
import io.github.yuubiz.folirel.model.Limits
import io.github.yuubiz.folirel.network.UrlNormalizer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Finds the source icon of one feed (UX-ICON), in this order:
 * 1. the icon the feed document declares (square-ish only; a wide logo is kept aside as a last resort)
 * 2. `link rel=icon` / `apple-touch-icon` of the feed's site page (best three, closest to 48 px first)
 * 3. `/favicon.ico` of the site
 * 4. the kept-aside wide logo; otherwise nothing, and the UI draws the initial letter.
 *
 * The site is the feed's declared site URL, or the most common origin of its recent article links. The feed URL's
 * own host is never assumed to be the site (it can be a CDN). Everything goes through [SmallResourceFetcher], so the
 * HTTP opt-in, address policy, TLS verification and byte limits apply; no third-party favicon service is asked.
 */
class FeedIconResolver(
    private val fetcher: SmallResourceFetcher,
    private val decoder: IconDecoder,
) {
    class Target(
        /** Absolute icon URL the feed declares, if any. */
        val declaredUrl: String?,
        val siteUrl: String?,
        /** Newest article links, to find the site when [siteUrl] is missing. */
        val articleLinks: List<String>,
    )

    sealed interface Result {
        class Found(val icon: DecodedIcon, val kind: String, val sourceUrl: String) : Result

        /** The site has no usable icon (or none could be tried): not an error. */
        data object None : Result

        /** Nothing found and part of the lookup failed in a way that may pass (network, HTTP not allowed, 5xx). */
        data object Retry : Result
    }

    suspend fun resolve(target: Target): Result {
        var retry = false
        var banner: Result.Found? = null
        val tried = HashSet<String>()

        suspend fun tryIcon(url: String, kind: String): Result.Found? {
            if (!tried.add(url)) return null
            when (val r = fetcher.get(url, Limits.ICON_MAX_BYTES, "image/*,*/*;q=0.5")) {
                is ResourceResult.Failed -> {
                    if (r.retryable) retry = true
                    return null
                }
                is ResourceResult.Ok -> {
                    val icon = decoder.decode(r.bytes) ?: return null
                    if (icon.isBanner) {
                        if (banner == null) banner = Result.Found(icon, KIND_BANNER, url)
                        return null
                    }
                    return Result.Found(icon, kind, url)
                }
            }
        }

        target.declaredUrl?.let { url -> tryIcon(url, KIND_FEED)?.let { return it } }

        val site = siteOrigin(target)
        if (site != null) {
            var finalOrigin: Origin = site
            val page = fetcher.get(site.pageUrl, Limits.SITE_PAGE_MAX_BYTES, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1", truncate = true)
            if (page is ResourceResult.Failed) {
                if (page.retryable) retry = true
            } else if (page is ResourceResult.Ok) {
                page.finalUrl.toHttpUrlOrNull()?.let { finalOrigin = Origin(it.scheme, it.host, it.port, page.finalUrl) }
                if (HtmlLinks.looksLikeHtml(page.bytes, page.contentType)) {
                    val charset = page.contentType?.let { ct -> Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1) }
                    val doc = HtmlLinks.parse(page.bytes, charset, page.finalUrl)
                    for (link in HtmlLinks.iconLinks(doc).take(MAX_HTML_ICONS)) tryIcon(link.url, KIND_HTML)?.let { return it }
                }
            }
            tryIcon(finalOrigin.root + "favicon.ico", KIND_FAVICON)?.let { return it }
        }
        banner?.let { return it }
        return if (retry) Result.Retry else Result.None
    }

    /** scheme, host and port of the site and the page to read for its icon links. */
    private class Origin(val scheme: String, val host: String, val port: Int, val pageUrl: String) {
        val root: String get() = "$scheme://${if (host.contains(':')) "[$host]" else host}${if (port != defaultPort(scheme)) ":$port" else ""}/"
    }

    private fun siteOrigin(target: Target): Origin? {
        val declared = target.siteUrl?.let { UrlNormalizer.normalizedOrNull(it) }?.toHttpUrlOrNull()
        if (declared != null) return Origin(declared.scheme, declared.host, declared.port, declared.toString())
        // No declared site: where the articles live. The most common origin wins; a tie keeps the newest article's.
        val origins = target.articleLinks.mapNotNull { UrlNormalizer.normalizedOrNull(it)?.toHttpUrlOrNull() }
            .map { Triple(it.scheme, it.host, it.port) }
        val best = origins.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val root = Origin(best.first, best.second, best.third, "")
        return Origin(best.first, best.second, best.third, root.root)
    }

    companion object {
        private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

        const val KIND_FEED = "FEED"
        const val KIND_HTML = "HTML"
        const val KIND_FAVICON = "FAVICON"
        const val KIND_BANNER = "BANNER"
        private const val MAX_HTML_ICONS = 3
    }
}
