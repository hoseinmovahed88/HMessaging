package com.hmessaging.ui.scheduled

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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.data.db.entity.ScheduledMessageEntity
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.data.model.ScheduleStatus
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.DateTimePickerDialog
import com.hmessaging.ui.components.EmptyState
import com.hmessaging.ui.components.SectionHeader
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(
    onOpenDrawer: () -> Unit,
    viewModel: ScheduledViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ScheduledMessageEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = null) }
                },
                title = { Text(stringResource(R.string.nav_scheduled)) },
                actions = {
                    TextButton(onClick = viewModel::clearFinished) {
                        Text(stringResource(R.string.delete))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editing = ScheduledMessageEntity(
                        recipients = "",
                        body = "",
                        scheduledAt = System.currentTimeMillis() + DEFAULT_OFFSET_MS,
                    )
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!state.exactAlarmsAllowed) {
                Text(
                    text = stringResource(R.string.schedule_inexact_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (state.pending.isEmpty() && state.history.isEmpty()) {
                EmptyState(
                    text = stringResource(R.string.no_scheduled),
                    icon = Icons.Filled.Schedule,
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (state.pending.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.schedule_pending)) }
                        items(state.pending, key = { it.id }) { message ->
                            ScheduledCard(
                                message = message,
                                onEdit = { editing = message },
                                onCancel = { viewModel.cancel(message.id) },
                                onSendNow = { viewModel.sendNow(message.id) },
                                onDelete = { viewModel.delete(message.id) },
                            )
                        }
                    }
                    if (state.history.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.schedule_sent)) }
                        items(state.history, key = { "history-${it.id}" }) { message ->
                            ScheduledCard(
                                message = message,
                                onEdit = { editing = message },
                                onCancel = null,
                                onSendNow = null,
                                onDelete = { viewModel.delete(message.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { draft ->
        ScheduleEditorDialog(
            initial = draft,
            onDismiss = { editing = null },
            onSave = {
                viewModel.save(it)
                editing = null
            },
        )
    }
}

@Composable
private fun ScheduledCard(
    message: ScheduledMessageEntity,
    onEdit: () -> Unit,
    onCancel: (() -> Unit)?,
    onSendNow: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = PhoneNumbers.splitRecipients(message.recipients)
                    .joinToString(", ") { PhoneNumbers.format(it) },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = TimeFormat.full(message.scheduledAt) + repeatSuffix(message.repeat),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = statusLabel(message.status) + (message.lastError?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (message.status == ScheduleStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                onSendNow?.let { TextButton(onClick = it) { Text(stringResource(R.string.send_now)) } }
                onCancel?.let { TextButton(onClick = it) { Text(stringResource(R.string.cancel)) } }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

@Composable
private fun ScheduleEditorDialog(
    initial: ScheduledMessageEntity,
    onDismiss: () -> Unit,
    onSave: (ScheduledMessageEntity) -> Unit,
) {
    var recipients by remember { mutableStateOf(initial.recipients) }
    var body by remember { mutableStateOf(initial.body) }
    var at by remember { mutableStateOf(initial.scheduledAt) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_send)) },
        confirmButton = {
            TextButton(
                enabled = body.isNotBlank() && PhoneNumbers.splitRecipients(recipients).isNotEmpty(),
                onClick = {
                    onSave(
                        initial.copy(
                            recipients = PhoneNumbers.joinRecipients(PhoneNumbers.splitRecipients(recipients)),
                            body = body,
                            scheduledAt = at,
                            repeat = repeat,
                            status = ScheduleStatus.PENDING,
                            lastError = null,
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
                    value = recipients,
                    onValueChange = { recipients = it },
                    label = { Text(stringResource(R.string.recipient_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(stringResource(R.string.type_a_message)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showPicker = true }) {
                    Text(TimeFormat.full(at))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RepeatMode.entries.forEach { mode ->
                        FilterChip(
                            selected = repeat == mode,
                            onClick = { repeat = mode },
                            label = { Text(repeatLabel(mode)) },
                        )
                    }
                }
            }
        },
    )

    if (showPicker) {
        DateTimePickerDialog(
            initialEpochMillis = at,
            onDismiss = { showPicker = false },
            onConfirm = {
                at = it
                showPicker = false
            },
        )
    }
}

@Composable
private fun repeatLabel(mode: RepeatMode): String = stringResource(
    when (mode) {
        RepeatMode.NONE -> R.string.repeat_none
        RepeatMode.HOURLY -> R.string.repeat_hourly
        RepeatMode.DAILY -> R.string.repeat_daily
        RepeatMode.WEEKLY -> R.string.repeat_weekly
        RepeatMode.MONTHLY -> R.string.repeat_monthly
    },
)

@Composable
private fun repeatSuffix(mode: RepeatMode): String =
    if (mode == RepeatMode.NONE) "" else " · " + repeatLabel(mode)

@Composable
private fun statusLabel(status: ScheduleStatus): String = stringResource(
    when (status) {
        ScheduleStatus.PENDING -> R.string.schedule_pending
        ScheduleStatus.SENT -> R.string.schedule_sent
        ScheduleStatus.FAILED -> R.string.schedule_failed
        ScheduleStatus.CANCELLED -> R.string.schedule_cancelled
    },
)

private const val DEFAULT_OFFSET_MS = 60L * 60 * 1000
