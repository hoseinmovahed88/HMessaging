package com.hmessaging.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Loads and remembers contact photo thumbnails.
 *
 * Written by hand rather than pulled in with an image-loading library because that is all this
 * needs: the thumbnails the contacts provider stores are a hundred pixels square, they are read
 * from the device rather than the network, and there is exactly one place in the app that shows
 * them. A cache and a decode is the whole job.
 *
 * The cache is bounded and keyed by the photo URI. A conversation list scrolling past two hundred
 * rows would otherwise decode the same handful of pictures over and over, once per recomposition.
 */
object ContactPhotos {

    /** null means "looked, and this contact has no picture" — worth remembering too. */
    private val cache: MutableMap<String, Bitmap?> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Bitmap?>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>): Boolean =
                size > MAX_ENTRIES
        },
    )

    /** Decodes the thumbnail at [uri]. Call from a background dispatcher. */
    fun load(context: Context, uri: String?): Bitmap? {
        if (uri.isNullOrBlank()) return null
        if (cache.containsKey(uri)) return cache[uri]
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri.toUri())?.use { stream ->
                // Thumbnails are already small, but a contact synced from an account may carry a
                // full-size picture here; sampling keeps a list scroll from decoding megabytes.
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = 1
                })
            }
        }.getOrNull()
        cache[uri] = bitmap
        return bitmap
    }

    fun clear() = cache.clear()

    private const val MAX_ENTRIES = 150
    private const val INITIAL_CAPACITY = 32
    private const val LOAD_FACTOR = 0.75f
}
