package com.hmessaging.sms

import android.content.Context
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.data.prefs.DeliveryStats
import com.hmessaging.util.AppRoles

/**
 * Whether the background watcher is still earning the notification it is obliged to show.
 *
 * Android will not run a foreground service without a visible notification, so the only way to be
 * rid of the permanent one is to stop the service — and the only honest way to decide that is from
 * what this phone has actually done. The app already counts it: every stored message records
 * whether a broadcast delivered it or whether it had to be found by reading the SMS store.
 *
 * A phone that has delivered a run of messages by broadcast does not need a watcher, and gets its
 * notification back only if one goes missing again. A phone that has missed even one keeps it,
 * because a message that never arrives costs more than a line in the notification shade.
 */
object WatcherNeed {

    /**
     * How many broadcasts must arrive unaided before the watcher stands down.
     *
     * Not one or two: a device that restricts background wake-ups often does so only after the app
     * has been idle for a while, so a handful of messages received in one afternoon proves little.
     */
    const val EVIDENCE_REQUIRED = 15

    fun isNeeded(stats: DeliveryStats): Boolean =
        stats.missedByBroadcast > 0 || stats.viaBroadcast < EVIDENCE_REQUIRED

    /**
     * Whether the watcher should be running at all, which is a stricter question than [isNeeded].
     *
     * It is off unless the reader turned it on, and off regardless while this app is the default
     * SMS app — because then it is the only app allowed to write the SMS store, and a watcher that
     * waits for the store to change is waiting for itself. The store cannot reveal a message this
     * app never received; only a message some other app wrote, which is what the watcher was built
     * to catch and only exists when that other app is the default.
     *
     * The old rule kept the watcher up on any phone that had ever found a message by reading the
     * store — and the watcher's own runtime receiver counts that way, so once running it proved
     * its own necessity forever, and its notification never left.
     */
    fun shouldRun(context: Context, settings: AppSettings, stats: DeliveryStats): Boolean =
        settings.liveSyncEnabled && !AppRoles.isDefaultSmsApp(context) && isNeeded(stats)

    /** Why it is running, for the diagnostics screen to say something better than "on". */
    fun reason(stats: DeliveryStats): String = when {
        stats.missedByBroadcast > 0 ->
            "needed: ${stats.missedByBroadcast} message(s) arrived only by reading the SMS store"

        stats.viaBroadcast < EVIDENCE_REQUIRED ->
            "watching: ${stats.viaBroadcast} of $EVIDENCE_REQUIRED broadcasts arrived unaided"

        else -> "not needed: every message so far arrived by broadcast"
    }
}
