package com.hmessaging.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.hmessaging.MainActivity
import com.hmessaging.R
import com.hmessaging.data.db.entity.ThreadEntity
import com.hmessaging.feature.otp.OtpPopupActivity
import com.hmessaging.util.Permissions
import com.hmessaging.util.PhoneNumbers

/** Every user-visible notification the app posts, and the channels they live on. */
class Notifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { enableVibration(true) },
            NotificationChannel(
                CHANNEL_OTP,
                context.getString(R.string.channel_otp),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_SCHEDULED,
                context.getString(R.string.channel_scheduled),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_system),
                NotificationManager.IMPORTANCE_LOW,
            ),
            // The watcher's notification cannot be removed — a foreground service must show one —
            // but at MIN it keeps no status bar icon and sits collapsed at the bottom of the shade.
            // A separate channel from CHANNEL_STATUS because an existing channel's importance can
            // never be lowered in code, only by the user.
            NotificationChannel(
                CHANNEL_WATCHER,
                context.getString(R.string.channel_watcher),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        system.createNotificationChannels(channels)
    }

    fun showIncomingMessage(thread: ThreadEntity, body: String, receivedAt: Long, showPreview: Boolean) {
        if (thread.muted) return
        val title = thread.contactName ?: PhoneNumbers.format(thread.address)
        val person = Person.Builder().setName(title).setKey(thread.address).build()
        val text = if (showPreview) body else context.getString(R.string.channel_messages)

        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName(context.getString(R.string.app_name)).build(),
        ).addMessage(text, receivedAt, person)

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(receivedAt)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openThreadIntent(thread.id))
            .addAction(replyAction(thread.id))
            .addAction(markReadAction(thread.id))
            .addAction(blockAction(thread.id, thread.address))

        post(messageNotificationId(thread.id), builder.build())
    }

    fun showOtp(otpId: Long, code: String, sender: String, serviceName: String?, body: String) {
        val title = context.getString(R.string.otp_title)
        val from = serviceName ?: PhoneNumbers.format(sender)
        val builder = NotificationCompat.Builder(context, CHANNEL_OTP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$title · $code")
            .setContentText(from)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(otpPopupIntent(otpId, code, sender, serviceName, body))
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.copy),
                    otpPopupIntent(otpId, code, sender, serviceName, body),
                ).build(),
            )
        builder.setTimeoutAfter(OTP_NOTIFICATION_TIMEOUT_MS)
        post(otpNotificationId(otpId), builder.build())
    }

    fun showScheduleResult(scheduledId: Long, title: String, text: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_SCHEDULED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openScheduledIntent())
        post(SCHEDULE_NOTIFICATION_BASE + scheduledId.toInt(), builder.build())
    }

    fun cancelThread(threadId: Long) = manager.cancel(messageNotificationId(threadId))

    fun cancelOtp(otpId: Long) = manager.cancel(otpNotificationId(otpId))

    @SuppressLint("MissingPermission") // Guarded by the runtime check on the line below.
    private fun post(id: Int, notification: Notification) {
        if (!Permissions.canPostNotifications(context)) return
        runCatching { manager.notify(id, notification) }
    }

    private fun openThreadIntent(threadId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_THREAD
            data = Uri.parse("hmessaging://thread/$threadId")
            putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, threadId.toInt(), intent, immutableFlags())
    }

    private fun openScheduledIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_SCHEDULED
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, SCHEDULE_REQUEST_CODE, intent, immutableFlags())
    }

    private fun otpPopupIntent(
        otpId: Long,
        code: String,
        sender: String,
        serviceName: String?,
        body: String,
    ): PendingIntent {
        val intent = OtpPopupActivity.intent(context, otpId, code, sender, serviceName, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(context, otpNotificationId(otpId), intent, immutableFlags())
    }

    private fun replyAction(threadId: Long): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.notif_reply_hint))
            .build()
        val intent = NotificationActionReceiver.intent(
            context,
            NotificationActionReceiver.ACTION_REPLY,
            threadId,
        )
        // Direct reply writes the typed text back into this intent, so it must be mutable.
        val pending = PendingIntent.getBroadcast(
            context,
            replyRequestCode(threadId),
            intent,
            mutableFlags(),
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.notif_reply),
            pending,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(threadId: Long): NotificationCompat.Action {
        val intent = NotificationActionReceiver.intent(
            context,
            NotificationActionReceiver.ACTION_MARK_READ,
            threadId,
        )
        val pending = PendingIntent.getBroadcast(
            context,
            markReadRequestCode(threadId),
            intent,
            immutableFlags(),
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.notif_mark_read),
            pending,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun blockAction(threadId: Long, address: String): NotificationCompat.Action {
        val intent = NotificationActionReceiver.intent(
            context,
            NotificationActionReceiver.ACTION_BLOCK,
            threadId,
        ).putExtra(NotificationActionReceiver.EXTRA_ADDRESS, address)
        val pending = PendingIntent.getBroadcast(
            context,
            blockRequestCode(threadId),
            intent,
            immutableFlags(),
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.block_number),
            pending,
        ).setShowsUserInterface(false).build()
    }

    private fun immutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun mutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_OTP = "otp"
        const val CHANNEL_SCHEDULED = "scheduled"
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_WATCHER = "watcher"

        private const val MESSAGE_NOTIFICATION_BASE = 10_000
        private const val OTP_NOTIFICATION_BASE = 500_000
        private const val SCHEDULE_NOTIFICATION_BASE = 900_000
        private const val SCHEDULE_REQUEST_CODE = 7_001
        private const val REPLY_REQUEST_BASE = 100_000
        private const val MARK_READ_REQUEST_BASE = 200_000
        private const val BLOCK_REQUEST_BASE = 300_000
        private const val OTP_NOTIFICATION_TIMEOUT_MS = 5 * 60 * 1000L

        fun messageNotificationId(threadId: Long): Int = MESSAGE_NOTIFICATION_BASE + threadId.toInt()
        fun otpNotificationId(otpId: Long): Int = OTP_NOTIFICATION_BASE + otpId.toInt()
        private fun replyRequestCode(threadId: Long): Int = REPLY_REQUEST_BASE + threadId.toInt()
        private fun markReadRequestCode(threadId: Long): Int = MARK_READ_REQUEST_BASE + threadId.toInt()
        private fun blockRequestCode(threadId: Long): Int = BLOCK_REQUEST_BASE + threadId.toInt()
    }
}
