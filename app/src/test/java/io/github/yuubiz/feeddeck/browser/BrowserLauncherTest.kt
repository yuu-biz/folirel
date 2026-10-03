package io.github.yuubiz.feeddeck.browser

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.feeddeck.data.preferences.BrowserMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** T32 opening branches, T33 share content. Opening never touches reading state (no DB access here at all). */
@RunWith(AndroidJUnit4::class)
class BrowserLauncherTest {
    private val launcher = BrowserLauncher()
    private val url = "https://example.com/article?id=1"

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun registerBrowser(activity: Activity, customTabs: Boolean) {
        val pm = shadowOf(activity.packageManager)
        val browser = ComponentName("com.example.browser", "com.example.browser.Main")
        pm.addActivityIfNotPresent(browser)
        pm.addIntentFilterForActivity(
            browser,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("https")
                addDataScheme("http")
            },
        )
        if (customTabs) {
            val service = ComponentName("com.example.browser", "com.example.browser.CustomTabsService")
            pm.addServiceIfNotPresent(service)
            pm.addIntentFilterForService(service, IntentFilter("android.support.customtabs.action.CustomTabsService"))
        }
    }

    @Test
    fun customTabsWhenAProviderExists() {
        val activity = activity()
        registerBrowser(activity, customTabs = true)
        assertEquals(BrowserLauncher.OpenResult.OPENED_CUSTOM_TAB, launcher.open(activity, url, BrowserMode.CUSTOM_TABS))
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
        assertEquals("com.example.browser", started.`package`)
        assertEquals(true, started.hasExtra("android.support.customtabs.extra.SESSION"))
    }

    @Test
    fun fallsBackToActionViewWithoutCustomTabs() {
        val activity = activity()
        registerBrowser(activity, customTabs = false)
        assertEquals(BrowserLauncher.OpenResult.OPENED_EXTERNAL, launcher.open(activity, url, BrowserMode.CUSTOM_TABS))
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
        assertNull(started.`package`)
        assertEquals(false, started.hasExtra("android.support.customtabs.extra.SESSION"))
    }

    @Test
    fun externalModeSkipsCustomTabs() {
        val activity = activity()
        registerBrowser(activity, customTabs = true)
        assertEquals(BrowserLauncher.OpenResult.OPENED_EXTERNAL, launcher.open(activity, url, BrowserMode.EXTERNAL))
        assertEquals(false, shadowOf(activity).nextStartedActivity.hasExtra("android.support.customtabs.extra.SESSION"))
    }

    @Test
    fun reportsWhenNoAppCanOpenTheLink() {
        val activity = activity()
        shadowOf(activity.application).checkActivities(true) // unresolvable intents throw like on a device
        assertEquals(BrowserLauncher.OpenResult.NO_BROWSER, launcher.open(activity, url, BrowserMode.CUSTOM_TABS))
    }

    @Test
    fun shareCarriesOnlyTitleAndUrl() {
        val chooser = launcher.shareIntent("日本語の見出し", url)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("日本語の見出し\n$url", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("日本語の見出し", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(send.clipData)
        assertEquals(setOf(Intent.EXTRA_TEXT, Intent.EXTRA_SUBJECT), send.extras!!.keySet())
    }
}
