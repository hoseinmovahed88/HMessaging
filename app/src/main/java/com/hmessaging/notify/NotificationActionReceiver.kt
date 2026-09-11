package com.hmessaging.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.RemoteInput
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.launch

/** Handles the inline actions on a message notification: reply, mark read, block. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        if (threadId < 0) return
        val action = intent.action ?: return
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY_TEXT)
            ?.toString()
            ?.trim()

        val graph = AppGraph.from(context)
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                when (action) {
                    ACTION_REPLY -> reply(graph, threadId, replyText)
                    ACTION_MARK_READ -> markRead(graph, threadId)
                    ACTION_BLOCK -> block(graph, threadId, intent.getStringExtra(EXTRA_ADDRESS))
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun reply(graph: AppGraph, threadId: Long, text: String?) {
        if (text.isNullOrBlank()) return
        val thread = graph.messageRepository.threadById(threadId) ?: return
        graph.smsSender.send(recipients = listOf(thread.address), body = text)
        graph.messageRepository.markThreadRead(threadId)
        graph.notifications.cancelThread(threadId)
    }

    private suspend fun markRead(graph: AppGraph, threadId: Long) {
        graph.messageRepository.markThreadRead(threadId)
        graph.notifications.cancelThread(threadId)
    }

    private suspend fun block(graph: AppGraph, threadId: Long, address: String?) {
        if (!address.isNullOrBlank()) graph.blockEngine.blockNumber(address)
        graph.messageRepository.markThreadRead(threadId)
        graph.notifications.cancelThread(threadId)
    }

    companion object {
        const val ACTION_REPLY = "com.hmessaging.action.NOTIFICATION_REPLY"
        const val ACTION_MARK_READ = "com.hmessaging.action.NOTIFICATION_MARK_READ"
        const val ACTION_BLOCK = "com.hmessaging.action.NOTIFICATION_BLOCK"
        const val KEY_REPLY_TEXT = "reply_text"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_ADDRESS = "address"

        fun intent(context: Context, action: String, threadId: Long): Intent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = action
                // Distinguishes otherwise-identical PendingIntents per thread.
                data = Uri.parse("hmessaging://notification/$action/$threadId")
                putExtra(EXTRA_THREAD_ID, threadId)
            }
    }
}
