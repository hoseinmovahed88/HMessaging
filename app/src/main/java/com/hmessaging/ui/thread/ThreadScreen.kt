package com.hmessaging.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.model.DeliveryStatus
import com.hmessaging.data.model.MessageType
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.BackButton
import com.hmessaging.ui.components.DateTimePickerDialog
import com.hmessaging.util.Clipboards
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    onBack: () -> Unit,
    viewModel: ThreadViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var menuOpen by remember { mutableStateOf(false) }
    var showSchedulePicker by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var showSimPicker by remember { mutableStateOf(false) }

    val title = state.thread?.let { it.contactName ?: PhoneNumbers.format(it.address) }.orEmpty()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton {
                        viewModel.saveDraft()
                        onBack()
                    }
                },
                title = {
                    Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            val thread = state.thread
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(if (thread?.pinned == true) R.string.unpin else R.string.pin))
                                },
                                onClick = {
                                    viewModel.setPinned(thread?.pinned != true)
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(if (thread?.muted == true) R.string.unmute else R.string.mute))
                                },
                                onClick = {
                                    viewModel.setMuted(thread?.muted != true)
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (thread?.archived == true) R.string.unarchive else R.string.archive,
                                        ),
                                    )
                                },
                                onClick = {
                                    viewModel.setArchived(thread?.archived != true)
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_conversation)) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        val text = viewModel.exportText()
                                        Clipboards.copy(context, title, text)
                                        snackbarHost.showSnackbar(context.getString(R.string.otp_copied))
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.block_number)) },
                                onClick = {
                                    viewModel.blockSender()
                                    menuOpen = false
                                    onBack()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_conversation)) },
                                onClick = {
                                    viewModel.deleteThread()
                                    menuOpen = false
                                    onBack()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    count = state.messages.size,
                    key = { index -> state.messages[index].id },
                ) { index ->
                    val message = state.messages[index]
                    val previous = state.messages.getOrNull(index - 1)
                    if (previous == null || !isSameDay(previous.date, message.date)) {
                        DayHeader(message.date)
                    }
                    MessageBubble(
                        message = message,
                        onCopy = { Clipboards.copy(context, title, message.body) },
                        onResend = { viewModel.resend(message) },
                        onDelete = { viewModel.deleteMessage(message.id) },
                    )
                }
            }

            Composer(
                state = state,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onSchedule = { showSchedulePicker = true },
                onTemplates = { showTemplates = true },
                onSimPicker = { showSimPicker = true },
            )
        }
    }

    if (showSchedulePicker) {
        DateTimePickerDialog(
            initialEpochMillis = System.currentTimeMillis() + DEFAULT_SCHEDULE_OFFSET_MS,
            onDismiss = { showSchedulePicker = false },
            onConfirm = { at ->
                showSchedulePicker = false
                viewModel.scheduleSend(at, RepeatMode.NONE)
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

    if (showSimPicker && state.simSlots.isNotEmpty()) {
        SimPickerDialog(
            slots = state.simSlots,
            selectedSubscriptionId = state.selectedSubscriptionId,
            onDismiss = { showSimPicker = false },
            onPick = {
                viewModel.onSubscriptionChange(it)
                showSimPicker = false
            },
        )
    }
}

@Composable
private fun DayHeader(date: Long) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = TimeFormat.dayHeader(date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    onCopy: () -> Unit,
    onResend: () -> Unit,
    onDelete: () -> Unit,
) {
    val incoming = message.type.isIncoming
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
    ) {
        Box {
            Surface(
                color = if (incoming) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                shape = RoundedCornerShape(
                    topStart = BUBBLE_RADIUS.dp,
                    topEnd = BUBBLE_RADIUS.dp,
                    bottomStart = if (incoming) BUBBLE_TAIL.dp else BUBBLE_RADIUS.dp,
                    bottomEnd = if (incoming) BUBBLE_RADIUS.dp else BUBBLE_TAIL.dp,
                ),
                modifier = Modifier
                    .widthIn(max = BUBBLE_MAX_WIDTH.dp)
                    .clickable { menuOpen = true },
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (incoming) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = TimeFormat.clock(message.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (message.parts > 1) {
                            Text(
                                text = "${message.parts}×",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (!incoming) {
                            Text(
                                text = statusLabel(message),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.status == DeliveryStatus.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        }
                    }
                    if (message.status == DeliveryStatus.FAILED && message.errorMessage != null) {
                        Text(
                            text = message.errorMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy)) },
                    onClick = {
                        onCopy()
                        menuOpen = false
                    },
                )
                if (message.type == MessageType.FAILED) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.send)) },
                        onClick = {
                            onResend()
                            menuOpen = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = {
                        onDelete()
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Composer(
    state: ThreadUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSchedule: () -> Unit,
    onTemplates: () -> Unit,
    onSimPicker: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    placeholder = { Text(stringResource(R.string.type_a_message)) },
                    modifier = Modifier.weight(1f),
                    maxLines = COMPOSER_MAX_LINES,
                )
                IconButton(onClick = onSend, enabled = state.canSend) {
                    Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.send))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onTemplates) {
                    Icon(Icons.Filled.Bookmark, contentDescription = stringResource(R.string.nav_templates))
                }
                IconButton(onClick = onSchedule, enabled = state.input.isNotBlank()) {
                    Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.schedule_send))
                }
                if (state.simSlots.size > 1) {
                    IconButton(onClick = onSimPicker) {
                        Icon(Icons.Filled.SimCard, contentDescription = stringResource(R.string.settings_sim))
                    }
                }
                Box(modifier = Modifier.weight(1f))
                LengthCounter(state)
            }
        }
    }
}

@Composable
private fun LengthCounter(state: ThreadUiState) {
    val length = state.length
    if (state.input.isEmpty()) return
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
        modifier = Modifier.padding(end = 8.dp),
    )
}

private fun statusLabel(message: MessageEntity): String = when (message.status) {
    DeliveryStatus.PENDING -> "…"
    DeliveryStatus.SENT -> "✓"
    DeliveryStatus.DELIVERED -> "✓✓"
    DeliveryStatus.FAILED -> "!"
    DeliveryStatus.NONE -> ""
}

private fun isSameDay(first: Long, second: Long): Boolean =
    TimeFormat.startOfDay(first) == TimeFormat.startOfDay(second)

private const val BUBBLE_RADIUS = 16
private const val BUBBLE_TAIL = 4
private const val BUBBLE_MAX_WIDTH = 300
private const val COMPOSER_MAX_LINES = 8
private const val DEFAULT_SCHEDULE_OFFSET_MS = 60L * 60 * 1000
