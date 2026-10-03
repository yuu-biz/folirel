package io.github.yuubiz.feeddeck.ui.subscriptions

import io.github.yuubiz.feeddeck.model.DiscoveryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V3-R04: a discovery that was cancelled or replaced must not write its outcome into the state of the newer one.
 * The "slow" runs are held by a latch the test opens; nothing depends on timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryControllerTest {
    private val testScope = TestScope(StandardTestDispatcher())
    private val gates = HashMap<String, CompletableDeferred<DiscoveryResult?>>()
    private val cooperative = HashSet<String>()

    private fun result(url: String) = DiscoveryResult(url, DiscoveryResult.PageStatus.READ, emptyList(), sourceFetchUrl = url)

    private val controller = DiscoveryController(testScope) { feedId, _ ->
        val gate = gates.getValue(feedId)
        if (feedId in cooperative) gate.await() else withContext(NonCancellable) { gate.await() }
    }

    private fun done() = controller.state.value as DiscoveryState.Done

    @Test
    fun cancelledOlderRunDoesNotOverwriteTheNewerRunning() {
        gates["a"] = CompletableDeferred()
        gates["b"] = CompletableDeferred()
        cooperative += "a"
        controller.start("a")
        testScope.runCurrent()
        controller.start("b")
        testScope.runCurrent()
        // The cancelled run of A is processed now; B is still running.
        testScope.advanceUntilIdle()
        assertEquals(DiscoveryState.Running("b"), controller.state.value)
        gates.getValue("b").complete(result("https://b.example/feed"))
        testScope.advanceUntilIdle()
        assertEquals("b", done().feedId)
        assertEquals("https://b.example/feed", done().result?.pageUrl)
    }

    @Test
    fun olderRunThatIgnoresCancellationIsStillDropped() {
        gates["a"] = CompletableDeferred()
        gates["b"] = CompletableDeferred()
        controller.start("a")
        testScope.runCurrent()
        controller.start("b")
        testScope.runCurrent()
        // A finishes after B was started although it was cancelled: its result belongs to no current request.
        gates.getValue("a").complete(result("https://a.example/feed"))
        testScope.advanceUntilIdle()
        assertEquals(DiscoveryState.Running("b"), controller.state.value)
        gates.getValue("b").complete(result("https://b.example/feed"))
        testScope.advanceUntilIdle()
        assertEquals("b", done().feedId)
    }

    @Test
    fun runFinishingAfterClearDoesNotComeBack() {
        gates["a"] = CompletableDeferred()
        controller.start("a")
        testScope.runCurrent()
        controller.clear()
        gates.getValue("a").complete(result("https://a.example/feed"))
        testScope.advanceUntilIdle()
        assertEquals(DiscoveryState.Idle, controller.state.value)
    }

    @Test
    fun theSameFeedRunAgainUsesOnlyTheLatest() {
        gates["a"] = CompletableDeferred()
        controller.start("a", "https://first.example/")
        testScope.runCurrent()
        controller.start("a", "https://second.example/")
        testScope.runCurrent()
        gates.getValue("a").complete(result("https://a.example/feed"))
        testScope.advanceUntilIdle()
        assertEquals("https://second.example/", done().enteredUrl)
    }

    @Test
    fun aFailingRunEndsAsDoneWithoutResult() {
        val failing = DiscoveryController(testScope) { _, _ -> error("boom") }
        failing.start("a")
        testScope.advanceUntilIdle()
        val state = failing.state.value as DiscoveryState.Done
        assertEquals(null, state.result)
        assertTrue(state.feedId == "a")
    }
}
