package com.hmessaging.sms

import com.hmessaging.data.prefs.DeliveryStats

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

    /** Why it is running, for the diagnostics screen to say something better than "on". */
    fun reason(stats: DeliveryStats): String = when {
        stats.missedByBroadcast > 0 ->
            "needed: ${stats.missedByBroadcast} message(s) arrived only by reading the SMS store"

        stats.viaBroadcast < EVIDENCE_REQUIRED ->
            "watching: ${stats.viaBroadcast} of $EVIDENCE_REQUIRED broadcasts arrived unaided"

        else -> "not needed: every message so far arrived by broadcast"
    }
}
