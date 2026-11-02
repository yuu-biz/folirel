package io.github.yuubiz.folirel.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Scroll position of the article list, the same operations for the List/Compact list column, the Magazine grid, the Masonry lanes and the Bento blocks (positions are article indexes). */
internal interface ScrollHandle {
    /** The underlying Compose state object; effects restart when it changes. */
    val owner: Any
    val firstIndex: Int
    val firstOffset: Int
    val viewportPx: Int

    /** How far, in px, the list start is from the current position: exact once the first row is on screen, an estimate before. */
    fun distanceToStart(): Float

    /** The index to jump to so that about [px] remain to the start. */
    fun indexAt(px: Float): Int

    suspend fun scrollTo(index: Int)

    /** The next measure keeps the first index and offset as they are, instead of following the first row's key to its new index. */
    fun keepIndex()

    suspend fun scroll(block: suspend ScrollScope.() -> Unit)
}

internal class ListScroll(private val s: LazyListState) : ScrollHandle {
    override val owner: Any get() = s
    override val firstIndex: Int get() = s.firstVisibleItemIndex
    override val firstOffset: Int get() = s.firstVisibleItemScrollOffset
    override val viewportPx: Int get() = s.layoutInfo.viewportSize.height

    private fun averageItemPx(): Float {
        val items = s.layoutInfo.visibleItemsInfo
        return if (items.isEmpty()) 0f else items.sumOf { it.size }.toFloat() / items.size
    }

    override fun distanceToStart(): Float = firstOffset + firstIndex * averageItemPx()

    override fun indexAt(px: Float): Int {
        val avg = averageItemPx()
        return if (avg <= 0f) 0 else ceil(px / avg).toInt().coerceAtLeast(0)
    }

    override suspend fun scrollTo(index: Int) = s.scrollToItem(index)
    override fun keepIndex() = s.requestScrollToItem(s.firstVisibleItemIndex, s.firstVisibleItemScrollOffset)
    override suspend fun scroll(block: suspend ScrollScope.() -> Unit) = s.scroll(block = block)
}

internal class GridScroll(private val s: LazyGridState) : ScrollHandle {
    override val owner: Any get() = s
    override val firstIndex: Int get() = s.firstVisibleItemIndex
    override val firstOffset: Int get() = s.firstVisibleItemScrollOffset
    override val viewportPx: Int get() = s.layoutInfo.viewportSize.height

    /** Px a row takes with its gap, and cards per row, as seen on the screen now. */
    private fun rowShape(): Pair<Float, Float> {
        val items = s.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return 0f to 1f
        val rows = items.map { it.row }.toSet().size.coerceAtLeast(1)
        val top = items.minOf { it.offset.y }
        val bottom = items.maxOf { it.offset.y + it.size.height }
        val pitch = (bottom - top + s.layoutInfo.mainAxisItemSpacing).toFloat() / rows
        return pitch to items.size.toFloat() / rows
    }

    override fun distanceToStart(): Float {
        if (firstIndex == 0) return firstOffset.toFloat()
        val (pitch, perRow) = rowShape()
        return firstOffset + firstIndex / perRow * pitch
    }

    override fun indexAt(px: Float): Int {
        val (pitch, perRow) = rowShape()
        return if (pitch <= 0f) 0 else ceil(px / pitch * perRow).toInt().coerceAtLeast(0)
    }

    override suspend fun scrollTo(index: Int) = s.scrollToItem(index)
    override fun keepIndex() = s.requestScrollToItem(s.firstVisibleItemIndex, s.firstVisibleItemScrollOffset)
    override suspend fun scroll(block: suspend ScrollScope.() -> Unit) = s.scroll(block = block)
}

internal class StaggeredScroll(private val s: LazyStaggeredGridState) : ScrollHandle {
    override val owner: Any get() = s
    override val firstIndex: Int get() = s.firstVisibleItemIndex
    override val firstOffset: Int get() = s.firstVisibleItemScrollOffset
    override val viewportPx: Int get() = s.layoutInfo.viewportSize.height

    /** Px one card takes with its gap, and lanes, as seen on the screen now. */
    private fun shape(): Pair<Float, Float> {
        val items = s.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return 0f to 1f
        val lanes = items.map { it.lane }.toSet().size.coerceAtLeast(1)
        val pitch = items.sumOf { it.size.height + s.layoutInfo.mainAxisItemSpacing }.toFloat() / items.size
        return pitch to lanes.toFloat()
    }

    override fun distanceToStart(): Float {
        if (firstIndex == 0) return firstOffset.toFloat()
        val (pitch, lanes) = shape()
        return firstOffset + firstIndex / lanes * pitch
    }

    override fun indexAt(px: Float): Int {
        val (pitch, lanes) = shape()
        return if (pitch <= 0f) 0 else ceil(px / pitch * lanes).toInt().coerceAtLeast(0)
    }

    override suspend fun scrollTo(index: Int) = s.scrollToItem(index)
    override fun keepIndex() = s.requestScrollToItem(s.firstVisibleItemIndex, s.firstVisibleItemScrollOffset)
    override suspend fun scroll(block: suspend ScrollScope.() -> Unit) = s.scroll(block = block)
}

/**
 * The Bento layout is a column of blocks (each block holds a few articles). Positions are kept and asked for by article, so this
 * turns a block of the column into its first article and an article into its block.
 */
