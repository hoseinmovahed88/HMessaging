package com.hmessaging.util

import android.content.Intent
import android.net.Uri

/**
 * Reads the "send a message to this person" intents the system hands a default SMS app.
 *
 * The manifest advertises SENDTO for `sms:`, `smsto:`, `mms:` and `mmsto:`, which is one of the
 * four requirements for holding the role — so the dialer and the contacts app will route here.
 * Answering that intent by opening the app at the conversation list, rather than at the
 * conversation the user asked for, is the same as not handling it.
 */
object SmsIntents {

    data class Target(val address: String?, val body: String?)

    private val SUPPORTED_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")
    private const val EXTRA_SMS_BODY = "sms_body"

    fun parse(intent: Intent?): Target? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> fromUri(intent)
            Intent.ACTION_SEND -> fromShare(intent)
            else -> null
        }
    }

    private fun fromUri(intent: Intent): Target? {
        val uri = intent.data ?: return null
        if (uri.scheme?.lowercase() !in SUPPORTED_SCHEMES) return null

        // Opaque URIs such as `smsto:0912...?body=hi` cannot be read with getQueryParameter.
        val raw = uri.schemeSpecificPart.orEmpty()
        val recipients = raw.substringBefore('?')
        val query = raw.substringAfter('?', "")

        val address = recipients
            .split(',', ';')
            .map { PhoneNumbers.normalize(it) }
            .firstOrNull { it != PhoneNumbers.UNKNOWN_ADDRESS }

        val body = bodyFromQuery(query)
            ?: intent.getStringExtra(EXTRA_SMS_BODY)
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)

        if (address == null && body.isNullOrBlank()) return null
        return Target(address, body?.takeIf { it.isNotBlank() })
    }

    private fun fromShare(intent: Intent): Target? {
        val body = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(EXTRA_SMS_BODY)
            ?: return null
        val address = intent.data?.let { fromUri(intent)?.address }
        return Target(address, body.takeIf { it.isNotBlank() })
    }

    private fun bodyFromQuery(query: String): String? {
        if (query.isEmpty()) return null
        return query.split('&')
            .firstOrNull { it.startsWith("body=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let { Uri.decode(it) }
    }
}
