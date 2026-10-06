package io.github.yuubiz.folirel.ui.home

import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.PeriodFilter
import io.github.yuubiz.folirel.model.ReadFilter
import io.github.yuubiz.folirel.model.SortOrder

data class Selection(
    val scope: ArticleScope = ArticleScope.All,
    val filter: ReadFilter = ReadFilter.ALL,
    val sort: SortOrder = SortOrder.NEWEST,
    val search: String = "",
    val period: PeriodFilter = PeriodFilter.ALL,
)

/** Saved list position: first visible article and its pixel offset, plus its sort time for fallback. */
data class ListAnchor(val listKey: String, val articleId: String, val sortAt: Long, val offset: Int)

/** Fixed target set for "mark all as read", captured when the confirmation opens. */
data class MarkAllReadRequest(
    val ids: List<String>,
    val scopeTitle: String,
    val filter: ReadFilter,
    val search: String,
    val period: PeriodFilter = PeriodFilter.ALL,
    /** Hidden keywords were part of the set (the count above already leaves their articles out). */
    val hiddenKeywordsActive: Boolean = false,
)
