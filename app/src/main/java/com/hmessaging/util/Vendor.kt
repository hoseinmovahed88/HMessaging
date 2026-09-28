package com.hmessaging.util

import android.os.Build

/** The one vendor family whose notification defaults this app has to talk the reader through. */
object Vendor {

    /**
     * True on Xiaomi, Redmi and POCO phones.
     *
     * HyperOS keeps a notification-sound switch of its own, above Android's channels, and leaves
     * it off for apps installed from outside its store. Android reports the channel as loud, the
     * app cannot read the switch, and every message arrives in silence until the reader finds
     * it — so on these phones the reader is told where it is.
     */
    val isXiaomi: Boolean by lazy {
        val names = listOf(Build.MANUFACTURER, Build.BRAND).map { it.orEmpty().lowercase() }
        names.any { name -> XIAOMI_NAMES.any { it in name } }
    }

    private val XIAOMI_NAMES = listOf("xiaomi", "redmi", "poco")
}
