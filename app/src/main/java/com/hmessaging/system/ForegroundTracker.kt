package com.hmessaging.system

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.hmessaging.feature.otp.OtpPopupActivity
import com.hmessaging.feature.quickreply.QuickReplyPopupActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether the app's own UI is the thing on screen.
 *
 * Used to decide that a floating pop-up would be redundant: if the reader is already looking at
 * HMessaging, the conversation list has updated underneath the window it would open.
 *
 * The two pop-up activities are deliberately not counted. They are overlays drawn over whatever the
 * reader was doing, not the app being used — and counting them would make the first pop-up suppress
 * the second, which is the opposite of what is wanted.
 */
class ForegroundTracker : Application.ActivityLifecycleCallbacks {

    private val started = AtomicInteger(0)

    val isAppOnScreen: Boolean get() = started.get() > 0

    override fun onActivityStarted(activity: Activity) {
        if (counts(activity)) started.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        if (counts(activity)) started.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    private fun counts(activity: Activity): Boolean =
        activity !is QuickReplyPopupActivity && activity !is OtpPopupActivity

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
