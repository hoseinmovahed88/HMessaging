package com.hmessaging.ui.forward

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.hmessaging.data.db.entity.ForwardRuleEntity
import com.hmessaging.data.model.SourceMatch
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.HyperCard
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.components.HyperScreen
import com.hmessaging.ui.autoreply.SourceMatchChips
import com.hmessaging.ui.autoreply.sourceMatchLabel
import com.hmessaging.ui.components.SectionHeader
import com.hmessaging.ui.components.HyperSwitchRow
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardScreen(
    onOpenDrawer: () -> Unit,
    viewModel: ForwardViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ForwardRuleEntity?>(null) }

    HyperScreen(
        title = stringResource(R.string.nav_forwarding),
        navigationIcon = { HyperIconButton(Icons.Filled.Menu, null, onOpenDrawer) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = ForwardRuleEntity(name = "", targets = "") }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            HyperSwitchRow(
                title = stringResource(R.string.nav_forwarding),
                checked = state.settings.forwardingEnabled,
                onCheckedChange = viewModel::setEnabled,
            )
            OutlinedTextField(
                value = state.settings.forwardMaxPerHour.toString(),
                onValueChange = { value ->
                    value.filter(Char::isDigit).toIntOrNull()?.let(viewModel::setMaxPerHour)
                },
                label = { Text(stringResource(R.string.forward_max_per_hour)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (state.rules.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_forward_rules),
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

            if (state.log.isNotEmpty()) {
                SectionHeader(stringResource(R.string.forward_recent))
                state.log.take(LOG_PREVIEW_COUNT).forEach { entry ->
                    Text(
                        text = "${TimeFormat.full(entry.sentAt)} · ${PhoneNumbers.format(entry.fromAddress)} → " +
                            "${PhoneNumbers.format(entry.target)} · " +
                            (if (entry.success) "✓" else entry.error.orEmpty()),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.success) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }

    editing?.let { rule ->
        ForwardRuleDialog(
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
    rule: ForwardRuleEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name.ifBlank { stringResource(R.string.nav_forwarding) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = PhoneNumbers.splitRecipients(rule.targets)
                        .joinToString(", ") { PhoneNumbers.format(it) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = sourceMatchLabel(rule.matchType) +
                        if (rule.pattern.isNotBlank()) " · ${rule.pattern}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
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
private fun ForwardRuleDialog(
    initial: ForwardRuleEntity,
    onDismiss: () -> Unit,
    onSave: (ForwardRuleEntity) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var targets by remember { mutableStateOf(initial.targets) }
    var matchType by remember { mutableStateOf(initial.matchType) }
    var pattern by remember { mutableStateOf(initial.pattern) }
    var includeSender by remember { mutableStateOf(initial.includeSender) }
    var includeTimestamp by remember { mutableStateOf(initial.includeTimestamp) }
    var template by remember { mutableStateOf(initial.template.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial.id == 0L) R.string.rule_new else R.string.rule_edit))
        },
        confirmButton = {
            TextButton(
                enabled = PhoneNumbers.splitRecipients(targets).isNotEmpty(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.ifBlank { targets.take(RULE_NAME_LENGTH) },
                            targets = PhoneNumbers.joinRecipients(PhoneNumbers.splitRecipients(targets)),
                            matchType = matchType,
                            pattern = pattern.trim(),
                            includeSender = includeSender,
                            includeTimestamp = includeTimestamp,
                            template = template.ifBlank { null },
                        ),
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = targets,
                    onValueChange = { targets = it },
                    label = { Text(stringResource(R.string.forward_targets)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SourceMatchChips(selected = matchType, onSelect = { matchType = it })
                if (matchType == SourceMatch.SENDER_PATTERN || matchType == SourceMatch.BODY_KEYWORD) {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        label = { Text(stringResource(R.string.field_pattern)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HyperSwitchRow(
                    title = stringResource(R.string.forward_include_sender),
                    checked = includeSender,
                    onCheckedChange = { includeSender = it },
                )
                HyperSwitchRow(
                    title = stringResource(R.string.forward_include_time),
                    checked = includeTimestamp,
                    onCheckedChange = { includeTimestamp = it },
                )
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text(stringResource(R.string.forward_template)) },
                    supportingText = { Text(stringResource(R.string.placeholder_help)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

private const val RULE_NAME_LENGTH = 24
private const val LOG_PREVIEW_COUNT = 20
