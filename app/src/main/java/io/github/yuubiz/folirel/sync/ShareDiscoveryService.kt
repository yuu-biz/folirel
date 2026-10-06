package io.github.yuubiz.folirel.sync

import io.github.yuubiz.folirel.backend.local.ResourceResult
import io.github.yuubiz.folirel.backend.local.discovery.FeedProbe
import io.github.yuubiz.folirel.data.repository.SubscriptionRepository
import io.github.yuubiz.folirel.model.DiscoveryResult
import io.github.yuubiz.folirel.model.ExistingFeed
import io.github.yuubiz.folirel.model.ShareCandidate
import io.github.yuubiz.folirel.model.ShareDiscovery
import io.github.yuubiz.folirel.model.ShareProblem
import io.github.yuubiz.folirel.model.Structure
import io.github.yuubiz.folirel.network.UrlNormalizer

/**
 * "Subscribe to a shared address" (Share to Subscribe): looks at one address the user chose, with the same page and feed
 * reading as the moved-feed search ([FeedProbe]; the address policy, HTTP opt-in, TLS and redirect rules are the network
 * client's own), and says which real feeds it offers and which of them the user already has. Reads only: the feed
 * is registered by the add-feed path after the user's explicit confirmation, never from here.
 */
class ShareDiscoveryService(
    private val probe: FeedProbe,
    private val subscriptions: SubscriptionRepository,
) {
    suspend fun discover(url: String): ShareDiscovery {
        val start = UrlNormalizer.normalizedOrNull(url) ?: return ShareDiscovery.Failed(ShareProblem.INVALID_URL)
        val probed = probe.probe(start)
        when (probed.page) {
            DiscoveryResult.PageStatus.HTTP_NOT_ALLOWED -> return ShareDiscovery.Failed(ShareProblem.HTTP_NOT_ALLOWED)
            DiscoveryResult.PageStatus.UNREADABLE, DiscoveryResult.PageStatus.NO_SITE ->
                return ShareDiscovery.Failed(problemOf(probed.pageFailure))
            DiscoveryResult.PageStatus.READ, DiscoveryResult.PageStatus.FEED_ENTERED -> Unit
        }
        val account = subscriptions.ensureLocalAccount()
        val candidates = probed.feeds.filter { it.fetchable }.distinctBy { it.url }.map { f ->
            // The final address first; the declared one too, because a feed registered under it is the same feed.
            val known = listOf(f.url, f.requestedUrl).distinct().firstNotNullOfOrNull { u -> subscriptions.findFeed(account, u)?.let { u to it } }
            ShareCandidate(
                url = f.url,
                subscribeUrl = known?.first ?: f.url,
                feedTitle = f.feedTitle,
                siteUrl = f.siteUrl,
                entryCount = f.entryCount,
                newestAt = f.newestAt,
                titleOnPage = f.titleOnPage,
                existing = known?.second?.let { ExistingFeed(it.id, Structure.displayName(it), it.isSubscribed) },
            )
        }
        if (candidates.isNotEmpty()) return ShareDiscovery.Found(probed.pageUrl, candidates)
        // The page was read. Feed links that were declared but could not be read are not the same as a page with none.
        return ShareDiscovery.Failed(if (probed.feeds.isNotEmpty() && probed.page == DiscoveryResult.PageStatus.READ) ShareProblem.FEEDS_UNREADABLE else ShareProblem.NO_FEED)
    }

    private fun problemOf(kind: ResourceResult.Kind?): ShareProblem = when (kind) {
        ResourceResult.Kind.HTTP_BLOCKED -> ShareProblem.HTTP_NOT_ALLOWED
        ResourceResult.Kind.BLOCKED -> ShareProblem.BLOCKED
        ResourceResult.Kind.TLS -> ShareProblem.TLS
        ResourceResult.Kind.STATUS -> ShareProblem.PAGE_STATUS
        ResourceResult.Kind.TOO_LARGE -> ShareProblem.PAGE_TOO_LARGE
        ResourceResult.Kind.NETWORK -> ShareProblem.NETWORK
        null -> ShareProblem.FAILED
    }
}
