package io.github.yuubiz.folirel.ui.home

/**
 * What the article list needs from its owner: where each list was left (position restore) and the place where a new
 * article notice that the user dealt with is closed. [HomeViewModel] is the app's implementation.
 */
internal interface ListHost {
    fun anchorFor(listKey: String): ListAnchor?
    fun saveAnchor(anchor: ListAnchor)

    /**
     * The user answered the new-article notice (tapped it, went to the top, or dismissed it): these articles are not
     * announced again. This is not "read" and not "all seen"; it never touches article state.
     */
    fun acknowledgeNew(ids: Collection<String>)
}
