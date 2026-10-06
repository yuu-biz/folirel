package io.github.yuubiz.folirel.app

import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.model.ShareProblem
import io.github.yuubiz.folirel.ui.share.ShareState
import io.github.yuubiz.folirel.ui.subscriptions.SubscriptionsViewModel
import kotlinx.coroutines.runBlocking
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
 */
@RunWith(AndroidJUnit4::class)
@Config(application = FolirelApp::class)
class MainActivityShareTest {
    private var controller: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        controller?.close()
    }

    private fun send(text: String?) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
    }

    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun share(activity: MainActivity) = ViewModelProvider(activity)[SubscriptionsViewModel::class.java].share

    private fun idleUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < end) { "not reached: $what" }
            Thread.sleep(20)
        }
    }

    private fun failed(activity: MainActivity) = share(activity).state.value as ShareState.Failed

    @Test
    fun theShareFlowThroughTheActivityLifecycle() {
        // T31: first-run setup is not done. The share is received but waits; nothing is shown or looked at.
        controller = Robolectric.buildActivity(MainActivity::class.java, send("see http://127.0.0.1/feed.xml")).setup()
        var activity = controller!!.get()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ShareState.Idle, share(activity).state.value)

        // Setup finishes: the waiting share (cold start, T25) is looked at now, and it ends in a problem a person can read (HTTP is off).
        runBlocking { ApplicationProvider.getApplicationContext<FolirelApp>().container.settings.setOnboardingDone(true) }
        idleUntil("the waiting share is handled") { share(activity).state.value is ShareState.Failed }
        val cold = failed(activity)
        assertEquals(ShareProblem.HTTP_NOT_ALLOWED, cold.problem)

        // T33: a launcher tap on the running instance is not a share and changes nothing.
        controller!!.newIntent(launcher())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(cold, failed(activity))

        // T26: a warm share reaches the same instance as a new request.
        controller!!.newIntent(send("nothing to see here"))
        val warm = failed(activity)
        assertEquals(ShareProblem.NO_URL, warm.problem)
        assertNotEquals(cold.id, warm.id)

        // T27: recreation (rotation, process restart with saved state) hands the old intent back; it must not be handled again.
        activity = controller!!.recreate().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the same request, not a new one", warm, failed(activity))

        // T30: the same text shared again later is a new request.
        controller!!.newIntent(send("see http://127.0.0.1/feed.xml"))
        idleUntil("the repeated share ends") { (share(activity).state.value as? ShareState.Failed)?.problem == ShareProblem.HTTP_NOT_ALLOWED }
        assertNotEquals(cold.id, failed(activity).id)

        // T32: a share without any text is a problem message, not a crash.
        controller!!.newIntent(send(null))
        assertEquals(ShareProblem.NO_URL, failed(activity).problem)
    }
}
