package com.hmessaging.ui.thread

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hmessaging.R
import com.hmessaging.data.model.BankTxKind

/**
 * Tabs above a bank's conversation: one per account, plus money in and money out.
 *
 * Two dimensions in one row, divided rather than stacked, because a bank conversation is read on a
 * phone and two rows of chips would leave a screen of tabs above a screen of messages. They
 * combine: one account and money out together is the question people actually ask.
 *
 * Every tab carries its count, so a tab is never a tap into an empty conversation, and an account
 * with nothing under it is not offered at all. The tabs narrow the conversation itself, so every
 * amount and every balance is read where the bank wrote it.
 */
@Composable
fun BankTabsRow(
    accounts: List<BankTab>,
    depositCount: Int,
    withdrawalCount: Int,
    accountFilter: String?,
    directionFilter: BankTxKind?,
    onAccount: (String?) -> Unit,
    onDirection: (BankTxKind?) -> Unit,
    onClearAll: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            FilterChip(
                selected = accountFilter == null && directionFilter == null,
                onClick = onClearAll,
                label = { Text(stringResource(R.string.bank_all_messages)) },
            )

            if (depositCount > 0 || withdrawalCount > 0) {
                VerticalDivider(modifier = Modifier.padding(horizontal = 2.dp).height(24.dp))
                if (depositCount > 0) {
                    FilterChip(
                        selected = directionFilter == BankTxKind.DEPOSIT,
                        onClick = {
                            onDirection(if (directionFilter == BankTxKind.DEPOSIT) null else BankTxKind.DEPOSIT)
                        },
                        label = { Text("${stringResource(R.string.bank_deposits)} $depositCount") },
                    )
                }
                if (withdrawalCount > 0) {
                    FilterChip(
                        selected = directionFilter == BankTxKind.WITHDRAWAL,
                        onClick = {
                            onDirection(
                                if (directionFilter == BankTxKind.WITHDRAWAL) null else BankTxKind.WITHDRAWAL,
                            )
                        },
                        label = { Text("${stringResource(R.string.bank_withdrawals)} $withdrawalCount") },
                    )
                }
            }

            if (accounts.isNotEmpty()) {
                VerticalDivider(modifier = Modifier.padding(horizontal = 2.dp).height(24.dp))
                accounts.forEach { account ->
                    FilterChip(
                        selected = accountFilter == account.key,
                        onClick = {
                            onAccount(if (accountFilter == account.key) null else account.key)
                        },
                        label = { Text("${account.key}  ${account.count}") },
                    )
                }
            }
        }
    }
}
