package com.hmessaging.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** A contact that matched a search: what to show, and what to send to. */
data class ContactMatch(val name: String, val number: String, val photoUri: String? = null)

/**
 * Resolves phone numbers to contact names. Results are memoised because the incoming-message
 * pipeline and the conversation list both hit this for every row.
 *
 * The address book is also read once in full and kept in memory. That is there because the
 * provider's own matching could not be relied on: a message from `+989127464018` stayed nameless
 * next to a contact saved as `09127464018`, and neither `PhoneLookup.CONTENT_FILTER_URI` nor
 * `Phone.CONTENT_FILTER_URI` would join the two — the latter matches numbers from their beginning,
 * so filtering on a tail finds nothing. Holding the numbers ourselves means the comparison is the
 * app's own [PhoneNumbers.threadKey], the same rule that already decides two spellings are one
 * conversation, instead of whatever the device's provider happens to implement.
 */
class ContactsLookup(private val context: Context) {

    private val cache = ConcurrentHashMap<String, Optional>()

    private class Optional(val name: String?)

    /** Every stored number, and the same list keyed by significant digits. Built once, on demand. */
    private class Directory(val entries: List<ContactMatch>, val byKey: Map<String, ContactMatch>)

    @Volatile
    private var directory: Directory? = null
    private val directoryLock = Any()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun nameFor(address: String): String? {
        if (address.isBlank() || address == PhoneNumbers.UNKNOWN_ADDRESS) return null
        cache[address]?.let { return it.name }
        // A lookup made without the permission answers "no name" for every number. Caching that
        // would freeze the answer for the life of the process, long after the user grants it.
        if (!hasPermission()) return null
        // PhoneLookup first because it is a single indexed row and answers most numbers; the full
        // address book is only read when it does not, which is the case this whole class exists for.
        val resolved = query(address)?.takeIf { it.isNotBlank() } ?: bySignificantDigits(address)
        cache[address] = Optional(resolved)
        return resolved
    }

    /**
     * Matches on the last nine digits, against the address book read in full.
     *
     * This is the answer for numbers written with a country code on one side and a national zero
     * on the other. It deliberately does not ask the provider to do the matching, because asking
     * it is what failed.
     */
    private fun bySignificantDigits(address: String): String? {
        val key = PhoneNumbers.threadKey(address)
        if (key.length < MIN_MATCHABLE_DIGITS) return null
        return directory()?.byKey?.get(key)?.name
    }

    /**
     * The contact's photo, as a URI to open, or null when they have none.
     *
     * Resolved from the same in-memory address book as the name, so showing a picture costs no
     * extra provider query — only the decode of the thumbnail itself, which the caller caches.
     */
    fun photoFor(address: String): String? {
        if (address.isBlank() || address == PhoneNumbers.UNKNOWN_ADDRESS) return null
        if (!hasPermission()) return null
        val key = PhoneNumbers.threadKey(address)
        if (key.length < MIN_MATCHABLE_DIGITS) return null
        return directory()?.byKey?.get(key)?.photoUri
    }

    fun isKnownContact(address: String): Boolean = nameFor(address) != null

    /**
     * Contacts whose name or number matches [query], for picking someone to write to.
     *
     * Answered from the in-memory address book rather than a filter query, for the same reason as
     * [bySignificantDigits] and with a bonus: typing the tail of a number now finds it, which a
     * prefix-matching provider filter never did.
     */
    fun search(query: String, limit: Int = SEARCH_LIMIT): List<ContactMatch> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !hasPermission()) return emptyList()
        val entries = directory()?.entries ?: return emptyList()

        return entries.asSequence()
            .filter { SearchMatch.matches(trimmed, it.name, it.number) }
            .distinctBy { PhoneNumbers.threadKey(it.number) }
            .take(limit)
            .toList()
    }

    fun invalidate() {
        cache.clear()
        directory = null
    }

    /**
     * The address book, read on first use and kept until [invalidate].
     *
     * Reading it whole costs one cursor pass over a few hundred rows, once per process — cheaper
     * than the per-number provider query it replaces, which the conversation list made for every
     * unresolved row anyway.
     */
    private fun directory(): Directory? {
        directory?.let { return it }
        if (!hasPermission()) return null
        synchronized(directoryLock) {
            directory?.let { return it }
            val loaded = load() ?: return null
            directory = loaded
            return loaded
        }
    }

    private fun load(): Directory? = runCatching {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )?.use { cursor ->
            val entries = ArrayList<ContactMatch>(cursor.count.coerceAtMost(DIRECTORY_LIMIT))
            val byKey = HashMap<String, ContactMatch>(cursor.count.coerceAtMost(DIRECTORY_LIMIT))
            while (cursor.moveToNext() && entries.size < DIRECTORY_LIMIT) {
                val name = cursor.getString(0).orEmpty().trim()
                val number = cursor.getString(1).orEmpty().trim()
                if (number.isEmpty() || name.isEmpty()) continue
                val match = ContactMatch(name, number, cursor.getString(2)?.takeIf { it.isNotBlank() })
                entries += match
                val key = PhoneNumbers.threadKey(number)
                // First writer wins, so the alphabetically first name is the one shown when two
                // contacts share a number — stable between runs, which a last-writer rule is not.
                if (key.length >= MIN_MATCHABLE_DIGITS) byKey.putIfAbsent(key, match)
            }
            Directory(entries, byKey)
        }
    }.getOrNull()

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

    private companion object {
        const val SEARCH_LIMIT = 30

        /** Below this, a "match" on the tail would be matching almost anything. */
        const val MIN_MATCHABLE_DIGITS = 7

        /** A guard against an address book synced from somewhere unreasonable, not a real limit. */
        const val DIRECTORY_LIMIT = 20_000
    }
}
