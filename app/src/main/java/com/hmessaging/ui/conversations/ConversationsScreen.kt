package com.hmessaging.ui.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.data.db.entity.ThreadEntity
import com.hmessaging.di.AppGraph
import com.hmessaging.feature.update.UpdateStatus
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.ContactAvatar
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

    // Back closes the search rather than the screen, the same way it closes a selection in a
    // conversation: the search is the thing on top, so it is the thing back should undo.
    BackHandler(enabled = state.searchOpen) { viewModel.toggleSearch() }

    HyperScreen(
        title = stringResource(R.string.nav_conversations),
        navigationIcon = {
            HyperIconButton(Icons.Filled.Menu, null, onOpenDrawer)
        },
        actions = {
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
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewMessage,
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_message))
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
                                ThreadRow(thread, viewModel, onOpenThread)
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
                                    ThreadRow(thread, viewModel, onOpenThread)
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
    viewModel: ConversationsViewModel,
    onOpenThread: (Long) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val title = thread.contactName ?: PhoneNumbers.format(thread.address)
    val unread = thread.unreadCount > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenThread(thread.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ContactAvatar(name = title, address = thread.address, size = 48)

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

        Box {
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
                    text = { Text(stringResource(R.string.mark_read)) },
                    onClick = {
                        viewModel.markRead(thread.id)
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
