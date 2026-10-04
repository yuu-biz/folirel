package io.github.yuubiz.folirel.sync

import io.github.yuubiz.folirel.data.repository.SubscriptionRepository

/**
 * Adopting a new fetch address for a feed (SUB-DISCOVERY). The change is committed first, then the refresh layer is
 * told that the address of a feed changed, so the refresh the caller starts next is not merged into a request that
 * was made for the old address (V31-R01).
 */
class FeedAddressChange(
    private val subscriptions: SubscriptionRepository,
    private val refresh: RefreshCoordinator,
) {
    suspend fun adopt(feedId: String, fromUrl: String, toUrl: String): SubscriptionRepository.MoveResult {
        val result = subscriptions.applyUrlMove(feedId, fromUrl, toUrl)
        if (result == SubscriptionRepository.MoveResult.Applied) refresh.feedAddressChanged()
        return result
    }
}
