package io.github.yuubiz.feeddeck.backend.local.discovery

import io.github.yuubiz.feeddeck.backend.local.FeedFetcher
import io.github.yuubiz.feeddeck.backend.local.FetchOutcome
import io.github.yuubiz.feeddeck.backend.local.HtmlLinks
import io.github.yuubiz.feeddeck.backend.local.ResourceResult
import io.github.yuubiz.feeddeck.backend.local.SmallResourceFetcher
import io.github.yuubiz.feeddeck.backend.local.parser.FeedParseException
import io.github.yuubiz.feeddeck.backend.local.parser.FeedParser
import io.github.yuubiz.feeddeck.backend.local.parser.TextNormalizer
import io.github.yuubiz.feeddeck.backend.local.parser.XmlCharset
import io.github.yuubiz.feeddeck.model.CandidateVia
import io.github.yuubiz.feeddeck.model.DiscoveryCandidate
import io.github.yuubiz.feeddeck.model.DiscoveryResult
import io.github.yuubiz.feeddeck.model.EntryKeyAliases
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.model.Limits
import io.github.yuubiz.feeddeck.network.UrlNormalizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Looks on the site itself for where a feed went (SUB-DISCOVERY, in-site only): reads the site page (or the
 * address the user typed), takes the RSS/Atom links it declares, fetches each one and checks that it parses as a
 * feed. No search engine, no third-party service, no crawling of article pages. Nothing is chosen for the user and
 * nothing is changed here; every candidate carries the facts that can be checked (shared articles, same title,
 * dates), and "fetchable" means a real feed body, never an HTML page.
 */
class FeedDiscovery(
    private val pages: SmallResourceFetcher,
    private val feeds: FeedFetcher,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** What is already known about the subscribed feed. */
    class Known(
        val fetchUrl: String,
        val siteUrl: String?,
        val feedTitle: String?,
        val entryKeys: Set<String>,
        /** Newest article links; their most common origin stands in for an unknown site. */
        val articleLinks: List<String>,
    )

    /** [enteredUrl]: an address typed by the user (site page or feed); it replaces the site of the subscription. */
    suspend fun discover(known: Known, enteredUrl: String? = null): DiscoveryResult =
        discoverFrom(known, enteredUrl).copy(sourceFetchUrl = known.fetchUrl)

    private suspend fun discoverFrom(known: Known, enteredUrl: String?): DiscoveryResult {
        val entered = enteredUrl?.takeIf { it.isNotBlank() }
        val start = if (entered != null) {
            UrlNormalizer.normalizedOrNull(entered) ?: return DiscoveryResult(null, DiscoveryResult.PageStatus.UNREADABLE, emptyList())
        } else {
            known.siteUrl?.let { UrlNormalizer.normalizedOrNull(it) }
                ?: dominantOrigin(known.articleLinks)
                ?: return DiscoveryResult(null, DiscoveryResult.PageStatus.NO_SITE, emptyList())
        }

        val page = pages.get(start, Limits.SITE_PAGE_MAX_BYTES, "text/html,application/xhtml+xml;q=0.9,application/rss+xml;q=0.8,*/*;q=0.1", truncate = true)
        if (page is ResourceResult.Failed) {
            val status = if (page.kind == ResourceResult.Kind.HTTP_BLOCKED) DiscoveryResult.PageStatus.HTTP_NOT_ALLOWED else DiscoveryResult.PageStatus.UNREADABLE
            return DiscoveryResult(null, status, emptyList())
        }
        page as ResourceResult.Ok
        val links: List<HtmlLinks.FeedLink>
        val via: CandidateVia
        val status: DiscoveryResult.PageStatus
        // A feed is told by its root element; "<?xml" in front says nothing (XHTML pages begin with it too).
        if (HtmlLinks.looksLikeHtml(page.bytes, page.contentType) && !HtmlLinks.isFeedDocument(page.bytes)) {
            val charset = page.contentType?.let { Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            links = HtmlLinks.feedLinks(HtmlLinks.parse(page.bytes, charset, page.finalUrl))
            via = CandidateVia.SITE_PAGE
            status = DiscoveryResult.PageStatus.READ
        } else {
            // Not a page: the address itself may be the feed (the user pasted it).
            links = listOf(HtmlLinks.FeedLink(page.finalUrl, null, "application/xml"))
            via = CandidateVia.ENTERED_URL
            status = DiscoveryResult.PageStatus.FEED_ENTERED
        }
        val knownKeys = known.entryKeys.mapTo(HashSet()) { EntryKeyAliases.comparable(it) }
        val candidates = links
            .filter { it.url != known.fetchUrl }
            .take(MAX_CANDIDATES)
            .map { check(it, via, known, knownKeys) }
            .sortedWith(compareByDescending<DiscoveryCandidate> { it.fetchable }.thenByDescending { it.sharedEntries > 0 }.thenByDescending { it.sameTitle })
        return DiscoveryResult(page.finalUrl, status, candidates)
    }

    private suspend fun check(link: HtmlLinks.FeedLink, via: CandidateVia, known: Known, knownKeys: Set<String>): DiscoveryCandidate {
        val base = DiscoveryCandidate(url = link.url, via = via, fetchable = false, titleOnPage = link.title)
        val outcome = feeds.fetch(link.url, null, initialized = false)
        if (outcome is FetchOutcome.Failure) return base.copy(failure = outcome.error)
        if (outcome !is FetchOutcome.Success) return base.copy(failure = FetchError.HTTP_STATUS)
        val parsed = try {
            withContext(parseDispatcher) { FeedParser.parse(XmlCharset.decode(outcome.body, outcome.charset).text, outcome.finalUrl) }
        } catch (e: FeedParseException) {
            return base.copy(failure = e.error)
        }
        val keys = parsed.entries.mapTo(HashSet()) { EntryKeyAliases.comparable(it.entryKey) }
        val dates = parsed.entries.mapNotNull { it.publishedAt }
        return base.copy(
            // A redirect inside the candidate keeps working; adopting uses the final address.
            url = outcome.finalUrl,
            fetchable = true,
            feedTitle = parsed.title,
            siteUrl = parsed.siteUrl,
            entryCount = parsed.entries.size,
            oldestAt = dates.minOrNull(),
            newestAt = dates.maxOrNull(),
            sharedEntries = knownKeys.count { it in keys },
            sameTitle = known.feedTitle != null && parsed.title != null &&
                TextNormalizer.normalize(known.feedTitle).equals(TextNormalizer.normalize(parsed.title), ignoreCase = true),
        )
    }

    private fun dominantOrigin(links: List<String>): String? {
        val best = links.mapNotNull { UrlNormalizer.normalizedOrNull(it)?.toHttpUrlOrNull() }
            .map { Triple(it.scheme, it.host, it.port) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val default = if (best.first == "https") 443 else 80
        return "${best.first}://${best.second}${if (best.third != default) ":${best.third}" else ""}/"
    }

    private companion object {
        const val MAX_CANDIDATES = 8
    }
}
