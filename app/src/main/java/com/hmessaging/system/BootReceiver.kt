package com.hmessaging.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hmessaging.di.AppGraph
import com.hmessaging.feature.schedule.ScheduleSweepWorker
import com.hmessaging.sms.SmsSyncService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Alarms do not survive a reboot, an app update, or a clock change, so every pending scheduled
 * message is re-armed here — and anything that came due while the device was off is sent.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit

            else -> return
        }

        val graph = AppGraph.from(context)
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                graph.scheduleManager.sendDue()
                graph.scheduleManager.rescheduleAll()
                ScheduleSweepWorker.enqueue(context)
                if (graph.prefs.settings.first().liveSyncEnabled) SmsSyncService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
