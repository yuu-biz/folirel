package io.github.yuubiz.folirel.model

/** What part of the subscriptions the list shows (docs/product-and-ux.md S10). Independent from [ReadFilter]. */
sealed interface ArticleScope {
    data object All : ArticleScope
    data object Uncategorized : ArticleScope
    data class Category(val categoryId: String) : ArticleScope
    data class Feed(val feedId: String) : ArticleScope

    /** Stable string form for SavedStateHandle / DataStore. */
    fun encode(): String = when (this) {
        All -> "all"
        Uncategorized -> "uncategorized"
        is Category -> "category:$categoryId"
        is Feed -> "feed:$feedId"
    }

    companion object {
        fun decode(value: String?): ArticleScope = when {
            value == null || value == "all" -> All
            value == "uncategorized" -> Uncategorized
            value.startsWith("category:") -> Category(value.removePrefix("category:"))
            value.startsWith("feed:") -> Feed(value.removePrefix("feed:"))
            else -> All
        }
    }
}

enum class ReadFilter { ALL, UNREAD, SAVED }

enum class SortOrder { NEWEST, OLDEST }

/**
 * How the article list is laid out. The names are the user-facing concepts (Settings and menu: List, Compact list,
 * Magazine); [name] is what is stored in the settings (key `list_layout`).
 * - LIST: one column; title, two lines of summary, a side thumbnail, the source line and the buttons.
 * - COMPACT_LIST: one dense row per article.
 * - MAGAZINE: cards of different sizes packed into gap-free rows (see MagazinePlan, CardGridLayout).
 * - MASONRY: lanes of cards of different heights, each card under the shortest lane (see MasonryPlan).
 * - BENTO: tiles of four shapes (small, wide, tall, big) packed into gap-free blocks (see BentoPlan).
 *
 * A build that does not know a stored name reads the default instead (an older version after a downgrade).
 */
enum class ListMode { LIST, COMPACT_LIST, MAGAZINE, MASONRY, BENTO }

/** How the cards of the Magazine layout are arranged (the persisted keys keep their `card_` names). */
enum class CardLayoutStyle {
    /** Cards of different sizes packed into irregular, gap-free rows, like a magazine page (see MagazinePlan). */
    MIXED,

    /** Every card the same size. */
    EVEN,
}

/** Fully resolved list query. [categoryIds] already contains the selected category and its descendants. */
data class ArticleQuerySpec(
    val accountId: String,
    val scope: ArticleScope,
    val filter: ReadFilter,
    val sort: SortOrder,
    val search: String = "",
    val categoryIds: List<String> = emptyList(),
)
