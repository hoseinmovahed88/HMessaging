package com.hmessaging.feature.bank

import com.hmessaging.data.db.dao.BankDao
import com.hmessaging.data.db.entity.BankRuleEntity
import com.hmessaging.data.db.entity.BankTxEntity
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.util.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one [BankLedger.backfill] pass got through. */
data class BackfillResult(val scanned: Int, val filed: Int, val finished: Boolean)

/**
 * Files bank messages into the ledger, using only the formats the user has taught.
 *
 * No rules means no transactions, deliberately. The version of this that guessed at formats filed
 * six thousand rows from a phone with a few hundred real transactions in it — phone bills, advert
 * texts, a personal message whose "912" became an amount — and a ledger that wrong is worse than no
 * ledger at all, because its totals look like answers.
 */
class BankLedger(
    private val bankDao: BankDao,
    private val prefs: AppPrefs,
) {

    /** Parses one stored message; returns whether a rule claimed it. */
    suspend fun record(message: MessageEntity): Boolean {
        val rules = bankDao.rulesForSender(PhoneNumbers.threadKey(message.address))
        if (rules.isEmpty()) return false
        val row = firstMatch(rules, message) ?: return false
        bankDao.insert(row)
        return true
    }

    /**
     * Re-reads stored messages with the current rules.
     *
     * Only the senders that have rules are walked, which is what makes this quick: a phone with a
     * hundred thousand messages usually has a handful of banks among them, and every other sender
     * is skipped by the database rather than by the parser.
     */
    suspend fun backfill(limit: Int = BACKFILL_LIMIT): BackfillResult = withContext(Dispatchers.IO) {
        val rules = bankDao.activeRules()
        if (rules.isEmpty()) return@withContext BackfillResult(0, 0, finished = true)

        val bySender = rules.groupBy { it.senderKey }
        var cursor = prefs.bankScanCursor().takeIf { it > 0 } ?: Long.MAX_VALUE
        var filed = 0
        var scanned = 0
        var finished = false

        while (scanned < limit) {
            val batch = bankDao.inboxBefore(cursor, BATCH_SIZE)
            if (batch.isEmpty()) {
                finished = true
                break
            }
            val rows = batch.mapNotNull { message ->
                bySender[PhoneNumbers.threadKey(message.address)]?.let { firstMatch(it, message) }
            }
            if (rows.isNotEmpty()) {
                bankDao.insertAll(rows)
                filed += rows.size
            }
            scanned += batch.size
            cursor = batch.last().id
            prefs.setBankScanCursor(cursor)
        }

        BackfillResult(scanned = scanned, filed = filed, finished = finished)
    }

    /** Starts the walk again from the newest message, for after a rule is added or changed. */
    suspend fun resetScan() = prefs.setBankScanCursor(0)

    /** Removes a rule and everything it filed, so a mistaught format can be taken back. */
    suspend fun deleteRule(ruleId: Long) {
        bankDao.deleteTxForRule(ruleId)
        bankDao.deleteRule(ruleId)
    }

    /**
     * The first rule that reads this message wins.
     *
     * Order is not arbitrary: a sender's rules are distinguished by the label in front of the
     * amount, so at most one of them matches any given message. Where two would, taking the first
     * is at least stable.
     */
    private fun firstMatch(rules: List<BankRuleEntity>, message: MessageEntity): BankTxEntity? {
        for (rule in rules) {
            val tx = BankRules.apply(rule, message.body) ?: continue
            return BankTxEntity(
                messageId = message.id,
                threadId = message.threadId,
                address = message.address,
                kind = tx.kind,
                amount = tx.amount,
                currency = tx.currency,
                accountKey = accountKeyOf(tx.accountLabel, message.address),
                accountLabel = tx.accountLabel,
                balance = tx.balance,
                at = message.date,
                body = message.body,
                ruleId = rule.id,
            )
        }
        return null
    }

    /**
     * Two messages belong to the same account when the bank prints the same digits. Banks mask
     * differently in different messages (`*1234`, `6219****1234`), so only the digits count — and
     * when a bank names no account at all, the sender stands in for one, which keeps each bank's
     * unattributed messages apart rather than merging them into one nameless pile.
     */
    private fun accountKeyOf(label: String?, address: String): String {
        val digits = label?.filter(Char::isDigit).orEmpty()
        return if (digits.isNotEmpty()) digits else "@" + PhoneNumbers.normalize(address)
    }

    private companion object {
        const val BATCH_SIZE = 300
        const val BACKFILL_LIMIT = 20_000
    }
}
