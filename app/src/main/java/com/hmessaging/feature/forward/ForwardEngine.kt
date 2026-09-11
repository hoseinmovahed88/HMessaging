package com.hmessaging.feature.forward

import com.hmessaging.data.db.dao.ForwardDao
import com.hmessaging.data.db.entity.ForwardLogEntity
import com.hmessaging.data.db.entity.ForwardRuleEntity
import com.hmessaging.data.model.SourceMatch
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.sms.SmsSender
import com.hmessaging.util.ContactsLookup
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * Re-sends incoming messages to other numbers.
 *
 * Forwarding is the easiest feature in a messaging app to turn into an infinite loop and a large
 * bill, so three guards apply: a message arriving *from* one of a rule's own targets is never
 * forwarded by that rule, an hourly ceiling caps the total, and forwards never carry the user's
 * signature (which would grow the body on every hop).
 */
class ForwardEngine(
    private val forwardDao: ForwardDao,
    private val prefs: AppPrefs,
    private val sender: SmsSender,
    private val contacts: ContactsLookup,
) {

    private val regexCache = ConcurrentHashMap<String, Regex>()

    /** Returns how many forwards were dispatched. */
    suspend fun maybeForward(rawAddress: String, body: String, receivedAt: Long): Int {
        val settings = prefs.settings.first()
        if (!settings.forwardingEnabled) return 0

        val recentForwards = forwardDao.countSince(receivedAt - MILLIS_PER_HOUR)
        if (recentForwards >= settings.forwardMaxPerHour) return 0

        val address = PhoneNumbers.normalize(rawAddress)
        val rules = forwardDao.enabledRules()
        var budget = settings.forwardMaxPerHour - recentForwards
        var dispatched = 0

        for (rule in rules) {
            if (budget <= 0) break
            if (!matches(rule, address, body)) continue

            val targets = PhoneNumbers.splitRecipients(rule.targets)
                // Loop guard: never bounce a message straight back to where it came from.
                .filterNot { PhoneNumbers.sameNumber(it, address) }
            if (targets.isEmpty()) continue

            val text = render(rule, address, body, receivedAt)
            for (target in targets) {
                if (budget <= 0) break
                val outcome = sender.send(
                    recipients = listOf(target),
                    body = text,
                    subscriptionId = rule.subscriptionId,
                    appendSignature = false,
                    allowChunking = true,
                )
                forwardDao.log(
                    ForwardLogEntity(
                        ruleId = rule.id,
                        fromAddress = address,
                        target = target,
                        sentAt = System.currentTimeMillis(),
                        success = outcome.isSuccess,
                        error = outcome.errors.firstOrNull(),
                    ),
                )
                budget--
                if (outcome.isSuccess) dispatched++
            }
        }
        return dispatched
    }

    private fun matches(rule: ForwardRuleEntity, address: String, body: String): Boolean =
        when (rule.matchType) {
            SourceMatch.ALL -> true
            SourceMatch.CONTACTS_ONLY -> contacts.isKnownContact(address)
            SourceMatch.NON_CONTACTS_ONLY -> !contacts.isKnownContact(address)
            SourceMatch.SENDER_PATTERN -> matchesPattern(rule.pattern, address)
            SourceMatch.BODY_KEYWORD -> matchesPattern(rule.pattern, body)
        }

    private fun matchesPattern(pattern: String, subject: String): Boolean {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return false
        if (subject.contains(trimmed, ignoreCase = true)) return true
        val regex = regexCache.getOrPut(trimmed) {
            runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }.getOrElse { return false }
        }
        return regex.containsMatchIn(subject)
    }

    private fun render(rule: ForwardRuleEntity, address: String, body: String, at: Long): String {
        val displayName = contacts.nameFor(address) ?: PhoneNumbers.format(address)
        rule.template?.takeIf { it.isNotBlank() }?.let { template ->
            return template
                .replace("{sender}", PhoneNumbers.format(address))
                .replace("{name}", displayName)
                .replace("{body}", body)
                .replace("{time}", TimeFormat.clock(at))
                .replace("{date}", TimeFormat.dayHeader(at))
        }
        return buildString {
            if (rule.includeSender) append(displayName)
            if (rule.includeTimestamp) {
                if (isNotEmpty()) append(" · ")
                append(TimeFormat.full(at))
            }
            if (isNotEmpty()) append('\n')
            append(body)
        }
    }

    suspend fun pruneLog(retentionDays: Int = DEFAULT_LOG_RETENTION_DAYS) {
        forwardDao.pruneLog(System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY)
    }

    fun invalidateCaches() = regexCache.clear()

    private companion object {
        const val MILLIS_PER_HOUR = 60L * 60 * 1000
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val DEFAULT_LOG_RETENTION_DAYS = 30
    }
}
