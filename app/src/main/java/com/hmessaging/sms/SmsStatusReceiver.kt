package com.hmessaging.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.launch

/**
 * Receives the per-part send and delivery results produced by
 * [android.telephony.SmsManager.sendMultipartTextMessage].
 *
 * A single failed part fails the whole message; success is only recorded once the final part is
 * acknowledged, which is also the order the radio reports them in.
 */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1)
        val kind = intent.action ?: return
        val code = resultCode

        val graph = AppGraph.from(context)
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                when (kind) {
                    KIND_SENT -> handleSent(graph, messageId, partIndex, partCount, code)
                    KIND_DELIVERED -> graph.messageRepository.markDelivered(messageId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleSent(
        graph: AppGraph,
        messageId: Long,
        partIndex: Int,
        partCount: Int,
        resultCode: Int,
    ) {
        if (resultCode == Activity.RESULT_OK) {
            if (partIndex == partCount - 1) {
                graph.messageRepository.markSent(messageId, mirrorToSystem = graph.smsSender.canMirrorToSystem())
            }
        } else {
            graph.messageRepository.markFailed(messageId, describe(resultCode))
        }
    }

    private fun describe(resultCode: Int): String = when (resultCode) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio is off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Send limit exceeded"
        else -> "Send failed (code $resultCode)"
    }

    companion object {
        const val KIND_SENT = "com.hmessaging.action.SMS_SENT"
        const val KIND_DELIVERED = "com.hmessaging.action.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"
    }
}
