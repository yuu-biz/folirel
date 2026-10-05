package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.Structure
import kotlin.math.roundToInt

/** Narrowest tab underline: a one-letter title still gets a visible bar. */
private val TAB_INDICATOR_MIN_WIDTH = 24.dp
private val TAB_INDICATOR_HEIGHT = 3.dp
private val TAB_EDGE_PADDING = 8.dp

/** One tab: what the scope is called, and the key its place in the row is measured under (never its index). */
internal data class TabItem(val scope: ArticleScope, val label: String) {
    val key: String get() = scope.encode()
}

@Composable
internal fun tabItems(structure: Structure, pages: List<ArticleScope>): List<TabItem> {
    val all = stringResource(R.string.tab_all)
    val uncategorized = stringResource(R.string.scope_uncategorized)
    return pages.map { scope ->
        TabItem(
            scope,
            when (scope) {
                ArticleScope.All -> all
                ArticleScope.Uncategorized -> uncategorized
                is ArticleScope.Category -> structure.categories.firstOrNull { it.id == scope.categoryId }?.display.orEmpty()
                is ArticleScope.Feed -> ""
            },
        )
    }
}

/**
 * The row of category tabs. It moves with the pager from the first pixel of a drag (P1-NAV): the title and the underline follow
 * `currentPage + currentPageOffsetFraction`, so nothing waits for the page to settle. Three things are kept apart:
 * the position the pager is drawn at (what the row follows), the category the reader chose (the highlighted [selected] scope,
 * set at once by a tap) and the page the pager settled on (what a swipe commits).
 *
 * The row scrolls by hand like any scroll container; the follower never fights a finger on it. Titles are measured in the
 * content of the row (not on the screen), keyed by scope, so scrolling does not feed back into the measurement.
 */
@Composable
internal fun ScopeTabs(
    tabs: List<TabItem>,
    selected: ArticleScope,
    pagerState: PagerState,
    onSelect: (ArticleScope) -> Unit,
    onReselect: () -> Unit,
) {
    // The highlighted tab follows the pager while it is dragged or animated.
    val visual = pagerState.currentPage.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val scroll = rememberScrollState()
    val spans = remember { mutableStateMapOf<String, TitleSpan>() }
    var viewport by remember { mutableStateOf(0) }
    val follower = remember { TabFollower() }
    val barColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val keys = tabs.map { it.key }

    fun orderedSpans(): List<TitleSpan?> = keys.map { spans[it] }

    FollowEffects(pagerState, scroll, follower, keys, spans, { viewport }, density.fontScale)

    Box(Modifier.fillMaxWidth().clipToBounds().onSizeChanged { viewport = it.width }.testTag("tab_strip")) {
        Row(Modifier.horizontalScroll(scroll).testTag("tab_row_scroll"), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(TAB_EDGE_PADDING))
            tabs.forEachIndexed { index, item ->
                Tab(
                    selected = index == visual,
                    onClick = {
                        // Only the selected category settles: a tab pressed again once its page is on screen goes back to the top.
                        // While the pager is still moving toward it, the press is no new request.
                        val settledHere = item.scope == selected && pagerState.settledPage == index && !pagerState.isScrollInProgress
                        when {
                            settledHere -> onReselect()
                            item.scope != selected -> onSelect(item.scope)
                        }
                    },
                    text = {
                        Text(
                            item.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.onSizeChanged { size ->
                                val old = spans[item.key]
                                if (old == null || old.width != size.width.toFloat()) {
                                    spans[item.key] = TitleSpan(old?.center ?: 0f, size.width.toFloat())
                                }
                            },
                        )
                    },
                    // A tab is at least 48dp each way; where it sits in the content is what the row is aimed with (the title is
                    // centred in it).
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp)
                        .onPlaced { coordinates ->
                            val centre = coordinates.positionInParent().x + coordinates.size.width / 2f
                            val old = spans[item.key]
                            if (old == null || old.center != centre) spans[item.key] = TitleSpan(centre, old?.width ?: 0f)
                        }
                        .testTag("tab_${item.key}"),
                )
            }
            Spacer(Modifier.width(TAB_EDGE_PADDING))
        }
        // The underline: centred on the title, as wide as it (at least 24dp), between two titles with the pager's position.
        // Read in the layout phase, so it is placed with the frame that moved the pager.
        fun Density.bar(): Pair<Int, Int>? {
            val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val title = TabFollow.interpolate(orderedSpans(), position) ?: return null
            val width = maxOf(title.width, TAB_INDICATOR_MIN_WIDTH.toPx())
            return (title.center - width / 2f - scroll.value).roundToInt() to width.roundToInt()
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .layout { measurable, constraints ->
                    val bar = bar()
                    val p = measurable.measure(constraints.copy(minWidth = bar?.second ?: 0, maxWidth = bar?.second ?: 0))
                    layout(p.width, p.height) { p.place(0, 0) }
                }
                .offset { IntOffset(bar()?.first ?: 0, 0) }
                .height(TAB_INDICATOR_HEIGHT)
                .background(barColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .testTag("tab_indicator"),
        )
    }
}

/**
 * Moves the scroll of the row with the pager. One writer only: the row's own auto-centring is not used, and a movement is never
 * restarted frame by frame with an animation; the scroll is set directly to the value for the frame's position.
 */
@Composable
private fun FollowEffects(
    pagerState: PagerState,
    scroll: androidx.compose.foundation.ScrollState,
    follower: TabFollower,
    keys: List<String>,
    spans: Map<String, TitleSpan>,
    viewport: () -> Int,
    fontScale: Float,
) {
    fun spanList(): List<TitleSpan?> = keys.map { spans[it] }
    fun moveTo(target: Float) {
        val delta = target - scroll.value
        if (kotlin.math.abs(delta) >= 0.5f) scroll.dispatchRawDelta(delta)
    }
    // The geometry changed (first layout, other titles, text size, window width): put the selected title in the middle, once.
    // It is not repeated while the reader browses the row by hand: the measures do not change then.
    LaunchedEffect(keys, fontScale) {
        snapshotFlow {
            Triple(viewport(), scroll.maxValue, spanList().map { it?.copy() })
        }.collect { (width, max, list) ->
            if (width <= 0 || list.any { it == null || it.width <= 0f }) return@collect
            if (pagerState.isScrollInProgress || scroll.isScrollInProgress) return@collect
            val page = pagerState.currentPage.toFloat()
            val here = TabFollow.interpolate(list, page) ?: return@collect
            moveTo(TabFollow.centred(here.center, width.toFloat(), max.toFloat()))
            follower.settleAt(page)
        }
    }
    // Every position of the pager: the row follows. The collector reads the pager only; frames in which nothing moved cost nothing.
    LaunchedEffect(keys) {
        snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }.collect { position ->
            val list = spanList()
            val width = viewport()
            if (width <= 0 || list.any { it == null || it.width <= 0f }) return@collect
            if (scroll.isScrollInProgress) {
                // The reader's finger (or its fling) has the row: follow from where it is when the pager moves, never pull it.
                follower.scrollFor(position, atRest = false, userBrowsing = true, scroll.value.toFloat(), list, width.toFloat(), scroll.maxValue.toFloat())
                return@collect
            }
            val atRest = !pagerState.isScrollInProgress && pagerState.currentPageOffsetFraction == 0f
            val target = follower.scrollFor(position, atRest, userBrowsing = false, scroll.value.toFloat(), list, width.toFloat(), scroll.maxValue.toFloat())
            if (target != null) moveTo(target)
        }
    }
}
