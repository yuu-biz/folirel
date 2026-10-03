package io.github.yuubiz.feeddeck.sync

import io.github.yuubiz.feeddeck.backend.local.discovery.FeedDiscovery
import io.github.yuubiz.feeddeck.data.repository.SubscriptionRepository
import io.github.yuubiz.feeddeck.model.DiscoveryResult

/**
 * "Find where this feed moved" for one subscribed feed the user picked (SUB-DISCOVERY). Reads only; adopting a
 * candidate is [SubscriptionRepository.applyUrlMove], after the user's explicit approval.
 */
class FeedDiscoveryService(
    private val discovery: FeedDiscovery,
    private val subscriptions: SubscriptionRepository,
) {
    /** The feed's own site, or [enteredUrl] (a site page or feed address the user found, e.g. by a web search). */
    suspend fun run(feedId: String, enteredUrl: String? = null): DiscoveryResult? {
        val source = subscriptions.discoverySource(feedId) ?: return null
        return discovery.discover(
            FeedDiscovery.Known(source.fetchUrl, source.siteUrl, source.feedTitle, source.entryKeys, source.articleLinks),
            enteredUrl,
        )
    }

    /** Name used for the "search the web" hand-off. */
    suspend fun searchName(feedId: String): String? = subscriptions.discoverySource(feedId)?.displayName
}
