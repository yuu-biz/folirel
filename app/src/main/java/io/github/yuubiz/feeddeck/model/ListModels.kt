package io.github.yuubiz.feeddeck.model

/** What part of the subscriptions the list shows (docs/02 S10). Independent from [ReadFilter]. */
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

/** MAGAZINE: text + side thumbnail. LIST: compact one line. CARD: large card with a wide image on top. */
enum class ListMode { MAGAZINE, LIST, CARD }

/** Fully resolved list query. [categoryIds] already contains the selected category and its descendants. */
data class ArticleQuerySpec(
    val accountId: String,
    val scope: ArticleScope,
    val filter: ReadFilter,
    val sort: SortOrder,
    val search: String = "",
    val categoryIds: List<String> = emptyList(),
)
