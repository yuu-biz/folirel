package io.github.yuubiz.folirel.backend.local.discovery

import io.github.yuubiz.folirel.backend.local.FeedFetcher
import io.github.yuubiz.folirel.backend.local.SmallResourceFetcher
import io.github.yuubiz.folirel.backend.local.parser.TextNormalizer
import io.github.yuubiz.folirel.model.DiscoveryCandidate
import io.github.yuubiz.folirel.model.DiscoveryResult
import io.github.yuubiz.folirel.model.EntryKeyAliases
import io.github.yuubiz.folirel.network.UrlNormalizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Looks on the site itself for where a feed went (SUB-DISCOVERY, in-site only): reads the site page (or the
 * address the user typed), takes the RSS/Atom links it declares, fetches each one and checks that it parses as a
 * feed ([FeedProbe] does the reading). No search engine, no third-party service, no crawling of article pages.
 * Nothing is chosen for the user and nothing is changed here; every candidate carries the facts that can be checked
 * (shared articles, same title, dates), and "fetchable" means a real feed body, never an HTML page.
 */
class FeedDiscovery(
    private val probe: FeedProbe,
) {
    constructor(
        pages: SmallResourceFetcher,
        feeds: FeedFetcher,
        parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(FeedProbe(pages, feeds, parseDispatcher))

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

        val probed = probe.probe(start, exclude = setOf(known.fetchUrl))
        val knownKeys = known.entryKeys.mapTo(HashSet()) { EntryKeyAliases.comparable(it) }
        val candidates = probed.feeds
            .map { f ->
                DiscoveryCandidate(
                    url = f.url,
                    via = f.via,
                    fetchable = f.fetchable,
                    failure = f.failure,
                    feedTitle = f.feedTitle,
                    siteUrl = f.siteUrl,
                    entryCount = f.entryCount,
                    oldestAt = f.oldestAt,
                    newestAt = f.newestAt,
                    sharedEntries = knownKeys.count { it in f.entryKeys },
                    sameTitle = known.feedTitle != null && f.feedTitle != null &&
                        TextNormalizer.normalize(known.feedTitle).equals(TextNormalizer.normalize(f.feedTitle), ignoreCase = true),
                    titleOnPage = f.titleOnPage,
                )
            }
            .sortedWith(compareByDescending<DiscoveryCandidate> { it.fetchable }.thenByDescending { it.sharedEntries > 0 }.thenByDescending { it.sameTitle })
        return DiscoveryResult(probed.pageUrl, probed.page, candidates)
    }

    private fun dominantOrigin(links: List<String>): String? {
        val best = links.mapNotNull { UrlNormalizer.normalizedOrNull(it)?.toHttpUrlOrNull() }
            .map { Triple(it.scheme, it.host, it.port) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val default = if (best.first == "https") 443 else 80
        return "${best.first}://${best.second}${if (best.third != default) ":${best.third}" else ""}/"
    }
}
