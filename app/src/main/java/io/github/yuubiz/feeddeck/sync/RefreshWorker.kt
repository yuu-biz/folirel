package io.github.yuubiz.feeddeck.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.yuubiz.feeddeck.app.FeedDeckApp
import io.github.yuubiz.feeddeck.backend.RefreshReason
import io.github.yuubiz.feeddeck.backend.RefreshScope
import io.github.yuubiz.feeddeck.data.preferences.AppSettings
import java.util.concurrent.TimeUnit

/**
 * Periodic refresh through the shared coordinator, in this worker's coroutine: when the system stops the worker,
 * fetches only it wanted are cancelled (finished feeds stay applied).
 * If the time budget left feeds unreached, one follow-up run for just those feeds is scheduled a few minutes
 * later under the same network constraint (at most [MAX_CONTINUATIONS] in a row; never an immediate loop).
 * Otherwise the next periodic run starts with the feeds attempted longest ago.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FeedDeckApp).container
        val continuation = inputData.getInt(KEY_CONTINUATION, 0)
        val scope = if (continuation > 0) RefreshScope.Pending else RefreshScope.All
        val report = container.refresh.refresh(scope, RefreshReason.PERIODIC)
        if (report.notStarted + report.cancelled > 0 && continuation < MAX_CONTINUATIONS) {
            val settings = container.settings.current()
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInitialDelay(CONTINUATION_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(RefreshScheduler.constraints(settings.unmeteredOnly))
                .setInputData(workDataOf(KEY_CONTINUATION to continuation + 1))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(CONTINUATION_NAME, ExistingWorkPolicy.REPLACE, request)
        }
        return Result.success()
    }

    companion object {
        const val CONTINUATION_NAME = "feed-refresh-continue"
        const val MAX_CONTINUATIONS = 2
        const val CONTINUATION_DELAY_MINUTES = 3L
        private const val KEY_CONTINUATION = "continuation"
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
        val constraints = constraints(unmeteredOnly)
        // The minimum period is 15 minutes; the OS may run the work later than asked.
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(minutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val WORK_NAME = "feed-refresh"

        fun constraints(unmeteredOnly: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }
}
