package io.github.yuubiz.folirel.app

import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.model.ShareProblem
import io.github.yuubiz.folirel.ui.share.ShareState
import io.github.yuubiz.folirel.ui.subscriptions.SubscriptionsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * P3B-T25..T27, T30, T31, T32, T33 on the real MainActivity (Robolectric, no device): a share before first-run setup, cold start, warm start
 * (onNewIntent), a launcher tap, recreation, a repeated share and a malformed one, in one run (the app's preference store allows one
 * instance per process, so the steps share it). The shared addresses are http://127.0.0.1 ones, which the app refuses before anything is
 * sent (HTTP is off by default): the flow runs to its end without touching a network.
 *
 * Nothing here waits for a length of time. Every wait is for a state the app publishes (the share state, the readiness the composition
 * reports); while waiting, the test thread (which is the main thread of the app) runs the main looper, because that is where the
 * composition and the settings collection deliver their results.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = FolirelApp::class)
class MainActivityShareTest {
    private var controller: ActivityController<MainActivity>? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        controller?.close()
    }

    private fun send(text: String?) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
    }

    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun share(activity: MainActivity) = ViewModelProvider(activity)[SubscriptionsViewModel::class.java].share

    /**
     * Returns the first value of [flow] that satisfies [predicate]. The flow is collected off the main thread and the main looper is run
     * until the value arrives; the only timing is the deadline that fails a test that would otherwise hang.
     */
    private fun <T> await(what: String, flow: Flow<T>, predicate: (T) -> Boolean): T {
        val arrived = CompletableDeferred<T>()
        val collector = scope.launch { arrived.complete(flow.first(predicate)) }
        try {
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!arrived.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                // Returns the moment the value arrives; the short bound only gives the loop its turn to run the looper again.
                runBlocking { withTimeoutOrNull(25) { arrived.await() } }
                check(System.nanoTime() < deadline) { "not reached: $what" }
            }
            @Suppress("UNCHECKED_CAST")
            return arrived.getCompleted()
        } finally {
            collector.cancel()
        }
    }

    private fun failed(activity: MainActivity) = share(activity).state.value as ShareState.Failed

    @Test
    fun theShareFlowThroughTheActivityLifecycle() {
        // T31: first-run setup is not done. The share is received but waits; nothing is shown or looked at.
        controller = Robolectric.buildActivity(MainActivity::class.java, send("see http://127.0.0.1/feed.xml")).setup()
        var activity = controller!!.get()
        // The composition has reported that setup is not done: from here a share could only be held, never handled.
        await("composition reports setup state", share(activity).ready) { it == false }
        assertEquals(ShareState.Idle, share(activity).state.value)

        // Setup finishes: the waiting share (cold start, T25) is looked at now, and it ends in a problem a person can read (HTTP is off).
        runBlocking { ApplicationProvider.getApplicationContext<FolirelApp>().container.settings.setOnboardingDone(true) }
        await("setup done reaches the share flow", share(activity).ready) { it == true }
        await("the waiting share is handled", share(activity).state) { it is ShareState.Failed }
        val cold = failed(activity)
        assertEquals(ShareProblem.HTTP_NOT_ALLOWED, cold.problem)

        // T33: a launcher tap on the running instance is not a share and changes nothing.
        controller!!.newIntent(launcher())
        assertEquals(cold, failed(activity))

        // T26: a warm share reaches the same instance as a new request.
        controller!!.newIntent(send("nothing to see here"))
        val warm = failed(activity)
        assertEquals(ShareProblem.NO_URL, warm.problem)
        assertNotEquals(cold.id, warm.id)

        // T27: recreation (rotation, process restart with saved state) hands the old intent back; it must not be handled again.
        activity = controller!!.recreate().get()
        assertEquals("the same request, not a new one", warm, failed(activity))

        // T30: the same text shared again later is a new request.
        controller!!.newIntent(send("see http://127.0.0.1/feed.xml"))
        await("the repeated share ends", share(activity).state) { (it as? ShareState.Failed)?.problem == ShareProblem.HTTP_NOT_ALLOWED }
        assertNotEquals(cold.id, failed(activity).id)

        // T32: a share without any text is a problem message, not a crash.
        controller!!.newIntent(send(null))
        assertEquals(ShareProblem.NO_URL, failed(activity).problem)
    }
}
