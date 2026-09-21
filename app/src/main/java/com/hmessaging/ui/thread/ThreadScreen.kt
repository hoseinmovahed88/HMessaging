@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.hmessaging.ui.thread

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
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
import com.hmessaging.ui.components.ContactAvatar
import com.hmessaging.ui.components.HyperDetailScreen
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.components.MessageComposer
import com.hmessaging.ui.components.SimBadge
import com.hmessaging.ui.theme.LocalHyperColors
import com.hmessaging.util.Calls
import com.hmessaging.util.Clipboards
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.Sharing
import com.hmessaging.util.TimeFormat
import kotlinx.coroutines.launch

private const val BubbleCorner = 20
private const val BubbleTail = 6
private const val BubbleMaxWidth = 290
private const val ComposerMaxLines = 6
private const val DefaultScheduleOffsetMs = 60L * 60 * 1000
private const val SelectedTint = 0.16f

/** How far past the newest message the list must be pulled to move on. */
private const val NextThreadPullDp = 96

/** Only part of the drag counts, so the pull has weight rather than snapping open. */
private const val NextThreadPullResistance = 0.55f

private const val HintMinimumAlpha = 0.35f
private const val HintRiseDp = 20
private const val HintMaxWidthDp = 220

@Composable
fun ThreadScreen(
    onBack: () -> Unit,
    onForward: (String) -> Unit = {},
    onOpenThread: (Long) -> Unit = {},
    onTeachBank: (Long) -> Unit = {},
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
    var detail by remember { mutableStateOf<MessageEntity?>(null) }
    var showAccounts by remember { mutableStateOf(false) }

    val thread = state.thread
    val title = thread?.let { it.contactName ?: PhoneNumbers.format(it.address) }.orEmpty()
    val subtitle = thread?.address?.takeIf { it != title }

    // Pulling past the newest message moves to the conversation below this one in the list, so a
    // morning's worth of new messages can be read straight through without coming back out to the
    // list between each one.
    val nextThreadId = state.nextThreadId
    val pullThreshold = with(LocalDensity.current) { NextThreadPullDp.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(nextThreadId) { pull = 0f }

    val nextThreadPull = remember(nextThreadId, state.selecting, pullThreshold) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Only a finger counts. A fling that runs out of list at speed is someone
                // travelling through this conversation, not someone asking for the next one.
                if (nextThreadId == null || state.selecting || source != NestedScrollSource.Drag) {
                    return Offset.Zero
                }
                if (available.y >= 0f) {
                    // Reaching back towards older messages abandons a pull in progress.
                    pull = 0f
                    return Offset.Zero
                }
                pull = (pull - available.y * NextThreadPullResistance)
                    .coerceAtMost(pullThreshold * 2)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val reached = pull >= pullThreshold
                pull = 0f
                if (reached && nextThreadId != null) onOpenThread(nextThreadId)
                return Velocity.Zero
            }
        }
    }

    // The list is reversed, so index 0 is the newest message and "at the bottom" is index 0. A new
    // message pushes the one the user is looking at to index 1, so treat that as still at the
    // bottom; anything further up means they have scrolled back and must not be yanked away.
    val newestId = state.messages.lastOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null && listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // In selection mode the bar belongs to the selection: back clears it rather than leaving the
    // conversation, which is what every list with a selection mode does.
    BackHandler(enabled = state.selecting) { viewModel.clearSelection() }

    HyperDetailScreen(
        title = if (state.selecting) {
            stringResource(R.string.selected_count, state.selected.size)
        } else {
            title
        },
        subtitle = if (state.selecting) null else subtitle,
        titleLeading = if (state.selecting) {
            null
        } else {
            { ContactAvatar(name = title, address = thread?.address, size = 34) }
        },
        onBack = {
            if (state.selecting) {
                viewModel.clearSelection()
            } else {
                viewModel.saveDraft()
                onBack()
            }
        },
        snackbarHostState = snackbarHost,
        actions = {
            if (state.selecting) {
                HyperIconButton(
                    icon = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                    onClick = {
                        Clipboards.copy(context, title, viewModel.selectedText())
                        viewModel.clearSelection()
                    },
                )
                HyperIconButton(
                    icon = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.share),
                    onClick = {
                        Sharing.shareText(context, viewModel.selectedText())
                        viewModel.clearSelection()
                    },
                )
                HyperIconButton(
                    icon = Icons.Filled.Forward,
                    contentDescription = stringResource(R.string.forward),
                    onClick = {
                        val text = viewModel.selectedText()
                        viewModel.clearSelection()
                        onForward(text)
                    },
                )
                HyperIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete),
                    onClick = viewModel::deleteSelected,
                )
            } else {
                // A conversation is usually with someone you might also ring. The number is right
                // here; making the user copy it into the dialer was the only thing stopping them.
                if (Calls.isDialable(thread?.address)) {
                    HyperIconButton(
                        icon = Icons.Filled.Call,
                        contentDescription = stringResource(R.string.call),
                        onClick = { Calls.dial(context, thread?.address) },
                    )
                }
                if (state.bankAccounts.isNotEmpty()) {
                    HyperIconButton(
                        icon = Icons.Filled.AccountBalance,
                        contentDescription = stringResource(R.string.bank_accounts_title),
                        onClick = { showAccounts = true },
                    )
                }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Reversed: the list is anchored at the bottom, so a conversation opens on its newest
            // message with no scrolling, and when the keyboard shrinks the viewport the newest
            // messages stay put instead of sliding underneath it.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nextThreadPull),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                reverseLayout = true,
            ) {
                items(
                    count = state.messages.size,
                    key = { index -> state.messages[state.messages.lastIndex - index].id },
                ) { index ->
                    // One column per item: a reversed list flips the order of an item's own children
                    // too, which would put the day header underneath its messages.
                    val position = state.messages.lastIndex - index
                    val message = state.messages[position]
                    val previous = state.messages.getOrNull(position - 1)
                    Column {
                        if (previous == null || !isSameDay(previous.date, message.date)) {
                            DayHeader(message.date)
                        }
                        MessageBubble(
                            message = message,
                            selected = message.id in state.selected,
                            onTap = {
                                if (state.selecting) {
                                    viewModel.toggleSelected(message.id)
                                } else {
                                    detail = message
                                }
                            },
                            onLongPress = { viewModel.toggleSelected(message.id) },
                        )
                    }
                }
            }

            if (nextThreadId != null && pull > 0f) {
                NextConversationHint(
                    title = state.nextThreadTitle.orEmpty(),
                    progress = (pull / pullThreshold).coerceIn(0f, 1f),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    detail?.let { message ->
        MessageDetailSheet(
            message = message,
            onDismiss = { detail = null },
            onCopy = { text ->
                Clipboards.copy(context, title, text)
                detail = null
            },
            onShare = { text ->
                Sharing.shareText(context, text)
                detail = null
            },
            onForward = { text ->
                detail = null
                onForward(text)
            },
            onDelete = {
                viewModel.deleteMessage(message.id)
                detail = null
            },
            // Offered on received messages only: a bank's format is taught from what the bank
            // wrote, never from a reply to it.
            onTeachBank = if (message.type.isIncoming) {
                {
                    detail = null
                    onTeachBank(message.id)
                }
            } else {
                null
            },
        )
    }

    if (showAccounts) {
        BankAccountsSheet(
            accounts = state.bankAccounts,
            transactions = state.bankTransactions,
            onDismiss = { showAccounts = false },
        )
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

/**
 * The badge that rises from the bottom as the conversation is pulled past its newest message.
 *
 * It names where the pull leads, because a gesture that silently replaces what is on screen is
 * one the reader has to try before they can know what it does.
 */
@Composable
private fun NextConversationHint(title: String, progress: Float, modifier: Modifier = Modifier) {
    val ready = progress >= 1f
    val background = if (ready) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (ready) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = background,
        shadowElevation = 3.dp,
        modifier = modifier
            .padding(bottom = 14.dp)
            .graphicsLayer {
                // Fades and climbs with the pull, so how far is left to go is visible rather
                // than guessed at.
                alpha = HintMinimumAlpha + (1f - HintMinimumAlpha) * progress
                translationY = (1f - progress) * HintRiseDp.dp.toPx()
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(
                    if (ready) R.string.next_thread_release else R.string.next_thread_pull,
                    title,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = HintMaxWidthDp.dp),
            )
        }
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
    state: ThreadUiState,
    message: MessageEntity,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val incoming = message.type.isIncoming
    val hyper = LocalHyperColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The tint spans the row, not the bubble, so a selected message reads as a selected
            // line in a list rather than a differently coloured bubble.
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = SelectedTint) else Color.Transparent,
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(vertical = 1.dp),
        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
    ) {
        Surface(
            color = if (incoming) hyper.bubbleIncoming else hyper.bubbleOutgoing,
            shape = RoundedCornerShape(
                topStart = BubbleCorner.dp,
                topEnd = BubbleCorner.dp,
                bottomStart = if (incoming) BubbleTail.dp else BubbleCorner.dp,
                bottomEnd = if (incoming) BubbleCorner.dp else BubbleTail.dp,
            ),
            modifier = Modifier.widthIn(max = BubbleMaxWidth.dp),
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
                    SimBadge(
                        subscriptionId = message.subscriptionId,
                        slots = state.simSlots,
                        color = metaColor,
                    )
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
    // The same composer the new-message screen uses, so writing the first message to someone and
    // writing the next one are not two different-looking acts.
    MessageComposer(
        value = state.input,
        onValueChange = onInputChange,
        onSend = onSend,
        canSend = state.canSend,
        onSchedule = onSchedule,
        above = { if (state.input.isNotEmpty()) LengthCounter(state) },
        leading = {
            HyperIconButton(
                icon = Icons.Filled.Bookmark,
                contentDescription = stringResource(R.string.nav_templates),
                onClick = onTemplates,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailing = {
            if (state.simSlots.size > 1) {
                HyperIconButton(
                    icon = Icons.Filled.SimCard,
                    contentDescription = stringResource(R.string.settings_sim),
                    onClick = onSimPicker,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
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
