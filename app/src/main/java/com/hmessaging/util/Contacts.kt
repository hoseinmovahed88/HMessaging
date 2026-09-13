package com.hmessaging.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** A contact that matched a search: what to show, and what to send to. */
data class ContactMatch(val name: String, val number: String)

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
        // A lookup made without the permission answers "no name" for every number. Caching that
        // would freeze the answer for the life of the process, long after the user grants it.
        if (!hasPermission()) return null
        val resolved = query(address)
        cache[address] = Optional(resolved)
        return resolved
    }

    fun isKnownContact(address: String): Boolean = nameFor(address) != null

    /**
     * Contacts whose name or number matches [query], for picking someone to write to.
     *
     * Deliberately not cached: this answers a different question from [nameFor] — many rows for one
     * query rather than one name for one number — and the phone's own contacts provider is already
     * indexed for exactly this filter.
     */
    fun search(query: String, limit: Int = SEARCH_LIMIT): List<ContactMatch> {
        if (query.isBlank() || !hasPermission()) return emptyList()
        val uri: Uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(query.trim()),
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        val name = cursor.getString(0).orEmpty()
                        val number = cursor.getString(1).orEmpty()
                        if (number.isNotBlank()) add(ContactMatch(name, number))
                    }
                }
                    // One person with a mobile and a landline is two rows; the same number listed
                    // twice is not two people.
                    .distinctBy { PhoneNumbers.threadKey(it.number) }
            }
        }.getOrNull().orEmpty()
    }

    fun invalidate() = cache.clear()

    private companion object {
        const val SEARCH_LIMIT = 30
    }

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
