package io.github.yuubiz.folirel.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.yuubiz.folirel.backend.RefreshReason
import io.github.yuubiz.folirel.backend.RefreshScope
import io.github.yuubiz.folirel.sync.AutoRefreshPolicy
import kotlinx.coroutines.launch

class FolirelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(StartupRefreshObserver(container))
    }
}

/**
 * On app start/return: refresh once if the last refresh attempt (manual or automatic) is at least 30 minutes old. Uses the same
 * coordinator and the same network constraint as periodic updates (opening the app does not bypass it).
 */
private class StartupRefreshObserver(private val container: AppContainer) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        container.appScope.launch {
            val settings = container.settings.current()
            val now = container.clock.millis()
            if (AutoRefreshPolicy.startupDue(now, settings.lastRefreshAttemptAt)) {
                container.refresh.refresh(RefreshScope.All, RefreshReason.STARTUP)
            }
        }
    }
}
