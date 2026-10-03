package io.github.yuubiz.feeddeck.ui.home

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

/**
 * Decoded source icons for the UI. The stored PNGs are small (at most 96 px); decoded bitmaps are cached by
 * feed id and version (the time the icon was resolved), so scrolling never decodes or loads anything twice and a
 * re-resolved icon replaces the old one.
 */
class FeedIconStore(private val loadPng: suspend (feedId: String) -> ByteArray?) {
    private val cache = LruCache<String, ImageBitmap>(CACHE_ENTRIES)

    fun cached(feedId: String, version: Long): ImageBitmap? = cache.get(key(feedId, version))

    suspend fun bitmap(feedId: String, version: Long): ImageBitmap? {
        cached(feedId, version)?.let { return it }
        return withContext(Dispatchers.Default) {
            val png = loadPng(feedId) ?: return@withContext null
            val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() ?: return@withContext null
            cache.put(key(feedId, version), decoded)
            decoded
        }
    }

    private fun key(feedId: String, version: Long) = "$feedId:$version"

    private companion object {
        const val CACHE_ENTRIES = 256
    }
}

private class StoreIconSource(private val versions: Map<String, Long>, private val store: FeedIconStore) : FeedIconSource {
    @Composable
    override fun bitmapFor(feedId: String): ImageBitmap? {
        val version = versions[feedId] ?: return null
        val state = produceState(store.cached(feedId, version), feedId, version) { value = store.bitmap(feedId, version) }
        return state.value
    }
}

/**
 * Makes the stored icons available to [SourceIcon] below. [enabled] false (images off) provides nothing, so no
 * icon is drawn and nothing is loaded. Bursts of new icons are batched so the lists do not redraw per icon.
 */
@OptIn(FlowPreview::class)
@Composable
fun ProvideFeedIcons(store: FeedIconStore, versions: Flow<Map<String, Long>>, enabled: Boolean, content: @Composable () -> Unit) {
    val debounced = remember(versions) { versions.debounce(ICON_BATCH_MS) }
    val map by debounced.collectAsStateWithLifecycle(emptyMap())
    val source = remember(map, store) { StoreIconSource(map, store) }
    CompositionLocalProvider(LocalFeedIcons provides if (enabled) source else null, content = content)
}

private const val ICON_BATCH_MS = 400L
