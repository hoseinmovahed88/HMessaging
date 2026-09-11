package com.hmessaging.feature.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.hmessaging.R
import com.hmessaging.data.db.dao.ScheduleDao
import com.hmessaging.data.db.entity.ScheduledMessageEntity
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.data.model.ScheduleStatus
import com.hmessaging.notify.Notifications
import com.hmessaging.sms.SmsSender
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat
import java.time.LocalDateTime

/**
 * Owns everything about deferred sending: the alarms, the repeat maths, and the catch-up sweep.
 *
 * Exact alarms are used when the user has granted them, because a message scheduled for 09:00
 * should go at 09:00. Without that grant Android will still fire the alarm, just in its own time,
 * and the periodic sweep in [ScheduleSweepWorker] closes the gap after Doze or a reboot.
 */
class ScheduleManager(
    private val context: Context,
    private val scheduleDao: ScheduleDao,
    private val sender: SmsSender,
    private val notifications: Notifications,
) {

    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    suspend fun save(message: ScheduledMessageEntity): Long {
        val id = scheduleDao.upsert(message)
        val stored = if (message.id == 0L) message.copy(id = id) else message
        if (stored.status == ScheduleStatus.PENDING) setAlarm(stored) else cancelAlarm(stored.id)
        return stored.id
    }

    suspend fun cancel(id: Long) {
        val existing = scheduleDao.byId(id) ?: return
        cancelAlarm(id)
        scheduleDao.upsert(existing.copy(status = ScheduleStatus.CANCELLED))
    }

    suspend fun delete(id: Long) {
        cancelAlarm(id)
        scheduleDao.deleteById(id)
    }

    /** Re-arms every pending alarm. Alarms do not survive a reboot or an app update. */
    suspend fun rescheduleAll() {
        scheduleDao.pending().forEach(::setAlarm)
    }

    /** Sends anything already due — used after boot and on the periodic safety-net sweep. */
    suspend fun sendDue(now: Long = System.currentTimeMillis()) {
        scheduleDao.due(now).forEach { fire(it.id) }
    }

    suspend fun fire(id: Long) {
        val message = scheduleDao.byId(id) ?: return
        if (message.status != ScheduleStatus.PENDING) return

        val recipients = PhoneNumbers.splitRecipients(message.recipients)
        if (recipients.isEmpty()) {
            scheduleDao.upsert(
                message.copy(
                    status = ScheduleStatus.FAILED,
                    lastAttemptAt = System.currentTimeMillis(),
                    lastError = context.getString(R.string.schedule_error_no_recipient),
                ),
            )
            return
        }

        val outcome = sender.send(
            recipients = recipients,
            body = message.body,
            subscriptionId = message.subscriptionId,
        )
        val now = System.currentTimeMillis()

        if (!outcome.isSuccess) {
            scheduleDao.upsert(
                message.copy(
                    status = ScheduleStatus.FAILED,
                    lastAttemptAt = now,
                    lastError = outcome.errors.firstOrNull(),
                ),
            )
            notifications.showScheduleResult(
                message.id,
                context.getString(R.string.schedule_failed),
                message.body.take(NOTIFICATION_SNIPPET),
            )
            return
        }

        val next = nextOccurrence(message)
        val updated = if (next != null) {
            message.copy(
                scheduledAt = next,
                sentCount = message.sentCount + 1,
                lastAttemptAt = now,
                lastError = null,
                status = ScheduleStatus.PENDING,
            )
        } else {
            message.copy(
                sentCount = message.sentCount + 1,
                lastAttemptAt = now,
                lastError = null,
                status = ScheduleStatus.SENT,
            )
        }
        scheduleDao.upsert(updated)
        if (next != null) setAlarm(updated) else cancelAlarm(message.id)
    }

    /** Next firing time for a repeating message, or null when it is finished. */
    fun nextOccurrence(message: ScheduledMessageEntity): Long? {
        if (message.repeat == RepeatMode.NONE) return null
        var candidate = TimeFormat.toLocal(message.scheduledAt)
        val now = System.currentTimeMillis()
        // Step forward until we pass "now", so a missed run does not fire repeatedly.
        repeat(MAX_CATCH_UP_STEPS) {
            candidate = advance(candidate, message.repeat)
            val millis = TimeFormat.toEpochMillis(candidate)
            if (millis > now) {
                val until = message.repeatUntil
                return if (until != null && millis > until) null else millis
            }
        }
        return null
    }

    private fun advance(from: LocalDateTime, repeat: RepeatMode): LocalDateTime = when (repeat) {
        RepeatMode.NONE -> from
        RepeatMode.HOURLY -> from.plusHours(1)
        RepeatMode.DAILY -> from.plusDays(1)
        RepeatMode.WEEKLY -> from.plusWeeks(1)
        RepeatMode.MONTHLY -> from.plusMonths(1)
    }

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }

    private fun setAlarm(message: ScheduledMessageEntity) {
        val manager = alarmManager ?: return
        val pending = alarmIntent(message.id)
        val triggerAt = message.scheduledAt.coerceAtLeast(System.currentTimeMillis())
        runCatching {
            if (canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                // Without the exact-alarm grant this still fires, just batched by the system.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }
    }

    private fun cancelAlarm(id: Long) {
        alarmManager?.cancel(alarmIntent(id))
    }

    private fun alarmIntent(id: Long): PendingIntent {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleAlarmReceiver.ACTION_FIRE
            data = Uri.parse("hmessaging://schedule/$id")
            putExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID, id)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, id.toInt(), intent, flags)
    }

    private companion object {
        const val MAX_CATCH_UP_STEPS = 1000
        const val NOTIFICATION_SNIPPET = 120
    }
}
