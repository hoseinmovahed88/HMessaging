package com.hmessaging.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.HyperDetailScreen
import com.hmessaging.ui.components.DateTimePickerDialog
import com.hmessaging.ui.thread.TemplatePickerDialog
import com.hmessaging.util.PhoneNumbers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(
    onBack: () -> Unit,
    onOpenThread: (Long) -> Unit,
    viewModel: NewMessageViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var showSchedulePicker by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }

    HyperDetailScreen(
        title = stringResource(R.string.new_message),
        onBack = onBack,
        snackbarHostState = snackbarHost,
        actions = {
            IconButton(onClick = { showTemplates = true }) {
                Icon(Icons.Filled.Bookmark, contentDescription = stringResource(R.string.nav_templates))
            }
            IconButton(onClick = { showSchedulePicker = true }, enabled = state.canSend) {
                Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.schedule_send))
            }
            IconButton(
                onClick = {
                    viewModel.send { threadId ->
                        if (threadId != null) onOpenThread(threadId) else onBack()
                    }
                },
                enabled = state.canSend,
            ) {
                Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.send))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.recipients,
                onValueChange = viewModel::onRecipientsChange,
                label = { Text(stringResource(R.string.recipient_hint)) },
                shape = RoundedCornerShape(18.dp),
                supportingText = {
                    val parsed = state.parsedRecipients
                    if (parsed.isNotEmpty()) {
                        Text(parsed.joinToString(", ") { PhoneNumbers.format(it) })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )

            if (state.simSlots.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.simSlots.forEach { slot ->
                        AssistChip(
                            onClick = { viewModel.onSubscriptionChange(slot.subscriptionId) },
                            label = { Text(slot.displayName) },
                            leadingIcon = if (state.selectedSubscriptionId == slot.subscriptionId) {
                                { Icon(Icons.Filled.Send, contentDescription = null) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::onBodyChange,
                label = { Text(stringResource(R.string.type_a_message)) },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            val length = state.length
            Text(
                text = stringResource(
                    R.string.parts_counter,
                    length.usedInCurrentPart,
                    length.limitForCurrentPart,
                    length.parts,
                ) + " · " + stringResource(
                    if (length.isUnicode) R.string.encoding_unicode else R.string.encoding_gsm,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showSchedulePicker) {
        DateTimePickerDialog(
            initialEpochMillis = System.currentTimeMillis() + DEFAULT_SCHEDULE_OFFSET_MS,
            onDismiss = { showSchedulePicker = false },
            onConfirm = { at ->
                showSchedulePicker = false
                viewModel.schedule(at, RepeatMode.NONE) { onBack() }
            },
        )
    }

    if (showTemplates) {
        TemplatePickerDialog(
            templates = state.templates,
            onDismiss = { showTemplates = false },
            onPick = {
                viewModel.applyTemplate(it)
                showTemplates = false
            },
        )
    }
}

private const val DEFAULT_SCHEDULE_OFFSET_MS = 60L * 60 * 1000
