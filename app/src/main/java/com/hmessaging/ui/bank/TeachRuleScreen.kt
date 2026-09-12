@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hmessaging.ui.bank

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.model.BankTxKind
import com.hmessaging.feature.bank.NumberSpan
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.EmptyState
import com.hmessaging.ui.components.HyperCard
import com.hmessaging.ui.components.HyperDetailScreen
import com.hmessaging.ui.components.HyperGroupTitle
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat

/**
 * Teaching the app one bank's format: pick a real message, then say which number is which.
 *
 * This replaces a parser that guessed at these formats and got them wrong, which is not a fixable
 * kind of wrong — the wordings are not published, they differ per bank, and they change. One real
 * message answers what no amount of guessing can.
 */
@Composable
fun TeachRuleScreen(
    onDone: () -> Unit,
    viewModel: TeachRuleViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    HyperDetailScreen(
        title = stringResource(R.string.bank_teach_title),
        onBack = { if (state.picked != null) viewModel.back() else onDone() },
    ) { padding ->
        if (state.picked == null) {
            CandidateList(state, viewModel, Modifier.padding(padding))
        } else {
            Labeller(state, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun CandidateList(
    state: TeachUiState,
    viewModel: TeachRuleViewModel,
    modifier: Modifier,
) {
    if (!state.loading && state.candidates.isEmpty()) {
        EmptyState(text = stringResource(R.string.bank_teach_none), modifier = modifier)
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item("hint") { HyperGroupTitle(stringResource(R.string.bank_teach_pick)) }
        items(state.candidates, key = { it.id }) { message ->
            HyperCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = PhoneNumbers.format(message.address),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = TimeFormat.full(message.date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Button(
                        onClick = { viewModel.pick(message) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        Text(stringResource(R.string.bank_teach_use))
                    }
                }
            }
        }
    }
}

@Composable
private fun Labeller(
    state: TeachUiState,
    viewModel: TeachRuleViewModel,
    modifier: Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item("body") {
            HyperCard {
                Text(
                    text = state.picked?.body.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        item("which") { HyperGroupTitle(stringResource(R.string.bank_teach_which)) }
        item("fields") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                TeachField.entries.forEach { field ->
                    FilterChip(
                        selected = state.field == field,
                        onClick = { viewModel.setField(field) },
                        label = { Text(stringResource(fieldLabel(field)) + fieldValue(state, field)) },
                    )
                }
            }
        }

        item("numbers") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                state.numbers.forEach { span ->
                    val assigned = span == state.amount || span == state.balance || span == state.account
                    if (assigned) {
                        FilterChip(
                            selected = true,
                            onClick = { viewModel.assign(span) },
                            label = { Text(span.text) },
                        )
                    } else {
                        SuggestionChip(
                            onClick = { viewModel.assign(span) },
                            label = { Text(span.text) },
                        )
                    }
                }
            }
        }

        item("direction-title") { HyperGroupTitle(stringResource(R.string.bank_teach_direction)) }
        item("direction") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                BankTxKind.entries.forEach { kind ->
                    FilterChip(
                        selected = state.kind == kind,
                        onClick = { viewModel.setKind(kind) },
                        label = {
                            Text(
                                stringResource(
                                    if (kind == BankTxKind.DEPOSIT) {
                                        R.string.bank_deposits
                                    } else {
                                        R.string.bank_withdrawals
                                    },
                                ),
                            )
                        },
                    )
                }
                listOf("IRR", "IRT").forEach { currency ->
                    FilterChip(
                        selected = state.currency == currency,
                        onClick = { viewModel.setCurrency(currency) },
                        label = { Text(com.hmessaging.util.Money.currencyName(currency)) },
                    )
                }
            }
        }

        item("name") {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.field_name)) },
                supportingText = { Text(stringResource(R.string.bank_teach_name_desc)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }

        item("save") {
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

private fun fieldLabel(field: TeachField): Int = when (field) {
    TeachField.AMOUNT -> R.string.bank_teach_amount
    TeachField.BALANCE -> R.string.bank_teach_balance
    TeachField.ACCOUNT -> R.string.bank_teach_account
}

private fun fieldValue(state: TeachUiState, field: TeachField): String {
    val span: NumberSpan? = when (field) {
        TeachField.AMOUNT -> state.amount
        TeachField.BALANCE -> state.balance
        TeachField.ACCOUNT -> state.account
    }
    return span?.let { ": ${it.text}" }.orEmpty()
}
