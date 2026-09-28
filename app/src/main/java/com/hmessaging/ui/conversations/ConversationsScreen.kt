@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.hmessaging.ui.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.data.db.entity.ThreadEntity
import com.hmessaging.di.AppGraph
import com.hmessaging.feature.update.UpdateStatus
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.AlertProblemBanner
import com.hmessaging.util.AppRoles
import com.hmessaging.util.Vendor
import com.hmessaging.ui.components.HintBanner
import com.hmessaging.notify.AlertProblem
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.ui.components.ContactAvatar
import com.hmessaging.ui.components.SimBadge
import com.hmessaging.ui.components.DefaultSmsAppBanner
import com.hmessaging.ui.components.EmptyState
import com.hmessaging.ui.components.HyperCard
import com.hmessaging.ui.components.HyperGroupTitle
import com.hmessaging.ui.components.HyperGroupedRow
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.components.HyperRowDivider
import com.hmessaging.ui.components.HyperScreen
import com.hmessaging.ui.components.HyperSearchField
import com.hmessaging.ui.components.UpdateBanner
import com.hmessaging.ui.rememberIsDefaultSmsApp
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat

@Composable
fun ConversationsScreen(
    onOpenDrawer: () -> Unit,
    onOpenThread: (Long) -> Unit,
    onNewMessage: () -> Unit,
    onRequestDefaultSmsApp: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: ConversationsViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isDefaultSmsApp = rememberIsDefaultSmsApp()
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.from(context) }
    val updateStatus by graph.updates.status.collectAsStateWithLifecycle()
    var alertsDismissed by rememberSaveable { mutableStateOf(false) }
    // Bumped on every resume so the notification settings are re-read after a trip to Settings.
    var lifecycleTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) lifecycleTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Re-read on every return to the app rather than once: these switches are changed outside it,
    // and the banner has to notice when one of them is put right. Hoisted to the composable body
    // because a LazyColumn's content block is a LazyListScope, where remember cannot be called.
    val alertProblem = remember(lifecycleTick) { graph.notifications.alertProblem() }
    // Starts as "already shown" so the pointer never flashes before the preference is read.
    val settings by graph.prefs.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings(xiaomiSoundHintDone = true, xiaomiAutostartHintDone = true),
    )

    // Back closes the search rather than the screen, the same way it closes a selection in a
    // conversation: the search is the thing on top, so it is the thing back should undo.
    BackHandler(enabled = state.searchOpen) { viewModel.toggleSearch() }
    // A selection sits above the list the way the search does: back clears it, not the screen.
    BackHandler(enabled = state.selecting) { viewModel.clearSelection() }

    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmDelete) {
        val count = state.selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_threads_title)) },
            text = { Text(stringResource(R.string.delete_threads_text, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteSelected()
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    HyperScreen(
        title = if (state.selecting) {
            stringResource(R.string.selected_count, state.selected.size)
        } else {
            stringResource(R.string.nav_conversations)
        },
        navigationIcon = {
            if (state.selecting) {
                HyperIconButton(Icons.Filled.Close, stringResource(R.string.clear_selection), viewModel::clearSelection)
            } else {
                HyperIconButton(Icons.Filled.Menu, null, onOpenDrawer)
            }
        },
        actions = {
            if (state.selecting) {
                HyperIconButton(
                    icon = Icons.Filled.SelectAll,
                    contentDescription = stringResource(R.string.select_all),
                    onClick = viewModel::selectAllVisible,
                )
                HyperIconButton(
                    icon = Icons.Filled.MarkEmailRead,
                    contentDescription = stringResource(R.string.mark_read),
                    onClick = viewModel::markSelectedRead,
                )
                HyperIconButton(
                    icon = Icons.Filled.MarkEmailUnread,
                    contentDescription = stringResource(R.string.mark_unread),
                    onClick = viewModel::markSelectedUnread,
                )
                HyperIconButton(
                    icon = Icons.Filled.Archive,
                    contentDescription = stringResource(R.string.archive),
                    onClick = viewModel::archiveSelected,
                )
                HyperIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete),
                    onClick = { confirmDelete = true },
                )
            } else {
                HyperIconButton(
                    icon = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.search),
                    onClick = viewModel::toggleSearch,
                )
                HyperIconButton(
                    icon = Icons.Filled.Archive,
                    contentDescription = stringResource(R.string.nav_archived),
                    onClick = viewModel::toggleArchivedVisible,
                )
            }
        },
        floatingActionButton = {
            if (!state.selecting) {
                FloatingActionButton(
                    onClick = onNewMessage,
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_message))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Outside the list on purpose. As a list item it scrolled away with the conversations,
            // so searching from anywhere but the top meant scrolling back up first — and while
            // results were showing, the box you were typing in could leave the screen.
            AnimatedVisibility(visible = state.searchOpen) {
                HyperSearchField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = stringResource(R.string.search_name_or_message),
                )
            }
            // Above the list and outside it, for the same reason the search box is: a filter that
            // scrolls away cannot be changed from where its results are being read.
            if (!state.isSearching) {
                FilterRow(
                    selected = state.filter,
                    counts = state.filterCounts,
                    onSelect = viewModel::setFilter,
                )
            }
            LazyColumn(
                // weight, not fillMaxSize: inside a Column the list has to take what is left
                // after the search box rather than the whole screen.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 2.dp, bottom = 96.dp),
            ) {
                item("update-banner") {
                    UpdateBanner(
                        status = updateStatus,
                        onDownload = { (updateStatus as? UpdateStatus.Available)?.let { graph.updates.download(it.info) } },
                        onInstall = {
                            (updateStatus as? UpdateStatus.Ready)?.let {
                                context.startActivity(graph.updateChecker.installIntent(it.file))
                            }
                        },
                        onDismiss = graph.updates::dismiss,
                    )
                }

                // First, because without it nothing else matters: a fresh install on HyperOS has
                // autostart off, and with the app closed no message is delivered to anyone.
                if (Vendor.isXiaomi && !settings.xiaomiAutostartHintDone) {
                    item("xiaomi-autostart-hint") {
                        HintBanner(
                            title = stringResource(R.string.xiaomi_autostart_title),
                            text = stringResource(R.string.xiaomi_autostart_text),
                            actionLabel = stringResource(R.string.xiaomi_autostart_open),
                            onAction = {
                                val intent = AppRoles.autostartSettingsIntent()
                                    ?: AppRoles.appDetailsSettingsIntent(context)
                                runCatching {
                                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure {
                                    runCatching {
                                        context.startActivity(
                                            AppRoles.appDetailsSettingsIntent(context)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }
                                viewModel.dismissXiaomiAutostartHint()
                            },
                            onDismiss = viewModel::dismissXiaomiAutostartHint,
                        )
                    }
                }

                // Only when Android itself reports nothing wrong: when it does, that banner is
                // the one to act on, and this one would say the opposite of it.
                if (alertProblem == null && Vendor.isXiaomi && !settings.xiaomiSoundHintDone) {
                    item("xiaomi-sound-hint") {
                        HintBanner(
                            title = stringResource(R.string.xiaomi_sound_title),
                            text = stringResource(R.string.xiaomi_sound_text),
                            actionLabel = stringResource(R.string.xiaomi_sound_open),
                            onAction = {
                                runCatching {
                                    context.startActivity(
                                        graph.notifications.alertSettingsIntent(AlertProblem.APP_BLOCKED)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                                viewModel.dismissXiaomiSoundHint()
                            },
                            onDismiss = viewModel::dismissXiaomiSoundHint,
                        )
                    }
                }

                if (alertProblem != null && !alertsDismissed) {
                    item("alerts-banner") {
                        AlertProblemBanner(
                            problem = alertProblem,
                            onOpenSettings = {
                                runCatching {
                                    context.startActivity(
                                        graph.notifications.alertSettingsIntent(alertProblem)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                            onDismiss = { alertsDismissed = true },
                        )
                    }
                }

                if (!isDefaultSmsApp) {
                    item("default-app-banner") {
                        DefaultSmsAppBanner(
                            onRequest = onRequestDefaultSmsApp,
                            onOpenDiagnostics = onOpenDiagnostics,
                        )
                    }
                }

                when {
                    state.isSearching -> searchResults(state, onOpenThread)

                    // Deliberately renders nothing while loading: a blank moment goes unnoticed,
                    // "no conversations yet" for two seconds reads as data loss.
                    !state.loaded -> Unit

                    state.threads.isEmpty() && (!state.showArchived || state.archived.isEmpty()) ->
                        item("empty") {
                            EmptyState(
                                text = if (state.filter == ConversationFilter.ALL) {
                                    stringResource(R.string.no_conversations)
                                } else {
                                    stringResource(R.string.no_conversations_in_filter)
                                },
                                icon = Icons.Filled.Forum,
                                modifier = Modifier.padding(top = 96.dp),
                            )
                        }

                    else -> {
                        // One lazy item per row: a single item holding the whole list would compose
                        // every conversation up front, however many there are.
                        itemsIndexed(
                            items = state.threads,
                            key = { _, thread -> thread.id },
                        ) { index, thread ->
                            HyperGroupedRow(
                                isFirst = index == 0,
                                isLast = index == state.threads.lastIndex,
                                dividerInset = 72,
                            ) {
                                ThreadRow(thread, state, viewModel, onOpenThread)
                            }
                        }
                        if (state.showArchived && state.archived.isNotEmpty()) {
                            item("archived-title") {
                                HyperGroupTitle(stringResource(R.string.nav_archived))
                            }
                            itemsIndexed(
                                items = state.archived,
                                key = { _, thread -> "archived-${thread.id}" },
                            ) { index, thread ->
                                HyperGroupedRow(
                                    isFirst = index == 0,
                                    isLast = index == state.archived.lastIndex,
                                    dividerInset = 72,
                                ) {
                                    ThreadRow(thread, state, viewModel, onOpenThread)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Search results: the people first, then the messages.
 *
 * Both, because they answer different questions asked through the same box — "open my
 * conversation with Sara" and "find the message with the tracking number in it" — and until now
 * only the second was answered, so typing a contact's name found nothing at all.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.searchResults(
    state: ConversationsUiState,
    onOpenThread: (Long) -> Unit,
) {
    if (state.searchResults.isEmpty() && state.matchingThreads.isEmpty()) {
        item("no-results") {
            EmptyState(
                text = stringResource(R.string.no_conversations),
                modifier = Modifier.padding(top = 96.dp),
            )
        }
        return
    }

    if (state.matchingThreads.isNotEmpty()) {
        item("contacts-title") { HyperGroupTitle(stringResource(R.string.search_contacts)) }
        itemsIndexed(
            items = state.matchingThreads,
            key = { _, thread -> "match-${thread.id}" },
        ) { index, thread ->
            HyperGroupedRow(
                isFirst = index == 0,
                isLast = index == state.matchingThreads.lastIndex,
                dividerInset = 72,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenThread(thread.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val title = thread.contactName ?: PhoneNumbers.format(thread.address)
                    ContactAvatar(name = title, address = thread.address, size = 48)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = PhoneNumbers.format(thread.address),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    if (state.searchResults.isEmpty()) return
    item("messages-title") { HyperGroupTitle(stringResource(R.string.search_messages)) }
    items(state.searchResults, key = { it.id }) { message ->
        HyperCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenThread(message.threadId) }
                    .padding(16.dp),
            ) {
                Text(
                    text = PhoneNumbers.format(message.address),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = TimeFormat.full(message.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The one-tap cuts of the list, with what each holds. */
@Composable
private fun FilterRow(
    selected: ConversationFilter,
    counts: Map<ConversationFilter, Int>,
    onSelect: (ConversationFilter) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ConversationFilter.entries.forEach { filter ->
            val count = counts[filter] ?: 0
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        text = if (filter == ConversationFilter.ALL || count == 0) {
                            stringResource(filterLabel(filter))
                        } else {
                            "${stringResource(filterLabel(filter))} $count"
                        },
                    )
                },
            )
        }
    }
}

private fun filterLabel(filter: ConversationFilter): Int = when (filter) {
    ConversationFilter.ALL -> R.string.filter_all
    ConversationFilter.UNREAD -> R.string.filter_unread
    ConversationFilter.CONTACTS -> R.string.filter_contacts
    ConversationFilter.UNKNOWN -> R.string.filter_unknown
}

@Composable
private fun ThreadRow(
    thread: ThreadEntity,
    state: ConversationsUiState,
    viewModel: ConversationsViewModel,
    onOpenThread: (Long) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val title = thread.contactName ?: PhoneNumbers.format(thread.address)
    val unread = thread.unreadCount > 0
    val selected = thread.id in state.selected

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = SelectedRowTint) else Color.Transparent,
            )
            // A long press starts a selection; while one is open, a tap extends it rather than
            // opening the conversation, the way every list with a selection mode behaves.
            .combinedClickable(
                onClick = { if (state.selecting) viewModel.toggleSelected(thread.id) else onOpenThread(thread.id) },
                onLongClick = { viewModel.toggleSelected(thread.id) },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        } else {
            ContactAvatar(name = title, address = thread.address, size = 48)
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (thread.pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(14.dp),
                    )
                }
                if (thread.muted) {
                    Icon(
                        Icons.Filled.NotificationsOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(14.dp),
                    )
                }
            }
            Text(
                text = thread.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = TimeFormat.listStamp(thread.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Beside the time, because it answers the same kind of question about the newest
            // message: when it came, and on which line.
            SimBadge(subscriptionId = thread.lastSubscriptionId, slots = state.simSlots)
            if (unread) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = thread.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }

        if (!state.selecting) Box {
            HyperIconButton(
                icon = Icons.Filled.MoreVert,
                contentDescription = null,
                onClick = { menuOpen = true },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (thread.pinned) R.string.unpin else R.string.pin)) },
                    onClick = {
                        viewModel.setPinned(thread.id, !thread.pinned)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (thread.archived) R.string.unarchive else R.string.archive)) },
                    onClick = {
                        viewModel.setArchived(thread.id, !thread.archived)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (thread.muted) R.string.unmute else R.string.mute)) },
                    onClick = {
                        viewModel.setMuted(thread.id, !thread.muted)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (unread) R.string.mark_read else R.string.mark_unread)) },
                    onClick = {
                        if (unread) viewModel.markRead(thread.id) else viewModel.markUnread(thread.id)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.block_number)) },
                    onClick = {
                        viewModel.blockThread(thread)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete_conversation)) },
                    onClick = {
                        viewModel.deleteThread(thread.id)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/** How strongly a selected conversation is tinted: visible, not shouting. */
private const val SelectedRowTint = 0.12f