internal class BlockScroll(
    private val s: LazyListState,
    private val blockOf: IntArray,
    private val firstArticleOf: IntArray,
) : ScrollHandle {
    override val owner: Any get() = s
    override val firstIndex: Int get() = firstArticleOf.getOrElse(s.firstVisibleItemIndex) { 0 }
    override val firstOffset: Int get() = s.firstVisibleItemScrollOffset
    override val viewportPx: Int get() = s.layoutInfo.viewportSize.height

    private fun averageBlockPx(): Float {
        val items = s.layoutInfo.visibleItemsInfo
        return if (items.isEmpty()) 0f else items.sumOf { it.size + s.layoutInfo.mainAxisItemSpacing }.toFloat() / items.size
    }

    override fun distanceToStart(): Float = s.firstVisibleItemScrollOffset + s.firstVisibleItemIndex * averageBlockPx()

    override fun indexAt(px: Float): Int {
        val avg = averageBlockPx()
        val block = if (avg <= 0f) 0 else ceil(px / avg).toInt().coerceIn(0, (firstArticleOf.size - 1).coerceAtLeast(0))
        return firstArticleOf.getOrElse(block) { 0 }
    }

    override suspend fun scrollTo(index: Int) = s.scrollToItem(blockOf.getOrElse(index) { 0 })
    override fun keepIndex() = s.requestScrollToItem(s.firstVisibleItemIndex, s.firstVisibleItemScrollOffset)
    override suspend fun scroll(block: suspend ScrollScope.() -> Unit) = s.scroll(block = block)
}

/** The time back to the start grows with the distance, within the range a reader accepts as one quick movement. */
internal fun backToTopMillis(screens: Float): Int =
    (BACK_TO_TOP_MIN_MS + BACK_TO_TOP_PER_SCREEN_MS * screens.coerceIn(0f, MAX_SCREENS_ANIMATED)).roundToInt().coerceAtMost(BACK_TO_TOP_MAX_MS)

private const val BACK_TO_TOP_MIN_MS = 240f
private const val BACK_TO_TOP_PER_SCREEN_MS = 100f
private const val BACK_TO_TOP_MAX_MS = 650

/** What is shown moving; further away the list skips once, to this distance from the start, and moves the rest. */
private const val MAX_SCREENS_ANIMATED = 2.5f

private const val NOMINAL_FRAME_NANOS = 16_000_000L

/**
 * Back to the start of the list as ONE movement (H01). A far list skips once to a short way from the start; from there
 * every frame moves the list by the share of the distance that is still left which an ease-in-out curve over the whole
 * time asks for. The distance left is measured again every frame, so a wrong guess about the rows not yet on screen
 * (they have other heights than the ones seen) bends the speed a little instead of ending the movement a few rows short
 * and starting another one: the list neither stops nor starts again on its way. It lands exactly on the start.
 *
 * Nothing else moves the list meanwhile: it holds the scroll lock, a drag of the user takes it over (and this ends).
 * With animations switched off in the system the list goes there at once.
 */
internal suspend fun ScrollHandle.glideToStart() {
    val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    if (scale == 0f || viewportPx <= 0) {
        scrollTo(0)
        return
    }
    val viewport = viewportPx.toFloat()
    if (distanceToStart() > MAX_SCREENS_ANIMATED * viewport) scrollTo(indexAt(MAX_SCREENS_ANIMATED * viewport).coerceAtMost(firstIndex))
    val screens = distanceToStart() / viewport
    val durationNanos = backToTopMillis(screens) * scale * 1_000_000f
    scroll {
        var startedAt = -1L
        var easedBefore = 0f
        var finished = false
        while (!finished && distanceToStart() > 0.5f) {
            withFrameNanos { now ->
                // The first frame is already a step of the movement (as if it had begun one frame ago), not a wait: a frame that
                // does not move right after the skip would be a hitch.
                if (startedAt < 0) startedAt = now - NOMINAL_FRAME_NANOS
                val eased = FastOutSlowInEasing.transform(((now - startedAt) / durationNanos).coerceIn(0f, 1f))
                finished = eased >= 1f
                val share = if (finished) 1f else ((eased - easedBefore) / (1f - easedBefore)).coerceIn(0f, 1f)
                easedBefore = eased
                val step = distanceToStart() * share
                if (step > 0f) scrollBy(-step)
            }
        }
    }
    // The estimate of what is left only ever rounds; the start itself is reached exactly.
    if (firstIndex != 0 || abs(firstOffset) > 0) scrollTo(0)
}

/**
 * The one movement of a list that is in progress. Back to top (arrow, tab, banner) and the jump of the banner all go
 * through here: a request for the movement that is already running joins it, any other request ends the one before
 * it, so two movements never fight for the scroll position, and the user touching the list ends it ([cancel]).
 */
internal class ListMover(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var kind: Any? = null

    fun launch(kind: Any, block: suspend () -> Unit) {
        if (job?.isActive == true && this.kind == kind) return
        job?.cancel()
        this.kind = kind
        job = scope.launch { block() }
    }

    fun cancel() {
        job?.cancel()
        job = null
        kind = null
    }
}

/**
 * Where the buttons that ask for a movement (the arrow, the new-article notice) are, in the box of the list. A press on one
 * of them is the request itself and must not be taken for a touch of the list: that would end the movement before the
 * button's own click is seen, so asking for the same movement again would stop it and start it over. A press anywhere else
 * on the list (a card, its buttons, empty space) still ends the movement.
 */
internal class ControlBounds {
    var arrow: Rect? = null
    var banner: Rect? = null

    fun contains(point: Offset): Boolean = arrow?.contains(point) == true || banner?.contains(point) == true
}
