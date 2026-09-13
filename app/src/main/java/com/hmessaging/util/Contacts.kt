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
        val resolved = query(address) ?: bySignificantDigits(address)
        cache[address] = Optional(resolved)
        return resolved
    }

    /**
     * Second attempt, for when the platform's own lookup will not match across number forms.
     *
     * `PhoneLookup` is documented to compare numbers loosely, and mostly does — but a message from
     * `+989127464018` was arriving nameless beside a contact saved as `09127464018`, so on this
     * device it does not. The app already knows how to decide that two spellings are one person:
     * the significant-digits key that stops the same contact opening two conversations. The same
     * key answers it here, against whatever the contacts provider returns for those digits.
     */
    private fun bySignificantDigits(address: String): String? {
        val key = PhoneNumbers.threadKey(address)
        if (key.length < MIN_MATCHABLE_DIGITS) return null
        return search(key, limit = TAIL_MATCH_LIMIT)
            .firstOrNull { PhoneNumbers.threadKey(it.number) == key }
            ?.name
            ?.takeIf { it.isNotBlank() }
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
        const val TAIL_MATCH_LIMIT = 10

        /** Below this, a "match" on the tail would be matching almost anything. */
        const val MIN_MATCHABLE_DIGITS = 7
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
