package io.github.yuubiz.feeddeck.ui.home

/**
 * What the article list needs from its owner: where each list was left (position restore) and the place where new
 * articles that were shown are no longer announced. [HomeViewModel] is the app's implementation.
 */
internal interface ListHost {
    fun anchorFor(listKey: String): ListAnchor?
    fun saveAnchor(anchor: ListAnchor)

    /** New articles that were shown (or jumped to) are no longer announced. */
    fun markNewSeen(ids: Collection<String>)
}
