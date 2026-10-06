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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The part of feed discovery that does not care why it runs: read one address (a site page, or a feed), take the RSS/Atom
 * links the page itself declares, fetch every one of them and check that it parses as a feed. Both "find where this feed
 * moved" ([FeedDiscovery]) and "subscribe to a shared address" (the share flow) are built on it, so the HTTP policy
 * (address rules, cleartext opt-in, TLS, redirects), the HTML link reading and the feed parser exist once.
 *
 * It decides nothing and changes nothing: no search engine, no crawling of article pages, nothing is stored. "Fetchable"
 * always means a real feed body, never an HTML page.
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

    /** [startUrl] is already normalized. [exclude]: addresses that are not wanted (the feed being replaced). */
    suspend fun probe(startUrl: String, exclude: Set<String> = emptySet()): Probed {
        val page = pages.get(startUrl, Limits.SITE_PAGE_MAX_BYTES, PAGE_ACCEPT, truncate = true)
        if (page is ResourceResult.Failed) {
            val status = if (page.kind == ResourceResult.Kind.HTTP_BLOCKED) DiscoveryResult.PageStatus.HTTP_NOT_ALLOWED else DiscoveryResult.PageStatus.UNREADABLE
            return Probed(null, status, page.kind, 0, emptyList())
        }
        page as ResourceResult.Ok
        val links: List<HtmlLinks.FeedLink>
        val via: CandidateVia
        val status: DiscoveryResult.PageStatus
        // A feed is told by its root element; "<?xml" in front says nothing (XHTML pages begin with it too).
        if (HtmlLinks.looksLikeHtml(page.bytes, page.contentType) && !HtmlLinks.isFeedDocument(page.bytes)) {
            val charset = page.contentType?.let { CHARSET.find(it)?.groupValues?.get(1) }
            links = HtmlLinks.feedLinks(HtmlLinks.parse(page.bytes, charset, page.finalUrl))
            via = CandidateVia.SITE_PAGE
            status = DiscoveryResult.PageStatus.READ
        } else {
            // Not a page: the address itself may be the feed (the user pasted it).
            links = listOf(HtmlLinks.FeedLink(page.finalUrl, null, "application/xml"))
            via = CandidateVia.ENTERED_URL
            status = DiscoveryResult.PageStatus.FEED_ENTERED
        }
        val wanted = links.filter { it.url !in exclude }
        return Probed(page.finalUrl, status, null, links.size, wanted.take(MAX_FEEDS).map { check(it, via) })
    }

    private suspend fun check(link: HtmlLinks.FeedLink, via: CandidateVia): ProbedFeed {
        val outcome = feeds.fetch(link.url, null, initialized = false)
        if (outcome is FetchOutcome.Failure) return failed(link, via, outcome.error)
        if (outcome !is FetchOutcome.Success) return failed(link, via, FetchError.HTTP_STATUS)
        val parsed = try {
            withContext(parseDispatcher) { FeedParser.parse(XmlCharset.decode(outcome.body, outcome.charset).text, outcome.finalUrl) }
        } catch (e: FeedParseException) {
            return failed(link, via, e.error)
        }
        val dates = parsed.entries.mapNotNull { it.publishedAt }
        return ProbedFeed(
            requestedUrl = link.url,
            // A redirect inside the candidate keeps working; adopting uses the final address.
            url = outcome.finalUrl,
            via = via,
            titleOnPage = link.title,
            fetchable = true,
            feedTitle = parsed.title,
            siteUrl = parsed.siteUrl,
            entryCount = parsed.entries.size,
            oldestAt = dates.minOrNull(),
            newestAt = dates.maxOrNull(),
            entryKeys = parsed.entries.mapTo(HashSet()) { EntryKeyAliases.comparable(it.entryKey) },
        )
    }

    private fun failed(link: HtmlLinks.FeedLink, via: CandidateVia, error: FetchError) =
        ProbedFeed(link.url, link.url, via, link.title, fetchable = false, failure = error)

    companion object {
        /** Feed addresses looked at per page. */
        const val MAX_FEEDS = 8
        private const val PAGE_ACCEPT = "text/html,application/xhtml+xml;q=0.9,application/rss+xml;q=0.8,*/*;q=0.1"
        private val CHARSET = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
    }
}
