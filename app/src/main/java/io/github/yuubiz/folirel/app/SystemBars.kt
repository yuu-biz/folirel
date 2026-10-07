package io.github.yuubiz.folirel.app

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Edge to edge with status and navigation bar icons that match the app's own light/dark choice. The default of [enableEdgeToEdge]
 * follows the system night mode, so with Dark chosen on a light system the icons were dark on the app's dark bars (and the
 * other way round). The scrims are the defaults of [enableEdgeToEdge].
 */
fun ComponentActivity.applySystemBars(dark: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
    )
}

private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
