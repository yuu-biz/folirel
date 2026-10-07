package io.github.yuubiz.folirel.app

import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** The bar icons follow the app's light/dark choice, not the system night mode (Dark chosen on a light system and the reverse). */
@RunWith(AndroidJUnit4::class)
class SystemBarsTest {
    private fun lightIcons(appDark: Boolean): Pair<Boolean, Boolean> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.applySystemBars(appDark)
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            return insets.isAppearanceLightStatusBars to insets.isAppearanceLightNavigationBars
        } finally {
            controller.close()
        }
    }

    @Test
    @Config(qualifiers = "notnight")
    fun darkChosenOnALightSystemGetsLightIcons() {
        assertEquals(false to false, lightIcons(appDark = true))
        assertEquals(true to true, lightIcons(appDark = false))
    }

    @Test
    @Config(qualifiers = "night")
    fun lightChosenOnADarkSystemGetsDarkIcons() {
        assertEquals(true to true, lightIcons(appDark = false))
        assertEquals(false to false, lightIcons(appDark = true))
    }
}
