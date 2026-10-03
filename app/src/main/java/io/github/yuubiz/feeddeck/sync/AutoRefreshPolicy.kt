package io.github.yuubiz.feeddeck.sync

import io.github.yuubiz.feeddeck.data.preferences.AppSettings
import io.github.yuubiz.feeddeck.network.NetworkState

/**
 * Rules for automatic (startup and periodic) refresh. Manual refresh does not use them.
 * - nothing automatic before onboarding, or when automatic update is off
 * - the unmetered-only constraint applies to startup refresh too (opening the app does not bypass it)
 * - startup refresh at most once per 30 minutes, measured from the last automatic attempt (not success)
 */
object AutoRefreshPolicy {
    const val STARTUP_MIN_INTERVAL_MS = 30L * 60 * 1000

    fun networkAllowed(settings: AppSettings, network: NetworkState): Boolean =
        settings.onboardingDone && settings.refreshIntervalHours > 0 && network.connected && (!settings.unmeteredOnly || !network.metered)

    fun startupDue(nowMillis: Long, lastAutomaticAttemptAt: Long): Boolean =
        nowMillis - lastAutomaticAttemptAt >= STARTUP_MIN_INTERVAL_MS
}
