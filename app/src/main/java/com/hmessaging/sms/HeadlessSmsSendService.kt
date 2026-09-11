package com.hmessaging.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.hmessaging.di.AppGraph
import com.hmessaging.util.PhoneNumbers
import kotlinx.coroutines.launch

/**
 * Serves `RESPOND_VIA_MESSAGE` — the "reply with a message" option the dialer offers on an
 * incoming call. Required for the default-SMS-app role.
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val recipient = intent?.data?.let { uri ->
            uri.schemeSpecificPart?.substringBefore('?')
        }?.let(PhoneNumbers::normalize)

        if (!text.isNullOrBlank() && !recipient.isNullOrBlank() && recipient != PhoneNumbers.UNKNOWN_ADDRESS) {
            val graph = AppGraph.from(this)
            graph.applicationScope.launch {
                graph.smsSender.send(recipients = listOf(recipient), body = text)
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
