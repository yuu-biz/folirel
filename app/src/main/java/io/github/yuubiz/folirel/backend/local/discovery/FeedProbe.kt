package io.github.yuubiz.folirel.backend.local.discovery

import io.github.yuubiz.folirel.backend.local.FeedFetcher
import io.github.yuubiz.folirel.backend.local.FetchOutcome
import io.github.yuubiz.folirel.backend.local.HtmlLinks
import io.github.yuubiz.folirel.backend.local.ResourceResult
import io.github.yuubiz.folirel.backend.local.SmallResourceFetcher
import io.github.yuubiz.folirel.backend.local.parser.FeedParseException
import io.github.yuubiz.folirel.backend.local.parser.FeedParser
import io.github.yuubiz.folirel.backend.local.parser.XmlCharset
import io.github.yuubiz.folirel.model.CandidateVia
import io.github.yuubiz.folirel.model.DiscoveryResult
import io.github.yuubiz.folirel.model.EntryKeyAliases
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.model.Limits
import io.github.yuubiz.folirel.model.NormalizedFeed
import io.github.yuubiz.folirel.network.UrlNormalizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/**
 * The part of feed discovery that does not care why it runs: read one address (a site page, or a feed), take the RSS/Atom
 * links the page itself declares, fetch every one of them and check that it parses as a feed. Both "find where this feed
 * moved" ([FeedDiscovery]) and "subscribe to a shared address" (the share flow) are built on it, so the HTTP policy
 * (address rules, cleartext opt-in, TLS, redirects), the HTML link reading and the feed parser exist once.
 *
 * It decides nothing and changes nothing: no search engine, no crawling of article pages, nothing is stored. "Fetchable"
 * always means a real feed body, never an HTML page.
 *
 * The first read of an address is the only read of it: when the address turns out to be a feed, the bytes of that response are parsed
 * and become the candidate (no second request). With [siteRootFallback] one more page may be read: when an HTML page gave no usable
 * feed, the root page of the same origin (once, never anything deeper) is read for the feeds it declares.
 */
