package com.hmessaging.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
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

/** What is stopping this app from making a sound, when something is. */
enum class AlertProblem { PERMISSION, APP_BLOCKED, CHANNEL_BLOCKED, CHANNEL_SILENT }

/**
 * What became of one attempt to alert the reader.
 *
 * Every reason a message can arrive in silence used to look the same from outside: nothing. The
 * pipeline records this against each message so the diagnostics log can say which it was.
 */
enum class AlertOutcome { POSTED, MUTED, NO_PERMISSION, APP_BLOCKED, FAILED }

/** Every user-visible notification the app posts, and the channels they live on. */
class Notifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Creates the channels, and re-creates the message one under a new id whenever its settings
     * change.
     *
     * A channel is created once and then belongs to the user: Android ignores every later attempt
     * to change its importance, its sound or how it behaves on the lock screen. So the settings
     * below only ever applied to phones that installed this app after they were written, and a
     * phone carrying the first version's channel kept the first version's behaviour forever —
     * silent, on a vendor ROM that had quietly decided what a new channel should default to. The
     * id carries a version for that reason; bumping it is the only way to hand out a channel that
     * actually has these settings, and the old one is deleted so the app does not end up listing
     * two.
     */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return

        RETIRED_CHANNELS.forEach { runCatching { system.deleteNotificationChannel(it) } }

        val messageSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val messageAudio = AudioAttributes.Builder()
            // The plain notification usage. The instant-message one is more descriptive, and on
            // stock Android it plays through the same stream — but a vendor ROM's sound pipeline
            // only has to route one usage wrongly for every message to arrive silent, and that is
            // the one usage every notification on the phone already proves works.
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channels = listOf(
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(messageSound, messageAudio)
                enableVibration(true)
                vibrationPattern = MESSAGE_VIBRATION
                enableLights(true)
                setShowBadge(true)
                // The channel sets the ceiling and each notification chooses below it: with the
                // preview off, the notification itself asks to be private. A private ceiling here
                // meant a ROM that hides private notifications from the lock screen altogether
                // showed nothing at all, whatever the notification asked for.
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(
                CHANNEL_OTP,
                context.getString(R.string.channel_otp),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(messageSound, messageAudio)
                enableVibration(true)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

    /**
     * Why a message would arrive without a sound or a line on the lock screen.
     *
     * Three separate switches can each silence this app on their own, and none of them says so
     * from inside the app: the runtime permission, the app's notifications as a whole, and the
     * message channel's own importance — which a vendor ROM may set to silent without asking.
     * Returning which one it is lets the app send the reader to the setting that will fix it
     * rather than to a list of everything.
     */
    fun alertProblem(): AlertProblem? {
        if (!Permissions.canPostNotifications(context)) return AlertProblem.PERMISSION
        if (!manager.areNotificationsEnabled()) return AlertProblem.APP_BLOCKED
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val system = context.getSystemService(NotificationManager::class.java) ?: return null
        val channel = system.getNotificationChannel(CHANNEL_MESSAGES) ?: return null
        return when {
            channel.importance == NotificationManager.IMPORTANCE_NONE -> AlertProblem.CHANNEL_BLOCKED
            channel.importance < NotificationManager.IMPORTANCE_DEFAULT -> AlertProblem.CHANNEL_SILENT
            channel.sound == null -> AlertProblem.CHANNEL_SILENT
            else -> null
        }
    }

    /** The settings screen that can undo [alertProblem], as specific as the phone allows. */
    fun alertSettingsIntent(problem: AlertProblem): Intent = when (problem) {
        AlertProblem.CHANNEL_BLOCKED, AlertProblem.CHANNEL_SILENT ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_MESSAGES)
            } else {
                appNotificationSettings()
            }

        else -> appNotificationSettings()
    }

    private fun appNotificationSettings(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        }

    fun showIncomingMessage(
        thread: ThreadEntity,
        body: String,
        receivedAt: Long,
        showPreview: Boolean,
    ): AlertOutcome {
        if (thread.muted) return AlertOutcome.MUTED
        val title = thread.contactName ?: PhoneNumbers.format(thread.address)
        val person = Person.Builder().setName(title).setKey(thread.address).build()
        val text = if (showPreview) body else context.getString(R.string.channel_messages)

        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName(context.getString(R.string.app_name)).build(),
        ).addMessage(text, receivedAt, person)

        val builder = messageBuilder(title, text, receivedAt)
            .setStyle(style)
            // Whether the words themselves appear on the lock screen follows the preview setting;
            // that a message arrived is shown either way.
            .setVisibility(
                if (showPreview) {
                    NotificationCompat.VISIBILITY_PUBLIC
                } else {
                    NotificationCompat.VISIBILITY_PRIVATE
                },
            )
            .setContentIntent(openThreadIntent(thread.id))
            .addAction(replyAction(thread.id))
            .addAction(markReadAction(thread.id))
            .addAction(blockAction(thread.id, thread.address))

        return post(messageNotificationId(thread.id), builder.build())
    }

    /**
     * A message notification with nothing behind it, for checking the phone.
     *
     * It goes through the same channel and the same builder as a real message, so what it does —
     * sound, lock screen, banner — is exactly what a real message would do. When this one is heard
     * and a message is not, the fault is on the receiving side; when neither is, it is the phone's
     * notification settings, and the diagnostics screen can say which.
     */
    fun showTest(): AlertOutcome {
        val title = context.getString(R.string.app_name)
        val text = context.getString(R.string.diag_notify_test_body)
        val now = System.currentTimeMillis()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(title).build())
            .addMessage(text, now, Person.Builder().setName(title).setKey("test").build())
        val builder = messageBuilder(title, text, now)
            .setStyle(style)
            .setContentIntent(openScheduledIntent())
        return post(TEST_NOTIFICATION_ID, builder.build())
    }

    /**
     * Everything a message notification needs to be heard and seen, stated on the notification
     * as well as on the channel.
     *
     * Android O and later ignore the sound, vibration and priority set here and use the channel's;
     * earlier phones use these and have no channel. Both are set so the same code works on both,
     * and so a ROM that consults the notification where it should consult the channel finds the
     * same answer in both places.
     */
    private fun messageBuilder(title: String, text: String, at: Long): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(MESSAGE_VIBRATION)
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)

    fun showOtp(otpId: Long, code: String, sender: String, serviceName: String?, body: String): AlertOutcome {
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
        return post(otpNotificationId(otpId), builder.build())
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
    private fun post(id: Int, notification: Notification): AlertOutcome {
        if (!Permissions.canPostNotifications(context)) return AlertOutcome.NO_PERMISSION
        if (!manager.areNotificationsEnabled()) return AlertOutcome.APP_BLOCKED
        return if (runCatching { manager.notify(id, notification) }.isSuccess) {
            AlertOutcome.POSTED
        } else {
            AlertOutcome.FAILED
        }
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
        /**
         * Versioned on purpose; see [ensureChannels]. Raise it whenever the channel's settings
         * change, and add the id left behind to [RETIRED_CHANNELS].
         */
        const val CHANNEL_MESSAGES = "messages_v3"
        const val CHANNEL_OTP = "otp_v3"
        const val CHANNEL_SCHEDULED = "scheduled"
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_WATCHER = "watcher"

        /** Channels earlier versions created, deleted so the settings list holds one of each. */
        private val RETIRED_CHANNELS = listOf("messages", "otp", "messages_v2", "otp_v2")

        /** Two short pulses: enough to feel through a pocket, short enough not to buzz. */
        private val MESSAGE_VIBRATION = longArrayOf(0, 250, 200, 250)

        private const val MESSAGE_NOTIFICATION_BASE = 10_000
        private const val OTP_NOTIFICATION_BASE = 500_000
        private const val SCHEDULE_NOTIFICATION_BASE = 900_000
        private const val SCHEDULE_REQUEST_CODE = 7_001
        private const val TEST_NOTIFICATION_ID = 8_001
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
