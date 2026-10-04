package io.github.yuubiz.folirel.sync

import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.network.NetworkState

/**
 * Rules for automatic (startup and periodic) refresh. Manual refresh does not use them.
 * - nothing automatic before onboarding; periodic needs the interval on, startup needs the startup switch on
 * - the unmetered-only constraint applies to startup refresh too (opening the app does not bypass it)
 * - startup refresh at most once per 30 minutes, measured from the last refresh attempt of any kind (not success)
 */
object AutoRefreshPolicy {
    const val STARTUP_MIN_INTERVAL_MS = 30L * 60 * 1000

    /** Periodic: needs the interval switched on. Startup: needs the startup switch. Both: the same network rule. */
    fun networkAllowed(settings: AppSettings, network: NetworkState, reason: RefreshReason = RefreshReason.PERIODIC): Boolean {
        val enabled = when (reason) {
            RefreshReason.STARTUP -> settings.startupRefresh
            else -> settings.refreshIntervalMinutes > 0
        }
        return settings.onboardingDone && enabled && network.connected && (!settings.unmeteredOnly || !network.metered)
    }

    fun startupDue(nowMillis: Long, lastAttemptAt: Long): Boolean =
        nowMillis - lastAttemptAt >= STARTUP_MIN_INTERVAL_MS
}