class FeedProbe(
    private val pages: SmallResourceFetcher,
    private val feeds: FeedFetcher,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** One feed address the probe looked at. */
    class ProbedFeed(
        /** The address that was declared by the page (or entered); a redirect inside it is not applied here. */
        val requestedUrl: String,
        /** The address to subscribe to: the final address after redirects when [fetchable], else [requestedUrl]. */
        val url: String,
        val via: CandidateVia,
        val titleOnPage: String?,
        /** True only for a body that parsed as RSS/Atom/RDF. */
        val fetchable: Boolean,
        val failure: FetchError? = null,
        val feedTitle: String? = null,
        val siteUrl: String? = null,
        val entryCount: Int = 0,
        val oldestAt: Long? = null,
        val newestAt: Long? = null,
        /** Comparable keys ([EntryKeyAliases.comparable]) of the entries, for callers that compare with a known feed. */
        val entryKeys: Set<String> = emptySet(),
    )

    class Probed(
        /** The page that was read (after redirects), or null when nothing could be read. */
        val pageUrl: String?,
        val page: DiscoveryResult.PageStatus,
        /** Why the page could not be read, when [page] is not a success. */
        val pageFailure: ResourceResult.Kind?,
        /** How many feed links the page declared (before the limit and the exclusion). */
        val declared: Int,
        val feeds: List<ProbedFeed>,
    )

    /**
     * [startUrl] is already normalized. [exclude]: addresses that are not wanted (the feed being replaced).
     * [siteRootFallback]: when [startUrl] is an HTML page that yields no usable feed and is not itself the root of its site, read the
     * root page of its (final) origin once and check the feeds that page declares.
     */
    suspend fun probe(startUrl: String, exclude: Set<String> = emptySet(), siteRootFallback: Boolean = false): Probed {
        val page = pages.getPageOrFeed(startUrl, Limits.SITE_PAGE_MAX_BYTES, Limits.FEED_MAX_BYTES, PAGE_ACCEPT)
        if (page is ResourceResult.Failed) {
            val status = if (page.kind == ResourceResult.Kind.HTTP_BLOCKED) DiscoveryResult.PageStatus.HTTP_NOT_ALLOWED else DiscoveryResult.PageStatus.UNREADABLE
            return Probed(null, status, page.kind, 0, emptyList())
        }
        page as ResourceResult.Ok
        // A feed is told by its root element; "<?xml" in front says nothing (XHTML pages begin with it too).
        if (!(HtmlLinks.looksLikeHtml(page.bytes, page.contentType) && !HtmlLinks.isFeedDocument(page.bytes))) {
            // Not a page: the address itself may be the feed (the user pasted it). What was just read is the feed; it is not fetched again.
            val feed = if (page.truncated) failed(page.finalUrl, page.finalUrl, CandidateVia.ENTERED_URL, null, FetchError.LIMIT_EXCEEDED)
            else parsed(page.finalUrl, page.finalUrl, CandidateVia.ENTERED_URL, null, page.bytes, charsetOf(page.contentType))
            return Probed(page.finalUrl, DiscoveryResult.PageStatus.FEED_ENTERED, null, 1, listOf(feed))
        }
        val links = HtmlLinks.feedLinks(HtmlLinks.parse(page.bytes, charsetName(page.contentType), page.finalUrl))
        val checked = links.filter { it.url !in exclude }.take(MAX_FEEDS).map { check(it, CandidateVia.SITE_PAGE) }
        val fromRoot = if (siteRootFallback && checked.none { it.fetchable }) {
            readRoot(page.finalUrl, tried = links.mapTo(HashSet()) { it.url } + exclude)
        } else {
            emptyList()
        }
        return Probed(page.finalUrl, DiscoveryResult.PageStatus.READ, null, links.size, checked + fromRoot)
    }

    /**
     * The root page of the origin of [pageUrl], read once, and the feeds it declares. Nothing happens when [pageUrl] is the root itself, when
     * the root cannot be read or is not an HTML page, or when it ends up on another origin. [tried]: feed addresses already checked.
     */
    private suspend fun readRoot(pageUrl: String, tried: Set<String>): List<ProbedFeed> {
        val origin = pageUrl.toHttpUrlOrNull() ?: return emptyList()
        val root = rootOf(origin) ?: return emptyList()
        if (isRoot(origin)) return emptyList()
        val page = pages.get(root, Limits.SITE_PAGE_MAX_BYTES, PAGE_ACCEPT, truncate = true) as? ResourceResult.Ok ?: return emptyList()
        val final = page.finalUrl.toHttpUrlOrNull() ?: return emptyList()
        if (!sameOrigin(origin, final)) return emptyList()
        if (!HtmlLinks.looksLikeHtml(page.bytes, page.contentType) || HtmlLinks.isFeedDocument(page.bytes)) return emptyList()
        val links = HtmlLinks.feedLinks(HtmlLinks.parse(page.bytes, charsetName(page.contentType), page.finalUrl))
        return links.filter { it.url !in tried }.take(MAX_FEEDS).map { check(it, CandidateVia.SITE_ROOT) }
    }

    private suspend fun check(link: HtmlLinks.FeedLink, via: CandidateVia): ProbedFeed {
        val outcome = feeds.fetch(link.url, null, initialized = false)
        if (outcome is FetchOutcome.Failure) return failed(link.url, link.url, via, link.title, outcome.error)
        if (outcome !is FetchOutcome.Success) return failed(link.url, link.url, via, link.title, FetchError.HTTP_STATUS)
        // A redirect inside the candidate keeps working; adopting uses the final address.
        return parsed(link.url, outcome.finalUrl, via, link.title, outcome.body, outcome.charset)
    }

    /** The bytes of a response parsed as a feed: a candidate, or the reason it is none. */
    private suspend fun parsed(requested: String, finalUrl: String, via: CandidateVia, titleOnPage: String?, body: ByteArray, charset: String?): ProbedFeed {
        val feed: NormalizedFeed = try {
            withContext(parseDispatcher) { FeedParser.parse(XmlCharset.decode(body, charset).text, finalUrl) }
        } catch (e: FeedParseException) {
            return failed(requested, requested, via, titleOnPage, e.error)
        }
        val dates = feed.entries.mapNotNull { it.publishedAt }
        return ProbedFeed(
            requestedUrl = requested,
            url = finalUrl,
            via = via,
            titleOnPage = titleOnPage,
            fetchable = true,
            feedTitle = feed.title,
            siteUrl = feed.siteUrl,
            entryCount = feed.entries.size,
            oldestAt = dates.minOrNull(),
            newestAt = dates.maxOrNull(),
            entryKeys = feed.entries.mapTo(HashSet()) { EntryKeyAliases.comparable(it.entryKey) },
        )
    }

    private fun failed(requested: String, url: String, via: CandidateVia, titleOnPage: String?, error: FetchError) =
        ProbedFeed(requested, url, via, titleOnPage, fetchable = false, failure = error)

    private fun charsetName(contentType: String?): String? = contentType?.let { CHARSET.find(it)?.groupValues?.get(1) }

    /** The charset the response named, as the feed fetcher reports it. */
    private fun charsetOf(contentType: String?): String? = try {
        contentType?.toMediaTypeOrNull()?.charset()?.name()
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        /** Feed addresses looked at per page. */
        const val MAX_FEEDS = 8
        private const val PAGE_ACCEPT = "text/html,application/xhtml+xml;q=0.9,application/rss+xml;q=0.8,*/*;q=0.1"
        private val CHARSET = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)

        /** `scheme://host[:port]/` of [url]; null when it is not a plain http(s) address. */
        internal fun rootOf(url: HttpUrl): String? =
            UrlNormalizer.normalizedOrNull(url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())

        /** Whether [url] is the root page of its site: the path is "/" and there is no query. */
        internal fun isRoot(url: HttpUrl): Boolean = url.encodedPath == "/" && url.query == null

        internal fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}
