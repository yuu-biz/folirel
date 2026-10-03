package io.github.yuubiz.feeddeck.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshScope
import kotlinx.coroutines.launch

class FeedDeckApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(StartupRefreshObserver(container))
    }
}

/**
 * On app start/return: refresh once if the last automatic attempt is at least 30 minutes old. Uses the same
 * coordinator and the same network constraint as periodic updates (opening the app does not bypass it).
 */
private class StartupRefreshObserver(private val container: AppContainer) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        container.appScope.launch {
            val settings = container.settings.current()
            val now = container.clock.millis()
            if (now - settings.lastAutoRefreshAttemptAt >= STARTUP_MIN_INTERVAL_MS) {
                container.refresh.refresh(RefreshScope.All, RefreshReason.STARTUP)
            }
        }
    }

    companion object {
        const val STARTUP_MIN_INTERVAL_MS = 30L * 60 * 1000
    }
}
