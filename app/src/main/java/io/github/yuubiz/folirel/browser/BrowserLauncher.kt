package io.github.yuubiz.folirel.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import io.github.yuubiz.folirel.data.preferences.BrowserMode
import io.github.yuubiz.folirel.network.PublicAddresses
import io.github.yuubiz.folirel.network.UrlNormalizer
import java.net.InetAddress

/**
 * Opens the original article outside the app: Custom Tabs by default, ACTION_VIEW as fallback or when the
 * user prefers an external browser. Never marks anything as read. No WebView, no cookie access.
 */
class BrowserLauncher {
    sealed interface LinkCheck {
        data class Ok(val url: String, val isHttps: Boolean) : LinkCheck
        data object Missing : LinkCheck
        data object Unsafe : LinkCheck
    }

    enum class OpenResult { OPENED_CUSTOM_TAB, OPENED_EXTERNAL, NO_BROWSER }

    fun check(rawUrl: String?): LinkCheck {
        if (rawUrl.isNullOrBlank()) return LinkCheck.Missing
        val result = UrlNormalizer.normalize(rawUrl) as? UrlNormalizer.Result.Valid ?: return LinkCheck.Unsafe
        val host = result.url.toUri().host.orEmpty().trim('[', ']')
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost")) return LinkCheck.Unsafe
        if (IP_LITERAL.matches(host)) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return LinkCheck.Unsafe
            if (!PublicAddresses.isPublic(address)) return LinkCheck.Unsafe
        }
        return LinkCheck.Ok(result.url, result.isHttps)
    }

    /** [url] must come from [check]. */
    fun open(context: Context, url: String, mode: BrowserMode): OpenResult {
        val uri = url.toUri()
        if (mode == BrowserMode.CUSTOM_TABS) {
            val provider = CustomTabsClient.getPackageName(context, emptyList())
            if (provider != null) {
                val customTabs = CustomTabsIntent.Builder().setShowTitle(true).build()
                customTabs.intent.setPackage(provider)
                if (context !is android.app.Activity) customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    customTabs.launchUrl(context, uri)
                    return OpenResult.OPENED_CUSTOM_TAB
                } catch (_: ActivityNotFoundException) {
                    // fall through to ACTION_VIEW
                }
            }
        }
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is android.app.Activity) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(view)
            OpenResult.OPENED_EXTERNAL
        } catch (_: ActivityNotFoundException) {
            OpenResult.NO_BROWSER
        }
    }

    /** Shares exactly one article's title and URL through the system share sheet. */
    fun shareIntent(title: String, url: String?): Intent {
        val text = listOfNotNull(title.takeIf { it.isNotBlank() }, url).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        return Intent.createChooser(send, null)
    }

    private companion object {
        val IP_LITERAL = Regex("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)")
    }
}
