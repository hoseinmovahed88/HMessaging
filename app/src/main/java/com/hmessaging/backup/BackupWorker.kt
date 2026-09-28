package com.hmessaging.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Runs [AutoBackup.writeNow] once a day while the setting is on. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        if (!graph.prefs.settings.first().autoBackupEnabled) return Result.success()
        return graph.autoBackup.writeNow().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        private const val UNIQUE_NAME = "auto_backup"
        private const val INTERVAL_HOURS = 24L

        /**
         * KEEP rather than UPDATE: the first run of a periodic request happens at once, and
         * re-enqueueing on every app start would make that "at once" every app start.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
