package com.hmessaging.feature.otp

import android.content.Context
import android.content.Intent
import com.hmessaging.data.db.dao.OtpDao
import com.hmessaging.data.db.entity.OtpEntity
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.notify.Notifications
import com.hmessaging.util.AppRoles
import kotlinx.coroutines.flow.first

/**
 * Stores a detected code and surfaces it.
 *
 * Android 10 removed the ability for a background app to start an activity, so the pop-up is only
 * launched directly when the user has granted "display over other apps"; otherwise the code is
 * delivered as a heads-up notification whose tap target opens the same pop-up. The clipboard copy
 * always happens inside the activity, because background clipboard writes are ignored from
 * Android 10 onward.
 */
class OtpPresenter(
    private val context: Context,
    private val otpDao: OtpDao,
    private val prefs: AppPrefs,
    private val notifications: Notifications,
) {

    suspend fun present(match: OtpMatch, sender: String, body: String, receivedAt: Long): Long {
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
        val popupShown = settings.otpPopupEnabled && tryLaunchPopup(otpId, match, sender, body)
        if (!popupShown) {
            notifications.showOtp(otpId, match.code, sender, match.serviceName, body)
        }
        return otpId
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
