package io.github.yuubiz.feeddeck.model

import io.github.yuubiz.feeddeck.data.db.CategoryEntity
import io.github.yuubiz.feeddeck.data.db.FeedCategoryEntity
import io.github.yuubiz.feeddeck.data.db.FeedEntity
import io.github.yuubiz.feeddeck.data.db.FeedFetchStateEntity
import io.github.yuubiz.feeddeck.data.repository.CategoryPaths
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CategoryNode(
    val id: String,
    val path: List<String>,
    val display: String,
    val depth: Int,
    /** Subscribed feeds in this category or its descendants. */
    val subtreeFeedCount: Int,
    val directFeedIds: List<String>,
)

data class FeedNode(
    val id: String,
    val name: String,
    /** Registered URL (OPML identity). */
    val url: String,
    /** Current fetch URL (may be https:// after a verified migration, or a permanent redirect target). */
    val fetchUrl: String,
    val subscribed: Boolean,
    val paused: Boolean,
    val lastError: FetchError?,
    val initialized: Boolean,
    val categoryIds: Set<String>,
)

/** Subscriptions and categories derived for drawer, tabs and scope resolution. */
data class Structure(
    val categories: List<CategoryNode> = emptyList(),
    val feeds: Map<String, FeedNode> = emptyMap(),
    val loaded: Boolean = false,
) {
    val subscribedFeeds: List<FeedNode> get() = feeds.values.filter { it.subscribed }
    val hasUncategorized: Boolean get() = subscribedFeeds.any { it.categoryIds.isEmpty() }
    val tabCategories: List<CategoryNode> get() = categories.filter { it.subtreeFeedCount > 0 }

    /** The selected category plus all descendants. */
    fun categoryIdsFor(categoryId: String): List<String> {
        val root = categories.firstOrNull { it.id == categoryId } ?: return listOf(categoryId)
        return categories.filter { CategoryPaths.isSameOrDescendant(it.path, root.path) }.map { it.id }
    }

    companion object {
        fun build(
            feeds: List<FeedEntity>,
            categories: List<CategoryEntity>,
            memberships: List<FeedCategoryEntity>,
            states: List<FeedFetchStateEntity>,
        ): Structure {
            val stateById = states.associateBy { it.feedId }
            val categoriesByFeed = memberships.groupBy({ it.feedId }, { it.categoryId })
            val feedNodes = feeds.associate { f ->
                val state = stateById[f.id]
                f.id to FeedNode(
                    id = f.id,
                    name = displayName(f),
                    url = f.subscriptionUrl,
                    fetchUrl = f.fetchUrl,
                    subscribed = f.isSubscribed,
                    paused = f.isPaused,
                    lastError = FetchError.fromStored(state?.lastErrorCode),
                    initialized = state?.initialized == true,
                    categoryIds = categoriesByFeed[f.id].orEmpty().toSet(),
                )
            }
            val paths = categories.associate { it.id to CategoryPaths.decode(it.pathSegmentsJson) }
            val membersByCategory = memberships.groupBy({ it.categoryId }, { it.feedId })
            val nodes = categories
                .sortedWith(compareBy<CategoryEntity>({ paths.getValue(it.id).joinToString("\u0000") }))
                .map { c ->
                    val path = paths.getValue(c.id)
                    val subtreeIds = paths.filterValues { CategoryPaths.isSameOrDescendant(it, path) }.keys
                    val subtreeFeeds = subtreeIds.flatMap { membersByCategory[it].orEmpty() }.toSet()
                        .count { feedNodes[it]?.subscribed == true }
                    CategoryNode(
                        id = c.id,
                        path = path,
                        display = CategoryPaths.display(path),
                        depth = path.size - 1,
                        subtreeFeedCount = subtreeFeeds,
                        directFeedIds = membersByCategory[c.id].orEmpty().sortedBy { feedNodes[it]?.name?.lowercase() },
                    )
                }
            return Structure(nodes, feedNodes, loaded = true)
        }

        fun displayName(feed: FeedEntity): String =
            feed.userTitle?.takeIf { it.isNotBlank() }
                ?: feed.feedTitle?.takeIf { it.isNotBlank() }
                ?: feed.subscriptionUrl.toHttpUrlOrNull()?.host
                ?: feed.subscriptionUrl
    }
}
