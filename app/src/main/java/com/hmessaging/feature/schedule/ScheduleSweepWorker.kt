package com.hmessaging.feature.schedule

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

/**
 * Safety net for scheduling and retention.
 *
 * Exact alarms can be dropped by Doze, by a reboot that happens before the app runs again, or by
 * the user revoking the exact-alarm permission. This periodic pass sends anything already due,
 * re-arms the remaining alarms, and applies the OTP retention window.
 */
class ScheduleSweepWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        return runCatching {
            graph.scheduleManager.sendDue()
            graph.scheduleManager.rescheduleAll()

            val settings = graph.prefs.settings.first()
            graph.otpPresenter.purgeOld(settings.otpAutoDeleteDays)
            graph.messageRepository.purgeOldOtpMessages(settings.otpAutoDeleteDays)

            graph.autoReplyEngine.pruneLog()
            graph.forwardEngine.pruneLog()
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val UNIQUE_NAME = "schedule_sweep"
        private const val INTERVAL_MINUTES = 30L

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduleSweepWorker>(
                INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
