@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hmessaging.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hmessaging.R
import com.hmessaging.data.db.dao.ThreadAccountSummary
import com.hmessaging.data.db.entity.BankTxEntity
import com.hmessaging.data.model.BankTxKind
import com.hmessaging.ui.theme.LocalHyperColors
import com.hmessaging.util.Money
import com.hmessaging.util.TimeFormat

/**
 * One bank's conversation, read as accounts instead of as a list of texts.
 *
 * The messages are already in the conversation behind this sheet; what they do not say, however
 * many of them there are, is which account each belongs to and what is in it now. That is the
 * whole job here: one card per account, the balance the bank last stated for it, and the money in
 * and out — then, on a tap, only that account's messages.
 *
 * Nothing is inferred. Every figure comes from a rule the reader taught, so an account only
 * appears once its format has been explained.
 */
@Composable
fun BankAccountsSheet(
    accounts: List<ThreadAccountSummary>,
    transactions: List<BankTxEntity>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Null means every account; a key narrows the list below to that one.
    var openAccount by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            item("title") {
                Text(
                    text = stringResource(R.string.bank_accounts_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            items(accounts, key = { "${it.accountKey}|${it.currency}" }) { account ->
                AccountCard(
                    account = account,
                    expanded = openAccount == account.accountKey,
                    onClick = {
                        openAccount = if (openAccount == account.accountKey) null else account.accountKey
                    },
                )
            }

            item("tx-title") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = if (openAccount == null) {
                        stringResource(R.string.bank_all_transactions)
                    } else {
                        stringResource(R.string.bank_account_transactions)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            val shown = transactions.filter { openAccount == null || it.accountKey == openAccount }
            items(shown, key = { it.id }) { tx -> TransactionRow(tx) }

            item("tail") { Text(text = "", modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun AccountCard(
    account: ThreadAccountSummary,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (expanded) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = account.accountLabel?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.bank_account_unnamed),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.bank_tx_count, account.txCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The balance first and largest: it is the one number anyone opens this to read.
            if (account.latestBalance != null) {
                Text(
                    text = Money.format(account.latestBalance) + " " + Money.currencyName(account.currency),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.bank_balance_as_of, TimeFormat.full(account.lastAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Amount(
                    label = stringResource(R.string.bank_deposits),
                    value = Money.format(account.deposits),
                    color = LocalHyperColors.current.success,
                )
                Amount(
                    label = stringResource(R.string.bank_withdrawals),
                    value = Money.format(account.withdrawals),
                    color = MaterialTheme.colorScheme.error,
                )
                Amount(
                    label = stringResource(R.string.currency),
                    value = Money.currencyName(account.currency),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Amount(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun TransactionRow(tx: BankTxEntity) {
    val deposit = tx.kind == BankTxKind.DEPOSIT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = (if (deposit) "+" else "−") + Money.format(tx.amount),
                style = MaterialTheme.typography.bodyLarge,
                color = if (deposit) {
                    LocalHyperColors.current.success
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                text = TimeFormat.full(tx.at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (tx.balance != null) {
            Text(
                text = stringResource(R.string.bank_balance, Money.format(tx.balance)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
