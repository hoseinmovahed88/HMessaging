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
     * Whether the watcher should be running at all.
     *
     * Never unless the reader turned it on: it costs a permanent notification. Once on, it runs
     * whenever this app is the default SMS app, whatever the delivery record says — because then
     * its job is not watching the store but keeping a process alive. A ROM that refuses to
     * cold-start an app for a broadcast (HyperOS with autostart off, which is how every fresh
     * install starts) delivers SMS_DELIVER to nobody, and the default app is the only app that
     * would have written the message anywhere. A live process receives it.
     *
     * When another app is default, the store does change without this app, and the delivery
     * record decides as before. That record no longer proves itself right forever: the watcher's
     * own runtime receiver now hands a message straight to the pipeline as a broadcast would.
     */
    fun shouldRun(context: Context, settings: AppSettings, stats: DeliveryStats): Boolean =
        settings.liveSyncEnabled && (AppRoles.isDefaultSmsApp(context) || isNeeded(stats))

    /** Why it is running, for the diagnostics screen to say something better than "on". */
    fun reason(stats: DeliveryStats): String = when {
        stats.missedByBroadcast > 0 ->
            "needed: ${stats.missedByBroadcast} message(s) arrived only by reading the SMS store"

        stats.viaBroadcast < EVIDENCE_REQUIRED ->
            "watching: ${stats.viaBroadcast} of $EVIDENCE_REQUIRED broadcasts arrived unaided"

        else -> "not needed: every message so far arrived by broadcast"
    }
}
