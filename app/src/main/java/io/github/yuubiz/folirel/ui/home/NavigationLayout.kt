package io.github.yuubiz.folirel.ui.home

import io.github.yuubiz.folirel.data.preferences.CategoryNavigation
import kotlin.math.max
import kotlin.math.min

/**
 * Whether the home screen shows the category navigation as a persistent pane or behind the menu button (a modal drawer), and how
 * wide the pane is. Decided by the width the window really has (never by a device name), the text size and the reader's choice;
 * pure, so it is testable on the JVM. A window that changes width (rotation, fold, split screen) simply gets the answer for its
 * new width.
 *
 * Width classes: compact < 600 dp, medium 600..839 dp, expanded >= 840 dp. Compact is always a drawer, whatever the choice.
 */
object NavigationLayout {
    const val MEDIUM_FROM_DP = 600f
    const val EXPANDED_FROM_DP = 840f

    /** The pane is about this wide at normal text size, and never wider than [MAX_PANE_DP] or narrower than [MIN_PANE_DP]. */
    const val BASE_PANE_DP = 240f
    const val MIN_PANE_DP = 220f
    const val MAX_PANE_DP = 300f

    /** The article area beside a pane keeps at least this width (more with larger text, up to [READABLE_SCALE_CAP] times). */
    const val MIN_CONTENT_DP = 360f
    const val READABLE_SCALE_CAP = 1.5f

    /** [paneWidthDp] is null when the navigation is a drawer. */
    data class Result(val paneWidthDp: Float?) {
        val pane: Boolean get() = paneWidthDp != null
    }

    private val DRAWER = Result(null)

    fun paneWidthDp(fontScale: Float): Float = (BASE_PANE_DP * max(1f, fontScale)).coerceIn(MIN_PANE_DP, MAX_PANE_DP)

    fun resolve(windowWidthDp: Float, fontScale: Float, preference: CategoryNavigation): Result {
        if (preference == CategoryNavigation.DRAWER || windowWidthDp < MEDIUM_FROM_DP) return DRAWER
        if (preference == CategoryNavigation.AUTO && windowWidthDp < EXPANDED_FROM_DP) return DRAWER
        val pane = paneWidthDp(fontScale)
        // "When possible": a pane that leaves the articles too little room is not shown.
        val minContent = MIN_CONTENT_DP * min(max(1f, fontScale), READABLE_SCALE_CAP)
        return if (windowWidthDp - pane >= minContent) Result(pane) else DRAWER
    }
}
