package com.hmessaging.feature.bank

import com.hmessaging.data.db.dao.BankDao
import com.hmessaging.data.db.entity.BankTxEntity
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.prefs.AppPrefs
import com.hmessaging.util.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one [BankLedger.backfill] pass got through. */
data class BackfillResult(val scanned: Int, val filed: Int, val finished: Boolean)

/**
 * Keeps the bank ledger in step with the messages.
 *
 * Two ways in: [record] for a message as it arrives, and [backfill] for everything already stored.
 * Both go through the same parser and the same unique index on the message id, so re-running the
 * backfill after improving the parser corrects old rows instead of duplicating them.
 */
class BankLedger(
    private val bankDao: BankDao,
    private val prefs: AppPrefs,
) {

    /** Parses one stored message; returns whether it turned out to be a transaction. */
    suspend fun record(message: MessageEntity): Boolean {
        val row = toRow(message) ?: return false
        bankDao.insert(row)
        return true
    }

    /**
     * Walks stored messages that the parser has not reached and files whichever are bank
     * notifications.
     *
     * Progress is a single message id kept in preferences, walking from newest to oldest, so a pass
     * that is interrupted — or a phone with a hundred thousand messages that needs several — picks
     * up where the last one stopped rather than starting again.
     */
    suspend fun backfill(limit: Int = BACKFILL_LIMIT): BackfillResult = withContext(Dispatchers.IO) {
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
            val rows = batch.mapNotNull { toRow(it) }
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

    /** Starts the walk again from the newest message, for after the parser changes. */
    suspend fun resetScan() = prefs.setBankScanCursor(0)

    private fun toRow(message: MessageEntity): BankTxEntity? {
        val tx = BankSmsParser.parse(message.body) ?: return null
        val label = tx.accountLabel
        return BankTxEntity(
            messageId = message.id,
            threadId = message.threadId,
            address = message.address,
            kind = tx.kind,
            amount = tx.amount,
            currency = tx.currency,
            accountKey = accountKeyOf(label, message.address),
            accountLabel = label,
            balance = tx.balance,
            at = message.date,
            body = message.body,
        )
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
