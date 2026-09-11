package com.hmessaging.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

object Clipboards {

    /**
     * Copies [text] to the clipboard.
     *
     * From Android 10 onward only a foreground app may write to the clipboard, which is why the
     * OTP flow does the copy from its pop-up activity rather than from the broadcast receiver.
     */
    fun copy(context: Context, label: String, text: String, sensitive: Boolean = false): Boolean {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Stops Android 13+ from rendering the value in the clipboard confirmation toast.
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return runCatching { manager.setPrimaryClip(clip) }.isSuccess
    }

    /** Android 13+ shows its own clipboard confirmation, so apps should not add a toast. */
    fun systemShowsCopyConfirmation(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
