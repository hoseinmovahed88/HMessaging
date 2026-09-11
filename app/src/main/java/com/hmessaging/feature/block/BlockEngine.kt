package com.hmessaging.feature.block

import com.hmessaging.data.db.dao.BlockDao
import com.hmessaging.data.db.entity.BlockRuleEntity
import com.hmessaging.data.db.entity.BlockedMessageEntity
import com.hmessaging.data.model.MatchTarget
import com.hmessaging.data.model.MatchType
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.util.ContactsLookup
import com.hmessaging.util.PhoneNumbers
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

sealed interface BlockDecision {
    data object Allowed : BlockDecision
    data class Blocked(val reason: String, val ruleId: Long?) : BlockDecision
}

/**
 * Decides whether an incoming message (or call) should be silently swallowed.
 *
 * Rules are evaluated cheapest-first: the global toggles, then sender rules, then body rules.
 */
class BlockEngine(
    private val blockDao: BlockDao,
    private val prefs: AppPrefs,
    private val contacts: ContactsLookup,
) {

    private val regexCache = ConcurrentHashMap<String, Regex>()

    suspend fun evaluateMessage(rawAddress: String, body: String): BlockDecision {
        val settings = prefs.settings.first()
        val address = PhoneNumbers.normalize(rawAddress)

        globalDecision(address, settings)?.let { return it }

        val rules = blockDao.enabledRules()
        for (rule in rules) {
            val subject = when (rule.target) {
                MatchTarget.SENDER -> address
                MatchTarget.BODY -> body
            }
            if (matches(rule, subject)) {
                blockDao.incrementHit(rule.id)
                return BlockDecision.Blocked(describe(rule), rule.id)
            }
        }
        return BlockDecision.Allowed
    }

    /** Sender-only evaluation, used for call screening where there is no message body. */
    suspend fun evaluateCaller(rawAddress: String?): BlockDecision {
        val settings = prefs.settings.first()
        if (!settings.screenCalls) return BlockDecision.Allowed
        val address = PhoneNumbers.normalize(rawAddress)

        globalDecision(address, settings)?.let { return it }

        for (rule in blockDao.enabledRules()) {
            if (rule.target != MatchTarget.SENDER) continue
            if (matches(rule, address)) {
                blockDao.incrementHit(rule.id)
                return BlockDecision.Blocked(describe(rule), rule.id)
            }
        }
        return BlockDecision.Allowed
    }

    private fun globalDecision(address: String, settings: AppSettings): BlockDecision? {
        if (settings.blockPrivateNumbers && PhoneNumbers.isPrivateOrHidden(address)) {
            return BlockDecision.Blocked(REASON_PRIVATE, null)
        }
        if (settings.blockNonContacts && contacts.hasPermission() && !contacts.isKnownContact(address)) {
            return BlockDecision.Blocked(REASON_NON_CONTACT, null)
        }
        return null
    }

    suspend fun record(address: String, body: String, date: Long, decision: BlockDecision.Blocked) {
        if (!prefs.settings.first().keepBlockedMessages) return
        blockDao.insertBlockedMessage(
            BlockedMessageEntity(
                address = PhoneNumbers.normalize(address),
                body = body,
                date = date,
                reason = decision.reason,
                ruleId = decision.ruleId,
            ),
        )
    }

    /** Adds the simplest possible rule for a number, skipping duplicates. */
    suspend fun blockNumber(rawAddress: String, note: String? = null): Boolean {
        val address = PhoneNumbers.normalize(rawAddress)
        if (address == PhoneNumbers.UNKNOWN_ADDRESS) return false
        if (blockDao.hasExactSenderRule(address)) return false
        blockDao.upsertRule(
            BlockRuleEntity(
                pattern = address,
                matchType = MatchType.EXACT,
                target = MatchTarget.SENDER,
                note = note,
            ),
        )
        return true
    }

    suspend fun unblockNumber(rawAddress: String) {
        val address = PhoneNumbers.normalize(rawAddress)
        blockDao.allRules()
            .filter { it.target == MatchTarget.SENDER && it.matchType == MatchType.EXACT }
            .filter { PhoneNumbers.sameNumber(it.pattern, address) }
            .forEach { blockDao.deleteRuleById(it.id) }
    }

    suspend fun isNumberBlocked(rawAddress: String): Boolean {
        val address = PhoneNumbers.normalize(rawAddress)
        return blockDao.enabledRules()
            .filter { it.target == MatchTarget.SENDER }
            .any { matches(it, address) }
    }

    private fun matches(rule: BlockRuleEntity, subject: String): Boolean {
        val pattern = rule.pattern.trim()
        if (pattern.isEmpty()) return false
        return when (rule.matchType) {
            MatchType.EXACT ->
                if (rule.target == MatchTarget.SENDER) {
                    PhoneNumbers.sameNumber(pattern, subject)
                } else {
                    subject.equals(pattern, ignoreCase = true)
                }

            MatchType.STARTS_WITH -> subject.startsWith(pattern, ignoreCase = true)
            MatchType.ENDS_WITH -> subject.endsWith(pattern, ignoreCase = true)
            MatchType.CONTAINS -> subject.contains(pattern, ignoreCase = true)
            MatchType.REGEX -> compile(pattern)?.containsMatchIn(subject) ?: false
        }
    }

    /** A user-authored regex may be invalid; a broken rule must never break delivery. */
    private fun compile(pattern: String): Regex? = regexCache.getOrPut(pattern) {
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrElse { return null }
    }

    private fun describe(rule: BlockRuleEntity): String {
        val target = if (rule.target == MatchTarget.SENDER) "sender" else "text"
        return rule.note?.takeIf { it.isNotBlank() }
            ?: "$target ${rule.matchType.name.lowercase().replace('_', ' ')} \"${rule.pattern}\""
    }

    fun invalidateCaches() {
        regexCache.clear()
        contacts.invalidate()
    }

    private companion object {
        const val REASON_PRIVATE = "Private or hidden number"
        const val REASON_NON_CONTACT = "Not in contacts"
    }
}
