package com.hmessaging.util

import android.content.Context
import android.content.Intent

/** Hands text to whatever the user picks: another messenger, notes, mail. */
object Sharing {

    fun shareText(context: Context, text: String, subject: String? = null) {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        // Always the chooser, never a remembered default: sharing a message to the wrong app
        // because of a choice made once months ago is not a mistake worth risking.
        val chooser = Intent.createChooser(intent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }
}
