package io.github.yuubiz.folirel.ui.home

import io.github.yuubiz.folirel.model.ArticleScope

/**
 * Decides what a settled page of the category pager means. A swipe that settles on another page is the reader's choice and is
 * committed. A page that settles while a tab, the drawer or the picker has just chosen something else is the page the pager
 * was leaving (its animation finished a moment too late): it must not undo the newer choice. A drag that began after that choice is
 * the reader again, and counts.
 */
internal class SettleGuard(initial: ArticleScope) {
    private var agreed = initial
    private var dragsAtAgreement = 0

    /** @return the scope the selection is to change to, or null when it stays. [drags]: how many drags the pager has seen so far. */
    fun onSettled(atPage: ArticleScope, selected: ArticleScope, drags: Int): ArticleScope? {
        if (atPage == selected) {
            agreed = selected
            dragsAtAgreement = drags
            return null
        }
        if (selected != agreed && drags == dragsAtAgreement) return null
        agreed = atPage
        dragsAtAgreement = drags
        return atPage
    }
}
