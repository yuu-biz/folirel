package io.github.yuubiz.folirel.app

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.ui.theme.appColorScheme
import io.github.yuubiz.folirel.ui.theme.isDarkTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The splash lets the first frame draw as soon as the settings are read, without waiting for anything Compose does, and the window
 * behind that frame already has the colour the app's theme draws. A splash that waited for a Compose frame hung every instrumented
 * test of MainActivity on a device: a Compose test rule produces frames only while the main thread is idle, and a held-back draw
 * keeps it busy for ever. Here the window is never shown, so Compose never composes or draws; such a gate stays shut and the test
 * fails instead of hanging.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = FolirelApp::class)
class MainActivitySplashTest {
    private var controller: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        controller?.close()
    }

    @Test
    fun theFirstFrameIsLetThroughOnceTheSettingsAreReadWithTheThemeColourBehindIt() {
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller!!.get()
        val content = activity.findViewById<View>(android.R.id.content)
        val deadline = System.nanoTime() + 10_000_000_000L
        var drawHeldBack = true
        while (drawHeldBack && System.nanoTime() < deadline) {
            // Runs the settings arriving on the main thread; the bound only fails a gate that never opens.
            shadowOf(Looper.getMainLooper()).idle()
            drawHeldBack = content.viewTreeObserver.dispatchOnPreDraw()
            if (drawHeldBack) Thread.sleep(10)
        }
        assertFalse("the splash still holds back the first frame", drawHeldBack)

        val settings = runBlocking { ApplicationProvider.getApplicationContext<FolirelApp>().container.settings.current() }
        val systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val drawn = appColorScheme(activity, settings.palette, isDarkTheme(settings.theme, systemDark), settings.customSeed, settings.customLight, settings.customDark)
        assertEquals(drawn.surface.toArgb(), (activity.window.decorView.background as ColorDrawable).color)
    }
}
