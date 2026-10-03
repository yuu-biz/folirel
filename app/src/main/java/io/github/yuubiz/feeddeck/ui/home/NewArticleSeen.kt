package io.github.yuubiz.feeddeck.ui.home

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow

/**
 * New-article markers (N06 / REV-02) are consumed once the article is on screen. Which articles are on screen and which
 * are marked new both change over time and neither change implies the other: articles of a fast feed are shown before
 * the update request of a slower feed completes and marks them. Both are therefore read inside the snapshot block, so
 * a marker added later for an article that is already visible is reported as well (V3-R03).
 *
 * Emits the visible ids that are marked new, whenever either side changes; an empty list when none are.
 */
internal object NewArticleSeen {
    fun visibleNew(visibleKeys: () -> List<Any>, newIds: () -> Set<String>): Flow<List<String>> = snapshotFlow {
        val marked = newIds()
        visibleKeys().mapNotNull { key -> (key as? String)?.takeIf { it in marked } }
    }
}
