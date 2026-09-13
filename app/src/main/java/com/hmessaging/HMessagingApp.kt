package com.hmessaging

import android.app.Application
import androidx.work.Configuration
import com.hmessaging.di.AppGraph
import com.hmessaging.feature.schedule.ScheduleSweepWorker
import com.hmessaging.util.TimeFormat
import kotlinx.coroutines.launch

class HMessagingApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        val graph = AppGraph.from(this)
        graph.notifications.ensureChannels()
        registerActivityLifecycleCallbacks(graph.foreground)

        // Dates are written from list rows, notifications and workers alike, none of which can
        // await a preference, so the choice is mirrored into the formatter as it changes.
        graph.applicationScope.launch {
            graph.prefs.settings.collect { TimeFormat.persianCalendar = it.persianCalendar }
        }

        graph.applicationScope.launch {
            // A send whose result broadcast never arrived (process death mid-send) would otherwise
            // sit in the outbox forever.
            graph.messageRepository.failStaleOutbox(STALE_OUTBOX_WINDOW_MS)
            graph.scheduleManager.sendDue()
            graph.scheduleManager.rescheduleAll()
        }
        ScheduleSweepWorker.enqueue(this)
    }

    private companion object {
        const val STALE_OUTBOX_WINDOW_MS = 15L * 60 * 1000
    }
}
