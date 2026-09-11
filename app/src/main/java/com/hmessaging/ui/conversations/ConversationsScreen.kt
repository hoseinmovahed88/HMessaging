package com.hmessaging.ui.conversations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmessaging.R
import com.hmessaging.data.db.entity.ThreadEntity
import com.hmessaging.ui.HmViewModelFactory
import com.hmessaging.ui.components.Avatar
import com.hmessaging.ui.components.EmptyState
import com.hmessaging.ui.components.HyperCard
import com.hmessaging.ui.components.HyperGroupTitle
import com.hmessaging.ui.components.HyperIconButton
import com.hmessaging.ui.components.HyperRowDivider
import com.hmessaging.ui.components.HyperScreen
import com.hmessaging.ui.components.HyperSearchField
import com.hmessaging.ui.components.DefaultSmsAppBanner
import com.hmessaging.ui.rememberIsDefaultSmsApp
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat

@Composable
fun ConversationsScreen(
    onOpenDrawer: () -> Unit,
    onOpenThread: (Long) -> Unit,
    onNewMessage: () -> Unit,
    onRequestDefaultSmsApp: () -> Unit,
    viewModel: ConversationsViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isDefaultSmsApp = rememberIsDefaultSmsApp()
    var searchVisible by remember { mutableStateOf(false) }

    HyperScreen(
        title = stringResource(R.string.nav_conversations),
        navigationIcon = {
            HyperIconButton(Icons.Filled.Menu, null, onOpenDrawer)
        },
        actions = {
            HyperIconButton(
                icon = Icons.Filled.Search,
                contentDescription = stringResource(R.string.search),
                onClick = {
                    searchVisible = !searchVisible
                    if (!searchVisible) viewModel.onQueryChange("")
                },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (!isDefaultSmsApp) {
                item("default-app-banner") {
                    DefaultSmsAppBanner(onRequest = onRequestDefaultSmsApp)
                }
            }

            item("search") {
                AnimatedVisibility(visible = searchVisible) {
                    HyperSearchField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = stringResource(R.string.search),
                    )
                }
            }

            when {
                state.isSearching -> searchResults(state, onOpenThread)

                state.threads.isEmpty() && (!state.showArchived || state.archived.isEmpty()) ->
                    item("empty") {
                        EmptyState(
                            text = stringResource(R.string.no_conversations),
                            icon = Icons.Filled.Forum,
                            modifier = Modifier.padding(top = 96.dp),
                        )
                    }

                else -> {
                    if (state.threads.isNotEmpty()) {
                        item("threads") {
                            HyperCard {
                                state.threads.forEachIndexed { index, thread ->
                                    ThreadRow(thread, viewModel, onOpenThread)
                                    if (index != state.threads.lastIndex) HyperRowDivider(startInset = 72)
                                }
                            }
                        }
                    }
                    if (state.showArchived && state.archived.isNotEmpty()) {
                        item("archived-title") {
                            HyperGroupTitle(stringResource(R.string.nav_archived))
                        }
                        item("archived") {
                            HyperCard {
                                state.archived.forEachIndexed { index, thread ->
                                    ThreadRow(thread, viewModel, onOpenThread)
                                    if (index != state.archived.lastIndex) HyperRowDivider(startInset = 72)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchResults(
    state: ConversationsUiState,
    onOpenThread: (Long) -> Unit,
) {
    if (state.searchResults.isEmpty()) {
        item("no-results") {
            EmptyState(
                text = stringResource(R.string.no_conversations),
                modifier = Modifier.padding(top = 96.dp),
            )
        }
        return
    }
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
        Avatar(name = title, size = 48)

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
