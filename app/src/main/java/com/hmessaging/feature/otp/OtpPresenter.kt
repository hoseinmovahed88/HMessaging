package com.hmessaging.feature.otp

import android.content.Context
import android.content.Intent
import com.hmessaging.data.db.dao.OtpDao
import com.hmessaging.data.db.entity.OtpEntity
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.notify.AlertOutcome
import com.hmessaging.notify.Notifications
import com.hmessaging.util.AppRoles
import kotlinx.coroutines.flow.first

/**
 * Stores a detected code and surfaces it.
 *
 * The notification is posted every time; the pop-up is an extra on top of it. It used to be one
 * or the other, with the notification kept back whenever the pop-up launch did not throw — and on
 * Android 10 and later a launch from the background does not throw when it is refused, it is
 * simply dropped. On a locked phone that is every launch. So a code arrived, the app believed it
 * had shown it, and the reader heard nothing and saw nothing on the lock screen. The clipboard
 * copy still happens inside the activity, because background clipboard writes are ignored from
 * Android 10 onward.
 */
class OtpPresenter(
    private val context: Context,
    private val otpDao: OtpDao,
    private val prefs: AppPrefs,
    private val notifications: Notifications,
) {

    suspend fun present(match: OtpMatch, sender: String, body: String, receivedAt: Long): AlertOutcome {
        val otpId = otpDao.insert(
            OtpEntity(
                code = match.code,
                sender = sender,
                body = body,
                receivedAt = receivedAt,
                serviceName = match.serviceName,
            ),
        )

        val settings = prefs.settings.first()
        val outcome = notifications.showOtp(otpId, match.code, sender, match.serviceName, body)
        if (settings.otpPopupEnabled) tryLaunchPopup(otpId, match, sender, body)
        return outcome
    }

    private fun tryLaunchPopup(otpId: Long, match: OtpMatch, sender: String, body: String): Boolean {
        if (!AppRoles.canDrawOverlays(context)) return false
        val intent = OtpPopupActivity.intent(context, otpId, match.code, sender, match.serviceName, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    suspend fun purgeOld(retentionDays: Int): Int {
        if (retentionDays <= 0) return 0
        return otpDao.pruneOlderThan(System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY)
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
