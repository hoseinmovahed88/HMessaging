package com.hmessaging.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.model.DeliveryStatus
import com.hmessaging.data.model.MessageType
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.DateTimePickerDialog
import com.hmessaging.ui.components.HyperDetailScreen
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.theme.LocalHyperColors
import com.hmessaging.util.Clipboards
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat
import kotlinx.coroutines.launch

private const val BubbleCorner = 20
private const val BubbleTail = 6
private const val BubbleMaxWidth = 290
private const val ComposerMaxLines = 6
private const val DefaultScheduleOffsetMs = 60L * 60 * 1000

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

    val thread = state.thread
    val title = thread?.let { it.contactName ?: PhoneNumbers.format(it.address) }.orEmpty()
    val subtitle = thread?.address?.takeIf { it != title }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }

    HyperDetailScreen(
        title = title,
        subtitle = subtitle,
        onBack = {
            viewModel.saveDraft()
            onBack()
        },
        snackbarHostState = snackbarHost,
        actions = {
            Box {
                HyperIconButton(Icons.Filled.MoreVert, null, { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (thread?.pinned == true) R.string.unpin else R.string.pin)) },
                        onClick = {
                            viewModel.setPinned(thread?.pinned != true)
                            menuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (thread?.muted == true) R.string.unmute else R.string.mute)) },
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
        bottomBar = {
            Composer(
                state = state,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onSchedule = { showSchedulePicker = true },
                onTemplates = { showTemplates = true },
                onSimPicker = { showSimPicker = true },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
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
    }

    if (showSchedulePicker) {
        DateTimePickerDialog(
            initialEpochMillis = System.currentTimeMillis() + DefaultScheduleOffsetMs,
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text = TimeFormat.dayHeader(date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
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
    val hyper = LocalHyperColors.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
    ) {
        Box {
            Surface(
                color = if (incoming) hyper.bubbleIncoming else hyper.bubbleOutgoing,
                shape = RoundedCornerShape(
                    topStart = BubbleCorner.dp,
                    topEnd = BubbleCorner.dp,
                    bottomStart = if (incoming) BubbleTail.dp else BubbleCorner.dp,
                    bottomEnd = if (incoming) BubbleCorner.dp else BubbleTail.dp,
                ),
                modifier = Modifier
                    .widthIn(max = BubbleMaxWidth.dp)
                    .clickable { menuOpen = true },
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (incoming) hyper.onBubbleIncoming else hyper.onBubbleOutgoing,
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val metaColor = if (incoming) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            hyper.onBubbleOutgoing.copy(alpha = 0.75f)
                        }
                        if (message.parts > 1) {
                            Text(
                                text = "${message.parts}×",
                                style = MaterialTheme.typography.labelSmall,
                                color = metaColor,
                            )
                        }
                        Text(
                            text = TimeFormat.clock(message.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor,
                        )
                        if (!incoming) {
                            Text(
                                text = statusLabel(message),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.status == DeliveryStatus.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    metaColor
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
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (state.input.isNotEmpty()) {
                LengthCounter(state)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                HyperIconButton(
                    icon = Icons.Filled.Bookmark,
                    contentDescription = stringResource(R.string.nav_templates),
                    onClick = onTemplates,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.type_a_message),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge),
                    maxLines = ComposerMaxLines,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (state.simSlots.size > 1) {
                    HyperIconButton(
                        icon = Icons.Filled.SimCard,
                        contentDescription = stringResource(R.string.settings_sim),
                        onClick = onSimPicker,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onSchedule,
                    enabled = state.input.isNotBlank(),
                ) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = stringResource(R.string.schedule_send),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = if (state.canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .padding(start = 4.dp, bottom = 4.dp)
                        .size(44.dp),
                ) {
                    IconButton(onClick = onSend, enabled = state.canSend) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = stringResource(R.string.send),
                            tint = if (state.canSend) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LengthCounter(state: ThreadUiState) {
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
        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
    )
}

private fun statusLabel(message: MessageEntity): String = when (message.status) {
    DeliveryStatus.PENDING -> "· · ·"
    DeliveryStatus.SENT -> "✓"
    DeliveryStatus.DELIVERED -> "✓✓"
    DeliveryStatus.FAILED -> "!"
    DeliveryStatus.NONE -> ""
}

private fun isSameDay(first: Long, second: Long): Boolean =
    TimeFormat.startOfDay(first) == TimeFormat.startOfDay(second)
