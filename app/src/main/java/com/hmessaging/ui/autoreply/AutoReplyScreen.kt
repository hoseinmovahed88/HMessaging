package com.hmessaging.ui.autoreply

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
import androidx.compose.material3.FilterChip
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
import com.hmessaging.data.db.entity.AutoReplyRuleEntity
import com.hmessaging.data.model.SourceMatch
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.HyperCard
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.components.HyperScreen
import com.hmessaging.ui.components.DateTimePickerDialog
import com.hmessaging.ui.components.HyperSwitchRow
import com.hmessaging.ui.components.TimeOfDayPickerDialog
import com.hmessaging.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoReplyScreen(
    onOpenDrawer: () -> Unit,
    viewModel: AutoReplyViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AutoReplyRuleEntity?>(null) }
    var showAwayPicker by remember { mutableStateOf(false) }

    HyperScreen(
        title = stringResource(R.string.nav_auto_reply),
        navigationIcon = { HyperIconButton(Icons.Filled.Menu, null, onOpenDrawer) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editing = AutoReplyRuleEntity(name = "", replyText = "")
                },
            ) {
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
                title = stringResource(R.string.auto_reply_master),
                subtitle = stringResource(R.string.auto_reply_master_desc),
                checked = state.settings.autoReplyEnabled,
                onCheckedChange = viewModel::setEnabled,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.away_until), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (state.settings.awayModeActive) {
                            TimeFormat.full(state.settings.awayUntil)
                        } else {
                            stringResource(R.string.disabled)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showAwayPicker = true }) { Text(stringResource(R.string.edit)) }
                if (state.settings.awayModeActive) {
                    TextButton(onClick = { viewModel.setAwayUntil(0L) }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }

            Text(
                text = "${stringResource(R.string.nav_auto_reply)}: ${state.repliesToday}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            if (state.rules.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_auto_reply_rules),
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
    }

    editing?.let { rule ->
        AutoReplyRuleDialog(
            initial = rule,
            onDismiss = { editing = null },
            onSave = {
                viewModel.saveRule(it)
                editing = null
            },
        )
    }

    if (showAwayPicker) {
        DateTimePickerDialog(
            initialEpochMillis = System.currentTimeMillis() + DEFAULT_AWAY_MS,
            onDismiss = { showAwayPicker = false },
            onConfirm = {
                viewModel.setAwayUntil(it)
                showAwayPicker = false
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule: AutoReplyRuleEntity,
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
                    text = rule.name.ifBlank { stringResource(R.string.nav_auto_reply) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = rule.replyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${sourceMatchLabel(rule.matchType)} · " +
                        "${TimeFormat.formatMinuteOfDay(rule.startMinuteOfDay)}–" +
                        "${TimeFormat.formatMinuteOfDay(rule.endMinuteOfDay)} · " +
                        "${dayMaskLabel(rule.daysMask)}",
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
private fun AutoReplyRuleDialog(
    initial: AutoReplyRuleEntity,
    onDismiss: () -> Unit,
    onSave: (AutoReplyRuleEntity) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var replyText by remember { mutableStateOf(initial.replyText) }
    var matchType by remember { mutableStateOf(initial.matchType) }
    var pattern by remember { mutableStateOf(initial.pattern) }
    var daysMask by remember { mutableStateOf(initial.daysMask) }
    var startMinute by remember { mutableStateOf(initial.startMinuteOfDay) }
    var endMinute by remember { mutableStateOf(initial.endMinuteOfDay) }
    var cooldown by remember { mutableStateOf(initial.cooldownMinutes.toString()) }
    var maxPerDay by remember { mutableStateOf(initial.maxPerDay.toString()) }
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nav_auto_reply)) },
        confirmButton = {
            TextButton(
                enabled = replyText.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.ifBlank { replyText.take(RULE_NAME_LENGTH) },
                            replyText = replyText,
                            matchType = matchType,
                            pattern = pattern.trim(),
                            daysMask = daysMask,
                            startMinuteOfDay = startMinute,
                            endMinuteOfDay = endMinute,
                            cooldownMinutes = cooldown.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            maxPerDay = maxPerDay.toIntOrNull()?.coerceAtLeast(0) ?: 0,
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
                    label = { Text(stringResource(R.string.edit)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replyText,
                    onValueChange = { replyText = it },
                    label = { Text(stringResource(R.string.type_a_message)) },
                    supportingText = { Text(stringResource(R.string.placeholder_help)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SourceMatchChips(selected = matchType, onSelect = { matchType = it })
                if (matchType == SourceMatch.SENDER_PATTERN || matchType == SourceMatch.BODY_KEYWORD) {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        label = { Text(stringResource(R.string.search)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(R.string.active_days), style = MaterialTheme.typography.labelLarge)
                DayMaskChips(mask = daysMask, onToggle = { bit -> daysMask = daysMask xor (1 shl bit) })
                Text(stringResource(R.string.active_hours), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { editingStart = true }) {
                        Text(TimeFormat.formatMinuteOfDay(startMinute))
                    }
                    TextButton(onClick = { editingEnd = true }) {
                        Text(TimeFormat.formatMinuteOfDay(endMinute))
                    }
                }
                OutlinedTextField(
                    value = cooldown,
                    onValueChange = { cooldown = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.cooldown_minutes)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxPerDay,
                    onValueChange = { maxPerDay = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.max_per_day)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )

    if (editingStart) {
        TimeOfDayPickerDialog(
            initialMinuteOfDay = startMinute,
            onDismiss = { editingStart = false },
            onConfirm = {
                startMinute = it
                editingStart = false
            },
        )
    }
    if (editingEnd) {
        TimeOfDayPickerDialog(
            initialMinuteOfDay = endMinute,
            onDismiss = { editingEnd = false },
            onConfirm = {
                endMinute = it
                editingEnd = false
            },
        )
    }
}

@Composable
fun SourceMatchChips(selected: SourceMatch, onSelect: (SourceMatch) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SourceMatch.entries.chunked(CHIPS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { option ->
                    FilterChip(
                        selected = selected == option,
                        onClick = { onSelect(option) },
                        label = { Text(sourceMatchLabel(option)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayMaskChips(mask: Int, onToggle: (Int) -> Unit) {
    val labels = dayLabels()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = mask and (1 shl index) != 0,
                onClick = { onToggle(index) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun dayLabels(): List<String> = java.time.DayOfWeek.values().map {
    it.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault())
}

private fun dayMaskLabel(mask: Int): String =
    if (mask == AutoReplyRuleEntity.ALL_DAYS) "7/7" else Integer.bitCount(mask).toString() + "/7"

@Composable
fun sourceMatchLabel(match: SourceMatch): String = stringResource(
    when (match) {
        SourceMatch.ALL -> R.string.all
        SourceMatch.CONTACTS_ONLY -> R.string.match_contacts
        SourceMatch.NON_CONTACTS_ONLY -> R.string.match_non_contacts
        SourceMatch.SENDER_PATTERN -> R.string.block_target_sender
        SourceMatch.BODY_KEYWORD -> R.string.block_target_body
    },
)

private const val CHIPS_PER_ROW = 3
private const val RULE_NAME_LENGTH = 24
private const val DEFAULT_AWAY_MS = 8L * 60 * 60 * 1000
