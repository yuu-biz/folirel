package io.github.yuubiz.folirel.ui.share

import io.github.yuubiz.folirel.data.repository.AddFeedResult
import io.github.yuubiz.folirel.model.ExistingFeed
import io.github.yuubiz.folirel.model.ShareCandidate
import io.github.yuubiz.folirel.model.ShareDiscovery
import io.github.yuubiz.folirel.model.ShareProblem
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
 * P3B-T18, T19, T20, T30, T31: the state machine behind Share to Subscribe. Nothing is registered by receiving a share; only the latest
 * request may write the state. Slow runs are held by latches the test opens, so nothing depends on timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareSubscriptionControllerTest {
    private val testScope = TestScope(StandardTestDispatcher())
    private val gates = HashMap<String, CompletableDeferred<ShareDiscovery>>()
    private val discovered = ArrayList<String>()
    private val registered = ArrayList<String>()
    private var registerResult: AddFeedResult? = AddFeedResult.Added("new-feed")
    private var registerHeld = false
    private var pendingRegistration: Pair<(AddFeedResult) -> Unit, () -> Unit>? = null
    private val uncooperative = HashSet<String>()

    private fun candidate(url: String, existing: ExistingFeed? = null) =
        ShareCandidate(url, url, "Title of $url", "https://site.example/", 3, null, null, existing)

    private fun found(vararg urls: String) = ShareDiscovery.Found("https://page.example/", urls.map { candidate(it) })

    private val controller = ShareSubscriptionController(
        testScope,
        discover = { url ->
            discovered += url
            val gate = gates.getValue(url)
            if (url in uncooperative) withContext(NonCancellable) { gate.await() } else gate.await()
        },
        register = { url, onDone, onFailed ->
            registered += url
            if (registerHeld) {
                pendingRegistration = onDone to onFailed
            } else {
                registerResult?.let(onDone) ?: onFailed()
            }
        },
    )

    private fun gate(url: String) = CompletableDeferred<ShareDiscovery>().also { gates[url] = it }
    private fun state() = controller.state.value

    @Test
    fun receivingASingleAddressLooksButRegistersNothing() {
        val g = gate("https://example.com/")
        controller.setReady(true)
        controller.receive("memo\nhttps://example.com/")
        testScope.runCurrent()
        assertTrue(state() is ShareState.Discovering)
        g.complete(found("https://example.com/feed.xml"))
        testScope.advanceUntilIdle()
        val s = state() as ShareState.Candidates
        assertEquals(1, s.candidates.size)
        // One candidate is still only shown: nothing was handed to the add path.
        assertEquals(emptyList<String>(), registered)
    }

    @Test
    fun severalAddressesWaitForTheUsersChoiceAndNothingIsFetchedMeanwhile() {
        controller.setReady(true)
        controller.receive("https://a.example/ https://b.example/")
        testScope.advanceUntilIdle()
        assertEquals(listOf("https://a.example/", "https://b.example/"), (state() as ShareState.ChoosingUrl).urls)
        assertEquals(emptyList<String>(), discovered)
        // Something that was not offered cannot be chosen.
        controller.choose("https://evil.example/")
        assertTrue(state() is ShareState.ChoosingUrl)
        gate("https://b.example/").complete(found("https://b.example/rss"))
        controller.choose("https://b.example/")
        testScope.advanceUntilIdle()
        assertEquals(listOf("https://b.example/"), discovered)
        assertTrue(state() is ShareState.Candidates)
    }

    @Test
    fun confirmingRegistersExactlyOnceThroughTheAddPathAndEndsAsDone() {
        gate("https://example.com/").complete(found("https://example.com/feed.xml"))
        controller.setReady(true)
        controller.receive("https://example.com/")
        testScope.advanceUntilIdle()
        val c = (state() as ShareState.Candidates).candidates.single()
        controller.subscribe(c)
        controller.subscribe(c) // a second tap changes nothing
        assertEquals(listOf("https://example.com/feed.xml"), registered)
        assertEquals(AddFeedResult.Added("new-feed"), (state() as ShareState.Done).result)
        controller.acknowledge()
        assertEquals(ShareState.Idle, state())
    }

    @Test
    fun aFeedThatIsAlreadySubscribedIsNeverRegisteredAgain() {
        val existing = ExistingFeed("f1", "Mine", subscribed = true)
        gate("https://example.com/").complete(ShareDiscovery.Found(null, listOf(candidate("https://example.com/feed.xml", existing))))
        controller.setReady(true)
        controller.receive("https://example.com/")
        testScope.advanceUntilIdle()
        controller.subscribe((state() as ShareState.Candidates).candidates.single())
        assertEquals(emptyList<String>(), registered)
        assertTrue(state() is ShareState.Candidates)
    }

    @Test
    fun anUnsubscribedFeedIsTakenAgainOnlyOnAnExplicitConfirmation() {
        val existing = ExistingFeed("f1", "Mine", subscribed = false)
        gate("https://example.com/").complete(ShareDiscovery.Found(null, listOf(candidate("https://example.com/feed.xml", existing))))
        controller.setReady(true)
        controller.receive("https://example.com/")
        testScope.advanceUntilIdle()
        assertEquals(emptyList<String>(), registered)
        controller.subscribe((state() as ShareState.Candidates).candidates.single())
        assertEquals(listOf("https://example.com/feed.xml"), registered)
    }

    @Test
    fun aCandidateThatWasNotShownCannotBeRegistered() {
        gate("https://example.com/").complete(found("https://example.com/feed.xml"))
        controller.setReady(true)
        controller.receive("https://example.com/")
        testScope.advanceUntilIdle()
        controller.subscribe(candidate("https://other.example/feed.xml"))
        assertEquals(emptyList<String>(), registered)
    }

    @Test
    fun aFailedRegistrationEndsAsAProblemNotAsASuccess() {
        registerResult = null
        gate("https://example.com/").complete(found("https://example.com/feed.xml"))
        controller.setReady(true)
        controller.receive("https://example.com/")
        testScope.advanceUntilIdle()
        controller.subscribe((state() as ShareState.Candidates).candidates.single())
        assertEquals(ShareProblem.FAILED, (state() as ShareState.Failed).problem)
    }

    @Test
    fun cancelDuringTheSearchGoesBackAndALateResultIsDropped() {
        val g = gate("https://example.com/")
        uncooperative += "https://example.com/"
        controller.setReady(true)
        controller.receive("https://example.com/")
        testScope.runCurrent()
        controller.cancel()
        assertEquals(ShareState.Idle, state())
        g.complete(found("https://example.com/feed.xml"))
        testScope.advanceUntilIdle()
        assertEquals("a result that arrives after Cancel does not bring the dialog back", ShareState.Idle, state())
        assertEquals(emptyList<String>(), registered)
    }

    @Test
    fun aNewerShareIsNotOverwrittenByAnOlderRunThatIgnoresCancellation() {
        val old = gate("https://old.example/")
        uncooperative += "https://old.example/"
        val new = gate("https://new.example/")
        controller.setReady(true)
        controller.receive("https://old.example/")
        testScope.runCurrent()
        controller.receive("https://new.example/")
        testScope.runCurrent()
        old.complete(found("https://old.example/feed.xml"))
        testScope.advanceUntilIdle()
        assertEquals("https://new.example/", (state() as ShareState.Discovering).url)
        new.complete(found("https://new.example/feed.xml"))
        testScope.advanceUntilIdle()
        assertEquals("https://new.example/feed.xml", (state() as ShareState.Candidates).candidates.single().url)
    }

    @Test
    fun sharingTheSameTextAgainLaterIsANewRequest() {
        gate("https://example.com/").complete(found("https://example.com/feed.xml"))
        controller.setReady(true)
        controller.receive("https://example.com/")
        testScope.advanceUntilIdle()
        val first = (state() as ShareState.Candidates).id
        controller.cancel()
        controller.receive("https://example.com/")
        testScope.advanceUntilIdle()
        assertTrue((state() as ShareState.Candidates).id != first)
        assertEquals(listOf("https://example.com/", "https://example.com/"), discovered)
    }

    @Test
    fun textWithoutAnAddressOrTooLargeIsAProblemNotACrash() {
        controller.setReady(true)
        controller.receive("no address here")
        assertEquals(ShareProblem.NO_URL, (state() as ShareState.Failed).problem)
        controller.receive(null)
        assertEquals(ShareProblem.NO_URL, (state() as ShareState.Failed).problem)
        controller.receive("x".repeat(100_000))
        assertEquals(ShareProblem.TEXT_TOO_LARGE, (state() as ShareState.Failed).problem)
        assertEquals(emptyList<String>(), discovered)
    }

    @Test
    fun aShareBeforeSetupIsFinishedWaitsAndIsNotLostOrSkipped() {
        gate("https://example.com/").complete(found("https://example.com/feed.xml"))
        controller.receive("https://example.com/")
        testScope.advanceUntilIdle()
        assertEquals("nothing is read or shown during first-run setup", ShareState.Idle, state())
        assertEquals(emptyList<String>(), discovered)
        controller.setReady(true)
        testScope.advanceUntilIdle()
        assertTrue(state() is ShareState.Candidates)
        // Setup being finished once does not replay the same share.
        controller.cancel()
        controller.setReady(false)
        controller.setReady(true)
        assertEquals(ShareState.Idle, state())
    }

    @Test
    fun cancelBeforeSetupIsFinishedDropsTheWaitingShare() {
        controller.receive("https://example.com/")
        controller.cancel()
        controller.setReady(true)
        assertEquals(ShareState.Idle, state())
        assertEquals(emptyList<String>(), discovered)
    }

    @Test
    fun aRegistrationInProgressIsNotCancelled() {
        gate("https://example.com/").complete(found("https://example.com/feed.xml"))
        registerHeld = true
        controller.setReady(true)
        controller.receive("https://example.com/")
        testScope.advanceUntilIdle()
        controller.subscribe((state() as ShareState.Candidates).candidates.single())
        controller.cancel()
        assertTrue(state() is ShareState.Subscribing)
        pendingRegistration!!.first(AddFeedResult.Added("x"))
        assertTrue(state() is ShareState.Done)
    }

    @Test
    fun aRegistrationResultOfAReplacedRequestIsIgnored() {
        gate("https://example.com/").complete(found("https://example.com/feed.xml"))
        registerHeld = true
        controller.setReady(true)
        controller.receive("https://example.com/")
        testScope.advanceUntilIdle()
        controller.subscribe((state() as ShareState.Candidates).candidates.single())
        controller.receive("nothing") // a newer share replaces the dialog while the write is in flight
        pendingRegistration!!.first(AddFeedResult.Added("x"))
        assertEquals(ShareProblem.NO_URL, (state() as ShareState.Failed).problem)
    }

    @Test
    fun anExceptionInTheSearchBecomesAProblemWithoutItsText() {
        val c = ShareSubscriptionController(testScope, discover = { error("boom: secret details") }, register = { _, _, f -> f() })
        c.setReady(true)
        c.receive("https://example.com/")
        testScope.advanceUntilIdle()
        assertEquals(ShareProblem.FAILED, (c.state.value as ShareState.Failed).problem)
    }
}
