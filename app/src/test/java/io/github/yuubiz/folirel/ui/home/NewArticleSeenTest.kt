package io.github.yuubiz.folirel.ui.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V3-R03: the banner of new articles must also clear when the marker is added after the article is already shown.
 * The state objects are driven directly; the order "article shown, then marker added" is fixed by waiting for the
 * collector's first value before the marker is set.
 */
class NewArticleSeenTest {
    private val visible = mutableStateOf<List<Any>>(listOf("a", "b", "c"))
    private val marked = mutableStateOf<Set<String>>(emptySet())

    private fun publish() = Snapshot.sendApplyNotifications()

    private fun <T> collecting(flow: Flow<T>, block: suspend (Channel<T>) -> Unit) = runBlocking {
        val out = Channel<T>(Channel.UNLIMITED)
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { flow.collect { out.send(it) } }
        try {
            block(out)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun markerAddedLaterForAnAlreadyVisibleArticleIsReported() = collecting(NewArticleSeen.visibleNew({ visible.value }, { marked.value })) { out ->
        assertEquals("shown, nothing marked yet", emptyList<String>(), withTimeout(5_000) { out.receive() })
        // The slower feed's request completes: its already visible articles get their markers. The list on screen is the same.
        marked.value = setOf("b", "off-screen")
        publish()
        assertEquals(listOf("b"), withTimeout(5_000) { out.receive() })
    }

    @Test
    fun markerOfAnArticleThatIsNotOnScreenWaitsUntilItIs() = collecting(NewArticleSeen.visibleNew({ visible.value }, { marked.value })) { out ->
        withTimeout(5_000) { out.receive() }
        marked.value = setOf("d")
        publish()
        // Nothing visible is marked: the result is still empty, and an unchanged result is not reported again.
        assertNull(withTimeoutOrNull(500) { out.receive() })
        visible.value = listOf("c", "d")
        publish()
        assertEquals(listOf("d"), withTimeout(5_000) { out.receive() })
    }

    @Test
    fun consumingTheMarkerEndsTheReports() = collecting(NewArticleSeen.visibleNew({ visible.value }, { marked.value })) { out ->
        withTimeout(5_000) { out.receive() }
        marked.value = setOf("a")
        publish()
        assertEquals(listOf("a"), withTimeout(5_000) { out.receive() })
        marked.value = emptySet() // what markNewSeen does
        publish()
        assertEquals(emptyList<String>(), withTimeout(5_000) { out.receive() })
    }

    @Test
    fun theFormerShapeMissedTheLateMarker() = collecting(
        // The shape before the fix: only the visible ids are read inside the snapshot block; the markers are read afterwards.
        snapshotFlow { visible.value.mapNotNull { it as? String } }.map { shown -> shown.filter { it in marked.value } },
    ) { out ->
        withTimeout(5_000) { out.receive() }
        marked.value = setOf("b")
        publish()
        assertNull("no value: the marker change was not observed", withTimeoutOrNull(500) { out.receive() })
    }
}
