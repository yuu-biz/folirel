package io.github.yuubiz.feeddeck.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.yuubiz.feeddeck.app.FeedDeckApp
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.data.preferences.AppSettings
import java.util.concurrent.TimeUnit

/** Periodic refresh through the shared coordinator. Runs in the app process (no separate worker process). */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FeedDeckApp).container
        container.refresh.refresh(RefreshScope.All, RefreshReason.PERIODIC)
        // Partial results are already applied per feed; the next run continues with the oldest feeds first.
        return Result.success()
    }
}

/** Keeps the unique periodic work in line with settings. Timing is inexact and may be delayed by the OS. */
class RefreshScheduler(private val context: Context) {
    private var applied: Pair<Int, Boolean>? = null

    fun apply(settings: AppSettings) {
        val wanted = if (settings.onboardingDone) settings.refreshIntervalMinutes to settings.unmeteredOnly else 0 to true
        if (wanted == applied) return
        applied = wanted
        val workManager = WorkManager.getInstance(context)
        val (minutes, unmeteredOnly) = wanted
        if (minutes <= 0) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        // The minimum period is 15 minutes; the OS may run the work later than asked.
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(minutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val WORK_NAME = "feed-refresh"
    }
}
