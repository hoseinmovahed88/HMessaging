package com.hmessaging.feature.quickreply

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.system.ForegroundTracker
import com.hmessaging.util.AppRoles
import kotlinx.coroutines.flow.first

/**
 * Decides whether an arriving message deserves a floating reply window.
 *
 * A notification is the right answer for a phone in a pocket. The pop-up is for the other case —
 * the screen is on and being used — where a heads-up banner that disappears after a few seconds
 * makes the reader stop what they are doing, find the app, and scroll to the conversation. Those
 * are the only conditions it fires under, so it can never wake a dark screen or fight the lock
 * screen: the OTP pop-up owns that case, deliberately, because a code is worth the interruption.
 */
class QuickReplyPresenter(
    private val context: Context,
    private val prefs: AppPrefs,
    private val foreground: ForegroundTracker,
) {

    suspend fun maybeShow(threadId: Long, address: String, contactName: String?, body: String, receivedAt: Long): Boolean {
        if (threadId <= 0 || body.isBlank()) return false
        if (!prefs.settings.first().quickReplyPopupEnabled) return false
        if (!AppRoles.canDrawOverlays(context)) return false
        // Nothing to float over when the reader is already in the app: the list behind the window
        // has the message on it.
        if (foreground.isAppOnScreen) return false
        if (!screenInUse()) return false

        val intent = QuickReplyPopupActivity.intent(context, threadId, address, contactName, body, receivedAt)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * "In use" means lit and unlocked. `isInteractive` alone is not enough: it stays true while the
     * lock screen is showing, and a reply box on a locked phone would both fail to take input and
     * hand the message body to whoever picked the phone up.
     */
    private fun screenInUse(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        if (!power.isInteractive) return false
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return true
        return !keyguard.isKeyguardLocked
    }
}
