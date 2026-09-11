package com.hmessaging.sms

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hmessaging.MainActivity
import com.hmessaging.R
import com.hmessaging.di.AppGraph
import com.hmessaging.notify.NotificationActionReceiver
import com.hmessaging.notify.Notifications
import com.hmessaging.system.Diagnostics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps message delivery working on ROMs that will not start this app from a manifest receiver.
 *
 * Several vendor Android builds — MIUI/HyperOS most prominently — deny "autostart" by default,
 * which means a manifest-declared `SMS_DELIVER` or `SMS_RECEIVED` receiver never fires because the
 * process is never launched to host it. Two nets are cast from inside a process that is already
 * alive, where those restrictions do not apply:
 *
 *  - the same broadcasts, registered at runtime, and
 *  - a [ContentObserver] on the platform SMS store, which changes whether or not we got the
 *    broadcast, so a message that slipped past every receiver is still picked up.
 */
class SmsSyncService : Service() {

    private val graph by lazy { AppGraph.from(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var syncJob: Job? = null

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = sync("SMS store changed")
    }

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            sync("runtime ${intent.action?.substringAfterLast('.')}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        runCatching {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                addAction(Telephony.Sms.Intents.SMS_DELIVER_ACTION)
                priority = MAX_FILTER_PRIORITY
            }
            ContextCompat.registerReceiver(
                this,
                smsReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        sync("service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        sync("service command")
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(observer) }
        runCatching { unregisterReceiver(smsReceiver) }
        syncJob?.cancel()
        super.onDestroy()
    }

    /** Coalesces bursts: a multipart message fires the observer once per part. */
    private fun sync(reason: String) {
        if (syncJob?.isActive == true) return
        syncJob = graph.applicationScope.launch {
            val progress = graph.smsImporter.syncNew(deliverThroughPipeline = true)
            if (progress.imported > 0 || !progress.succeeded) {
                graph.diagnostics.record(
                    Diagnostics.KIND_SYNC,
                    if (progress.succeeded) {
                        "$reason — picked up ${progress.imported}"
                    } else {
                        "$reason — FAILED: ${progress.error}"
                    },
                )
            }
        }
    }

    private fun startInForeground() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = android.app.PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val turnOff = android.app.PendingIntent.getBroadcast(
            this,
            TURN_OFF_REQUEST_CODE,
            NotificationActionReceiver.stopWatcherIntent(this),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_WATCHER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.sync_service_title))
            .setContentText(getString(R.string.sync_service_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.sync_service_why)))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    getString(R.string.sync_service_turn_off),
                    turnOff,
                ).setShowsUserInterface(false).build(),
            )
            .build()

        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 4242
        private const val REQUEST_CODE = 4243
        private const val TURN_OFF_REQUEST_CODE = 4244
        private const val MAX_FILTER_PRIORITY = 999

        /** Starting a foreground service is refused from the background on Android 12+. */
        fun start(context: Context) {
            val intent = Intent(context, SmsSyncService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SmsSyncService::class.java)) }
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return false
            @Suppress("DEPRECATION")
            return runCatching {
                manager.getRunningServices(Int.MAX_VALUE).any {
                    it.service.className == SmsSyncService::class.java.name
                }
            }.getOrDefault(false)
        }
    }
}
