package io.github.yuubiz.folirel.model

/** One list row (read-only projection). Built by [io.github.yuubiz.folirel.data.repository.ArticleQuery]. */
data class ArticleRow(
    val id: String,
    val feedId: String,
    val title: String,
    val summaryText: String,
    val linkUrl: String?,
    val imageUrl: String?,
    val author: String?,
    val publishedAt: Long?,
    val sortAt: Long,
    val isRead: Boolean,
    val isSaved: Boolean,
    val feedName: String?,
    val feedUrl: String,
    val feedSubscribed: Boolean,
)
