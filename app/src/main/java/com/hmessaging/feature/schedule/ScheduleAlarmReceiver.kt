package com.hmessaging.feature.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.launch

/** Fires one scheduled message when its alarm goes off. */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
        if (id < 0) return

        val graph = AppGraph.from(context)
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                graph.scheduleManager.fire(id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.hmessaging.action.FIRE_SCHEDULED"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
    }
}
