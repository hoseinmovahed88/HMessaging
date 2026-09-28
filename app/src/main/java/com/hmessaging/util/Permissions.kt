package com.hmessaging.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {

    /** Everything the messaging pipeline needs; contacts and notifications are soft extras. */
    val required: List<String> = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Only older phones need a permission to write the public Download folder, where the
        // daily backup goes; from Android 10 the media store takes the app's own files without one.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context): List<String> = required.filterNot { has(context, it) }

    fun canSendSms(context: Context): Boolean = has(context, Manifest.permission.SEND_SMS)

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            has(context, Manifest.permission.POST_NOTIFICATIONS)
}
