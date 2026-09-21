package com.hmessaging.ui.blocked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.data.db.entity.BlockRuleEntity
import com.hmessaging.data.model.MatchTarget
import com.hmessaging.data.model.MatchType
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.HyperCard
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.components.HyperScreen
import com.hmessaging.ui.components.EmptyState
import com.hmessaging.ui.components.HyperSwitchRow
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedScreen(
    onOpenDrawer: () -> Unit,
    viewModel: BlockedViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<BlockRuleEntity?>(null) }

    HyperScreen(
        title = stringResource(R.string.nav_blocked),
        navigationIcon = { HyperIconButton(Icons.Filled.Menu, null, onOpenDrawer) },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = { editing = BlockRuleEntity(pattern = "") }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }) {
                    Text(stringResource(R.string.block_rules), modifier = Modifier.padding(12.dp))
                }
                Tab(selected = tab == 1, onClick = { tab = 1 }) {
                    // The count is the point of the tab: without it there is no way to tell a
                    // store that is empty from one that is not being written to.
                    Text(
                        text = if (state.blockedMessages.isEmpty()) {
                            stringResource(R.string.blocked_messages)
                        } else {
                            "${stringResource(R.string.blocked_messages)} (${state.blockedMessages.size})"
                        },
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (tab == 0) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    HyperSwitchRow(
                        title = stringResource(R.string.block_private_numbers),
                        checked = state.settings.blockPrivateNumbers,
                        onCheckedChange = viewModel::setBlockPrivateNumbers,
                    )
                    HyperSwitchRow(
                        title = stringResource(R.string.block_non_contacts),
                        checked = state.settings.blockNonContacts,
                        onCheckedChange = viewModel::setBlockNonContacts,
                    )
                    HyperSwitchRow(
                        title = stringResource(R.string.block_calls_too),
                        checked = state.settings.screenCalls,
                        onCheckedChange = viewModel::setScreenCalls,
                    )
                    // Named for what it does rather than for what it is about; it used to carry
                    // the same words as the tab beside it, which read as a second way in rather
                    // than as the switch that decides whether there is anything in there.
                    HyperSwitchRow(
                        title = stringResource(R.string.keep_blocked_messages),
                        subtitle = stringResource(R.string.keep_blocked_messages_desc),
                        checked = state.settings.keepBlockedMessages,
                        onCheckedChange = viewModel::setKeepBlockedMessages,
                    )

                    if (state.rules.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_block_rules),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        state.rules.forEach { rule ->
                            RuleCard(
                                rule = rule,
                                onToggle = { viewModel.setRuleEnabled(rule.id, it) },
                                onEdit = { editing = rule },
                                onDelete = { viewModel.deleteRule(rule.id) },
                            )
                        }
                    }
                }
            } else {
                if (state.blockedMessages.isEmpty()) {
                    EmptyState(
                        text = if (state.settings.keepBlockedMessages) {
                            stringResource(R.string.no_blocked_messages)
                        } else {
                            stringResource(R.string.blocked_messages_not_kept)
                        },
                        icon = Icons.Filled.Block,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(state.blockedMessages, key = { it.id }) { message ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = PhoneNumbers.format(message.address),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = message.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "${TimeFormat.full(message.date)} · ${message.reason}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    Row {
                                        TextButton(onClick = { viewModel.restoreBlockedMessage(message) }) {
                                            Text(stringResource(R.string.unblock_and_restore))
                                        }
                                        TextButton(onClick = { viewModel.deleteBlockedMessage(message.id) }) {
                                            Text(stringResource(R.string.delete))
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            TextButton(
                                onClick = viewModel::clearBlockedMessages,
                                modifier = Modifier.padding(16.dp),
                            ) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }

    editing?.let { rule ->
        BlockRuleDialog(
            initial = rule,
            onDismiss = { editing = null },
            onSave = {
                viewModel.saveRule(it)
                editing = null
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule: BlockRuleEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.pattern, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${matchTypeLabel(rule.matchType)} · ${matchTargetLabel(rule.target)}" +
                        if (rule.hitCount > 0) " · ${rule.hitCount}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rule.note?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun BlockRuleDialog(
    initial: BlockRuleEntity,
    onDismiss: () -> Unit,
    onSave: (BlockRuleEntity) -> Unit,
) {
    var pattern by remember { mutableStateOf(initial.pattern) }
    var note by remember { mutableStateOf(initial.note.orEmpty()) }
    var matchType by remember { mutableStateOf(initial.matchType) }
    var target by remember { mutableStateOf(initial.target) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial.id == 0L) R.string.rule_new else R.string.rule_edit))
        },
        confirmButton = {
            TextButton(
                enabled = pattern.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            pattern = pattern.trim(),
                            note = note.ifBlank { null },
                            matchType = matchType,
                            target = target,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.recipient_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.block_rules), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MatchTarget.entries.forEach { option ->
                        FilterChip(
                            selected = target == option,
                            onClick = { target = option },
                            label = { Text(matchTargetLabel(option)) },
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MatchType.entries.chunked(CHIPS_PER_ROW).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { option ->
                                FilterChip(
                                    selected = matchType == option,
                                    onClick = { matchType = option },
                                    label = { Text(matchTypeLabel(option)) },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.field_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
private fun matchTypeLabel(type: MatchType): String = stringResource(
    when (type) {
        MatchType.EXACT -> R.string.block_type_exact
        MatchType.STARTS_WITH -> R.string.block_type_starts_with
        MatchType.ENDS_WITH -> R.string.block_type_ends_with
        MatchType.CONTAINS -> R.string.block_type_contains
        MatchType.REGEX -> R.string.block_type_regex
    },
)

@Composable
private fun matchTargetLabel(target: MatchTarget): String = stringResource(
    when (target) {
        MatchTarget.SENDER -> R.string.block_target_sender
        MatchTarget.BODY -> R.string.block_target_body
    },
)

private const val CHIPS_PER_ROW = 3
