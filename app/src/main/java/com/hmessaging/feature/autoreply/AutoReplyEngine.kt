package com.hmessaging.feature.autoreply

import com.hmessaging.data.db.dao.AutoReplyDao
import com.hmessaging.data.db.entity.AutoReplyLogEntity
import com.hmessaging.data.db.entity.AutoReplyRuleEntity
import com.hmessaging.data.model.SourceMatch
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.sms.SmsSender
import com.hmessaging.util.ContactsLookup
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * The answering machine: replies to incoming messages on the user's behalf.
 *
 * Two things keep it from becoming a message loop or a phone bill: a per-sender cooldown plus a
 * daily cap, and a hard refusal to answer anything that is not a real, dialable subscriber number.
 */
class AutoReplyEngine(
    private val autoReplyDao: AutoReplyDao,
    private val prefs: AppPrefs,
    private val sender: SmsSender,
    private val contacts: ContactsLookup,
) {

    private val regexCache = ConcurrentHashMap<String, Regex>()

    /** Returns the rule that answered, or null when nothing replied. */
    suspend fun maybeReply(
        rawAddress: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int,
    ): AutoReplyRuleEntity? {
        val settings = prefs.settings.first()
        val awayMode = settings.awayModeActive
        if (!settings.autoReplyEnabled && !awayMode) return null

        val address = PhoneNumbers.normalize(rawAddress)
        if (!isReplyable(address)) return null

        val rules = autoReplyDao.enabledRules()
        for (rule in rules) {
            // Away mode is an explicit override, so it ignores each rule's own time window.
            if (!awayMode && !isWithinSchedule(rule, receivedAt)) continue
            if (!matches(rule, address, body)) continue
            if (!withinLimits(rule, address, receivedAt)) continue

            val text = render(rule.replyText, address, body, receivedAt)
            val outcome = sender.send(
                recipients = listOf(address),
                body = text,
                subscriptionId = subscriptionId,
                appendSignature = false,
                allowChunking = false,
            )
            if (!outcome.isSuccess) return null

            autoReplyDao.log(AutoReplyLogEntity(ruleId = rule.id, address = address, sentAt = receivedAt))
            return rule
        }
        return null
    }

    /**
     * Alphanumeric sender IDs and short codes cannot receive a reply; answering them wastes
     * messages and, with some operators, money.
     */
    private fun isReplyable(address: String): Boolean {
        if (address == PhoneNumbers.UNKNOWN_ADDRESS) return false
        if (address.any { it.isLetter() }) return false
        return address.count { it.isDigit() } >= MIN_DIALABLE_DIGITS
    }

    private fun isWithinSchedule(rule: AutoReplyRuleEntity, at: Long): Boolean {
        val dayBit = 1 shl TimeFormat.dayBitIndex(at)
        if (rule.daysMask and dayBit == 0) return false
        val minute = TimeFormat.minuteOfDay(at)
        return if (rule.startMinuteOfDay <= rule.endMinuteOfDay) {
            minute in rule.startMinuteOfDay..rule.endMinuteOfDay
        } else {
            // Window wraps past midnight, e.g. 22:00 → 07:00.
            minute >= rule.startMinuteOfDay || minute <= rule.endMinuteOfDay
        }
    }

    private fun matches(rule: AutoReplyRuleEntity, address: String, body: String): Boolean =
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

    private suspend fun withinLimits(rule: AutoReplyRuleEntity, address: String, now: Long): Boolean {
        if (rule.cooldownMinutes > 0) {
            val last = autoReplyDao.lastReplyTo(address)
            if (last != null && now - last < rule.cooldownMinutes * MILLIS_PER_MINUTE) return false
        }
        if (rule.maxPerDay > 0) {
            val today = TimeFormat.startOfDay(now)
            if (autoReplyDao.replyCountSince(address, today) >= rule.maxPerDay) return false
        }
        return true
    }

    /** Supports `{sender}`, `{name}`, `{body}`, `{time}` and `{date}` placeholders. */
    private fun render(template: String, address: String, body: String, at: Long): String =
        template
            .replace("{sender}", PhoneNumbers.format(address))
            .replace("{name}", contacts.nameFor(address) ?: PhoneNumbers.format(address))
            .replace("{body}", body)
            .replace("{time}", TimeFormat.clock(at))
            .replace("{date}", TimeFormat.dayHeader(at))

    suspend fun pruneLog(retentionDays: Int = DEFAULT_LOG_RETENTION_DAYS) {
        autoReplyDao.pruneLog(System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY)
    }

    fun invalidateCaches() = regexCache.clear()

    private companion object {
        const val MIN_DIALABLE_DIGITS = 7
        const val MILLIS_PER_MINUTE = 60_000L
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val DEFAULT_LOG_RETENTION_DAYS = 30
    }
}
