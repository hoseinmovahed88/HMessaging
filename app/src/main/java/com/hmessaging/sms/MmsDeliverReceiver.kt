package com.hmessaging.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hmessaging.di.AppGraph
import com.hmessaging.system.Diagnostics
import kotlinx.coroutines.launch

/**
 * Required for the app to be eligible as the default SMS app: Android will not offer the role to
 * a package that cannot receive `WAP_PUSH_DELIVER`.
 *
 * This app is SMS-only — it does not download or display MMS — so the notification is acknowledged
 * and dropped rather than silently pretended away.
 */
class MmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Ignoring MMS push: this build handles SMS only (${intent.action})")
        val graph = AppGraph.from(context)
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                graph.diagnostics.record(Diagnostics.KIND_WAP_PUSH, "MMS push ignored (SMS-only build)")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MmsDeliverReceiver"
    }
}
