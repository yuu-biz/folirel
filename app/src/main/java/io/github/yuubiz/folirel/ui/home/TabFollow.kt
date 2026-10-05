package io.github.yuubiz.folirel.ui.home

import kotlin.math.abs
import kotlin.math.floor

/** Where one tab title is in the content of the tab row (px from the left edge of the scrolled content, not of the screen). */
internal data class TitleSpan(val center: Float, val width: Float)

/**
 * The geometry of "the tab row follows the pager" (P1-NAV), kept free of Compose so it can be tested on the JVM.
 *
 * The position of the pager is `currentPage + currentPageOffsetFraction`. The row is scrolled so that the interpolated centre of
 * the two neighbouring titles sits in the middle of the row, limited to what can be scrolled; a row whose tabs all fit is not moved.
 */
internal object TabFollow {
    /** The title at a fractional pager position: the neighbouring titles blended, or null while one of them is not measured. */
    fun interpolate(spans: List<TitleSpan?>, position: Float): TitleSpan? {
        if (spans.isEmpty()) return null
        val last = spans.lastIndex
        val from = floor(position).toInt().coerceIn(0, last)
        val to = (from + 1).coerceAtMost(last)
        val a = spans[from] ?: return null
        val b = spans[to] ?: a
        val f = (position - from).coerceIn(0f, 1f)
        return TitleSpan(a.center + (b.center - a.center) * f, a.width + (b.width - a.width) * f)
    }

    /** Scroll that puts [center] in the middle of the viewport, within 0..[maxScroll] (no empty space is added at the ends). */
    fun centred(center: Float, viewport: Float, maxScroll: Float): Float =
        if (maxScroll <= 0f) 0f else (center - viewport / 2f).coerceIn(0f, maxScroll)
}

/**
 * Decides the scroll of the tab row for each position of the pager. The row follows the pager continuously; when the reader had
 * moved the row by hand, that displacement is kept at the start and fades over the first page of pager movement, so following
 * resumes from where the row is instead of jumping. A pager that comes back to where it started gives the displaced row back.
 */
internal class TabFollower {
    private var restPosition: Float? = null
    private var moving = false
    private var moveFrom = 0f
    private var displacement = 0f

    /** The row was put at [position] from outside (first layout, new geometry): movement is measured from here. */
    fun settleAt(position: Float) {
        restPosition = position
        moving = false
    }

    /**
     * The scroll the row should have at [position], or null when the geometry is not known yet. [atRest]: the pager is not moving;
     * [userBrowsing]: the reader is moving the row with a finger (it is followed from where it is, not pulled away).
     */
    fun scrollFor(
        position: Float,
        atRest: Boolean,
        userBrowsing: Boolean,
        scroll: Float,
        spans: List<TitleSpan?>,
        viewport: Float,
        maxScroll: Float,
    ): Float? {
        val here = TabFollow.interpolate(spans, position) ?: return null
        val desired = TabFollow.centred(here.center, viewport, maxScroll)
        if (!moving || userBrowsing) {
            val from = if (userBrowsing || restPosition == null) position else restPosition!!
            val start = TabFollow.interpolate(spans, from)?.let { TabFollow.centred(it.center, viewport, maxScroll) } ?: desired
            moveFrom = from
            displacement = scroll - start
            moving = true
        }
        val fade = 1f - abs(position - moveFrom).coerceAtMost(1f)
        val target = (desired + displacement * fade).coerceIn(0f, maxScroll.coerceAtLeast(0f))
        if (atRest) {
            restPosition = position
            moving = false
        }
        return target
    }
}
