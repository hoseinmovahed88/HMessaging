package com.hmessaging.ui.bank

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.data.db.entity.BankTxEntity
import com.hmessaging.data.model.BankTxKind
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.EmptyState
import com.hmessaging.ui.components.HyperCard
import com.hmessaging.ui.components.HyperGroupedRow
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.components.HyperScreen
import com.hmessaging.ui.theme.LocalHyperColors
import com.hmessaging.util.Money
import com.hmessaging.util.TimeFormat

/**
 * The money screen: what the banks have said, sorted out.
 *
 * Deposits and withdrawals are split because that is the question these messages get asked, and the
 * accounts are separated because a phone that receives messages from three banks otherwise produces
 * one meaningless running total.
 */
@Composable
fun BankScreen(
    onOpenDrawer: () -> Unit,
    viewModel: BankViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scanned = stringResource(R.string.bank_scanned)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar("$scanned $it")
            viewModel.clearMessage()
        }
    }

    HyperScreen(
        title = stringResource(R.string.nav_bank),
        navigationIcon = { HyperIconButton(Icons.Filled.Menu, null, onOpenDrawer) },
        snackbarHostState = snackbar,
        actions = {
            HyperIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.bank_rescan),
                onClick = viewModel::rescanAll,
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item("totals") { TotalsCard(state) }
            item("filters") { Filters(state, viewModel) }
            if (state.accounts.size > 1) {
                item("accounts") { Accounts(state, viewModel) }
            }

            if (state.transactions.isEmpty()) {
                item("empty") {
                    EmptyState(
                        text = stringResource(
                            if (state.scanning) R.string.bank_scanning else R.string.bank_empty,
                        ),
                        icon = Icons.Filled.AccountBalance,
                        modifier = Modifier.padding(top = 48.dp),
                    )
                }
            } else {
                itemsIndexed(state.transactions, key = { _, row -> row.id }) { index, row ->
                    HyperGroupedRow(
                        isFirst = index == 0,
                        isLast = index == state.transactions.lastIndex,
                    ) {
                        TransactionRow(row)
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(state: BankUiState) {
    if (state.totals.isEmpty()) return
    HyperCard {
        state.totals.forEach { totals ->
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = Money.currencyName(totals.currency),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Total(
                        label = stringResource(R.string.bank_deposits),
                        amount = totals.deposited,
                        color = LocalHyperColors.current.success,
                    )
                    Total(
                        label = stringResource(R.string.bank_withdrawals),
                        amount = totals.withdrawn,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Total(
                        label = stringResource(R.string.bank_net),
                        amount = totals.net,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun Total(label: String, amount: Long, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Money.format(amount),
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

@Composable
private fun Filters(state: BankUiState, viewModel: BankViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BankFilter.entries.forEach { option ->
            FilterChip(
                selected = state.filter == option,
                onClick = { viewModel.setFilter(option) },
                label = { Text(stringResource(filterLabel(option))) },
            )
        }
    }
}

@Composable
private fun Accounts(state: BankUiState, viewModel: BankViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = state.accountKey == null,
            onClick = { viewModel.setAccount(null) },
            label = { Text(stringResource(R.string.bank_all_accounts)) },
        )
        state.accounts.forEach { account ->
            FilterChip(
                selected = state.accountKey == account.accountKey,
                onClick = { viewModel.setAccount(account.accountKey) },
                label = {
                    Text(
                        text = account.accountLabel ?: account.address,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun TransactionRow(row: BankTxEntity) {
    val deposit = row.kind == BankTxKind.DEPOSIT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.accountLabel ?: row.address,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = TimeFormat.full(row.at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.balance != null) {
                Text(
                    text = stringResource(R.string.bank_balance, Money.format(row.balance)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = (if (deposit) "+" else "−") + Money.format(row.amount),
                style = MaterialTheme.typography.titleMedium,
                color = if (deposit) {
                    LocalHyperColors.current.success
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                text = Money.currencyName(row.currency),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun filterLabel(filter: BankFilter): Int = when (filter) {
    BankFilter.ALL -> R.string.bank_all
    BankFilter.DEPOSIT -> R.string.bank_deposits
    BankFilter.WITHDRAWAL -> R.string.bank_withdrawals
}
