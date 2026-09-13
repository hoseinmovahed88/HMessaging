@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hmessaging.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.Avatar
import com.hmessaging.ui.components.DateTimePickerDialog
import com.hmessaging.ui.components.EmptyState
import com.hmessaging.ui.components.HyperDetailScreen
import com.hmessaging.ui.components.HyperGroupTitle
import com.hmessaging.ui.components.HyperGroupedRow
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.components.HyperRow
import com.hmessaging.ui.components.HyperSearchField
import com.hmessaging.ui.components.MessageComposer
import com.hmessaging.ui.thread.TemplatePickerDialog
import com.hmessaging.util.PhoneNumbers

/**
 * Starting a conversation: find the person, then write.
 *
 * It used to be a form — a field labelled "Phone number" that a name typed into it could not
 * search, and a message box the size of the screen with the send button up in the title bar. It is
 * now shaped like the conversation it turns into: recipients at the top, a list to pick them from
 * in the middle, and the same composer at the bottom that every message in the app is written in.
 */
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
            HyperIconButton(
                icon = Icons.Filled.Bookmark,
                contentDescription = stringResource(R.string.nav_templates),
                onClick = { showTemplates = true },
            )
        },
        bottomBar = {
            MessageComposer(
                value = state.body,
                onValueChange = viewModel::onBodyChange,
                onSend = { viewModel.send { threadId -> threadId?.let(onOpenThread) ?: onBack() } },
                canSend = state.canSend,
                onSchedule = { showSchedulePicker = true },
                above = { if (state.body.isNotEmpty()) LengthCounter(state) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.recipients.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    state.recipients.forEach { recipient ->
                        InputChip(
                            selected = true,
                            onClick = { viewModel.removeRecipient(recipient) },
                            label = { Text(recipient.label) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.delete),
                                    modifier = Modifier.size(ChipIconSize.dp),
                                )
                            },
                        )
                    }
                }
            }

            HyperSearchField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = stringResource(R.string.new_message_find),
            )

            // While a recipient is chosen and nothing is being searched, the list would just be
            // repeating names at someone who is now writing the message.
            val showList = state.query.isNotEmpty() || state.recipients.isEmpty()
            if (!showList) return@Column

            SuggestionList(state, viewModel)
        }
    }

    if (showSchedulePicker) {
        DateTimePickerDialog(
            initialEpochMillis = System.currentTimeMillis() + DefaultScheduleOffsetMs,
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

@Composable
private fun SuggestionList(
    state: NewMessageUiState,
    viewModel: NewMessageViewModel,
) {
    val typedLooksLikeNumber = state.query.any(Char::isDigit)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Anyone not in the contact book still has to be reachable, so what was typed is always
        // offered as a number in its own right.
        if (typedLooksLikeNumber) {
            item("typed") {
                HyperGroupedRow(isFirst = true, isLast = true) {
                    HyperRow(
                        title = PhoneNumbers.format(state.query.trim()),
                        subtitle = stringResource(R.string.new_message_send_to_typed),
                        leading = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                        onClick = viewModel::addTypedNumber,
                    )
                }
            }
        }

        if (state.suggestions.isEmpty()) {
            item("empty") {
                EmptyState(
                    text = stringResource(
                        if (state.contactsPermission) {
                            R.string.new_message_no_matches
                        } else {
                            R.string.new_message_no_contacts_permission
                        },
                    ),
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
            return@LazyColumn
        }

        item("title") {
            HyperGroupTitle(
                stringResource(
                    if (state.query.isBlank()) R.string.new_message_recent else R.string.new_message_results,
                ),
            )
        }
        itemsIndexed(state.suggestions, key = { _, item -> item.key }) { index, suggestion ->
            HyperGroupedRow(
                isFirst = index == 0,
                isLast = index == state.suggestions.lastIndex,
            ) {
                HyperRow(
                    title = suggestion.label,
                    subtitle = suggestion.name?.let { PhoneNumbers.format(suggestion.address) },
                    leading = { Avatar(name = suggestion.label) },
                    onClick = { viewModel.addRecipient(suggestion) },
                )
            }
        }
    }
}

@Composable
private fun LengthCounter(state: NewMessageUiState) {
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
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
    )
}

private const val DefaultScheduleOffsetMs = 60L * 60 * 1000
private const val ChipIconSize = 18
