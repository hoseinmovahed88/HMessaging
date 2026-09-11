package com.hmessaging.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves phone numbers to contact names. Results are memoised because the incoming-message
 * pipeline and the conversation list both hit this for every row.
 */
class ContactsLookup(private val context: Context) {

    private val cache = ConcurrentHashMap<String, Optional>()

    private class Optional(val name: String?)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun nameFor(address: String): String? {
        if (address.isBlank() || address == PhoneNumbers.UNKNOWN_ADDRESS) return null
        cache[address]?.let { return it.name }
        val resolved = if (hasPermission()) query(address) else null
        cache[address] = Optional(resolved)
        return resolved
    }

    fun isKnownContact(address: String): Boolean = nameFor(address) != null

    fun invalidate() = cache.clear()

    private fun query(address: String): String? {
        val uri: Uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address),
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
