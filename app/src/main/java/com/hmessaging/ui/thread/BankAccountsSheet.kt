@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hmessaging.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hmessaging.R
import com.hmessaging.data.db.dao.ThreadAccountSummary
import com.hmessaging.util.Money
import com.hmessaging.util.TimeFormat

/**
 * A bank's accounts, for picking which one the conversation should show.
 *
 * It adds nothing up. The balance beside each account is the last one that bank stated in a
 * message, quoted rather than computed — a total worked out here would be a second answer that
 * disagrees with the messages the moment a format has not been taught, and the messages are right.
 *
 * Picking an account narrows the conversation itself rather than opening a list of figures, so
 * every amount and every balance is read where the bank wrote it.
 */
@Composable
fun BankAccountsSheet(
    accounts: List<ThreadAccountSummary>,
    messageCounts: Map<String, Int>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            item("title") {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.bank_accounts_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.bank_accounts_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item("all") {
                AccountCard(
                    title = stringResource(R.string.bank_all_accounts),
                    detail = null,
                    count = null,
                    chosen = selected == null,
                    onClick = { onSelect(null) },
                )
            }

            items(accounts, key = { "${it.accountKey}|${it.currency}" }) { account ->
                AccountCard(
                    title = account.accountLabel?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.bank_account_unnamed),
                    // The bank's own last word on this account, not a sum of anything.
                    detail = account.latestBalance?.let { balance ->
                        stringResource(
                            R.string.bank_balance_stated,
                            Money.format(balance) + " " + Money.currencyName(account.currency),
                            TimeFormat.full(account.lastAt),
                        )
                    },
                    count = messageCounts[account.accountKey],
                    chosen = selected == account.accountKey,
                    onClick = { onSelect(account.accountKey) },
                )
            }

            item("tail") { Text(text = "", modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun AccountCard(
    title: String,
    detail: String?,
    count: Int?,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (chosen) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (count != null && count > 0) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
