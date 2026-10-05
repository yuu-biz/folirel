package io.github.yuubiz.folirel.model

import io.github.yuubiz.folirel.data.db.CategoryEntity
import io.github.yuubiz.folirel.data.db.CategoryPreferenceEntity
import io.github.yuubiz.folirel.data.db.FeedCategoryEntity
import io.github.yuubiz.folirel.data.db.FeedEntity
import io.github.yuubiz.folirel.data.db.FeedFetchStateEntity
import io.github.yuubiz.folirel.data.repository.CategoryPaths
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
    val lastSuccessAt: Long? = null,
    val failureCount: Int = 0,
    /** The server asked for no request before this time (Retry-After). */
    val retryAfterUntil: Long? = null,
    /** Next automatic attempt (failure backoff). */
    val nextEligibleAt: Long? = null,
) {
    val problem: FeedProblem? get() = lastError?.let { FeedProblem.of(it) }

    /** Waiting for a retry time: the server's Retry-After, or the failure backoff after an error. */
    fun isWaiting(now: Long): Boolean =
        (retryAfterUntil ?: 0) > now || (lastError != null && (nextEligibleAt ?: 0) > now)
}

/** Subscriptions and categories derived for drawer, tabs and scope resolution. */
data class Structure(
    /** In path order (the tree); the order of the tabs is [tabOrdered]. */
    val categories: List<CategoryNode> = emptyList(),
    val feeds: Map<String, FeedNode> = emptyMap(),
    val loaded: Boolean = false,
    /** What the reader chose per category (tab place, pin, display overrides), by category id; a missing id has every default. */
    val preferences: Map<String, CategoryPreference> = emptyMap(),
) {
    /** Every category in the order of the tabs (pinned first); the one order that tabs, pager, picker and organizer share. */
    val tabOrdered: List<CategoryNode> = TabOrder.resolve(categories, preferences)

    val subscribedFeeds: List<FeedNode> get() = feeds.values.filter { it.subscribed }
    val hasUncategorized: Boolean get() = subscribedFeeds.any { it.categoryIds.isEmpty() }
    /** The categories that get a tab: they or their descendants have a subscribed feed. In tab order. */
    val tabCategories: List<CategoryNode> get() = tabOrdered.filter { it.subtreeFeedCount > 0 }

    /** Subscribed feeds, not paused, whose articles could not be saved: a problem of this device, shown on its own until it is solved. */
    val feedsFailingToSave: Int get() = subscribedFeeds.count { !it.paused && it.lastError == FetchError.STORAGE }

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
            preferences: List<CategoryPreferenceEntity> = emptyList(),
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
                    lastSuccessAt = state?.lastSuccessAt,
                    failureCount = state?.failureCount ?: 0,
                    retryAfterUntil = state?.retryAfterUntil,
                    nextEligibleAt = state?.nextEligibleAt,
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
            val known = categories.map { it.id }.toSet()
            val prefs = preferences.filter { it.categoryId in known }.associate { it.categoryId to it.toModel() }
            return Structure(nodes, feedNodes, loaded = true, preferences = prefs)
        }

        fun displayName(feed: FeedEntity): String =
            feed.userTitle?.takeIf { it.isNotBlank() }
                ?: feed.feedTitle?.takeIf { it.isNotBlank() }
                ?: feed.subscriptionUrl.toHttpUrlOrNull()?.host
                ?: feed.subscriptionUrl
    }
}

private fun CategoryPreferenceEntity.toModel() = CategoryPreference(
    categoryId = categoryId,
    tabOrder = tabOrder,
    isPinned = isPinned,
    // An unknown stored name (a newer or older build wrote it) means "inherit": never an error, never a made-up value.
    listLayout = listModeOverride?.let { v -> ListMode.entries.firstOrNull { it.name == v } },
    cardStyle = cardStyleOverride?.let { v -> CardLayoutStyle.entries.firstOrNull { it.name == v } },
    cardColumns = cardColumnsOverride?.takeIf { it in 0..3 },
)
