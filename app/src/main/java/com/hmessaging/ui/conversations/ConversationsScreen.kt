package com.hmessaging.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.hmessaging.ui.components.SectionHeader
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenDrawer: () -> Unit,
    onOpenThread: (Long) -> Unit,
    onNewMessage: () -> Unit,
    viewModel: ConversationsViewModel = viewModel(factory = HmViewModelFactory.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = null)
                    }
                },
                title = {
                    if (searchVisible) {
                        TextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            placeholder = { Text(stringResource(R.string.search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Text(stringResource(R.string.nav_conversations))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            searchVisible = !searchVisible
                            if (!searchVisible) viewModel.onQueryChange("")
                        },
                    ) {
                        Icon(
                            imageVector = if (searchVisible) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                    IconButton(onClick = viewModel::toggleArchivedVisible) {
                        Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.nav_archived))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewMessage) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_message))
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isSearching -> SearchResults(state, onOpenThread)

                state.threads.isEmpty() && (!state.showArchived || state.archived.isEmpty()) ->
                    EmptyState(
                        text = stringResource(R.string.no_conversations),
                        icon = Icons.Filled.Forum,
                    )

                else -> ThreadList(state, viewModel, onOpenThread)
            }
        }
    }
}

@Composable
private fun ThreadList(
    state: ConversationsUiState,
    viewModel: ConversationsViewModel,
    onOpenThread: (Long) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.threads, key = { it.id }) { thread ->
            ThreadRow(thread, viewModel, onOpenThread)
            HorizontalDivider()
        }
        if (state.showArchived && state.archived.isNotEmpty()) {
            item {
                SectionHeader(stringResource(R.string.nav_archived))
            }
            items(state.archived, key = { "archived-${it.id}" }) { thread ->
                ThreadRow(thread, viewModel, onOpenThread)
                HorizontalDivider()
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenThread(thread.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(name = title)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thread.pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (thread.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = thread.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = TimeFormat.listStamp(thread.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (thread.unreadCount > 0) {
                Badge(modifier = Modifier.padding(top = 4.dp)) { Text(thread.unreadCount.toString()) }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }
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

@Composable
private fun SearchResults(state: ConversationsUiState, onOpenThread: (Long) -> Unit) {
    if (state.searchResults.isEmpty()) {
        EmptyState(text = stringResource(R.string.no_conversations))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.searchResults, key = { it.id }) { message ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenThread(message.threadId) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = PhoneNumbers.format(message.address),
                    style = MaterialTheme.typography.titleMedium,
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
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            HorizontalDivider()
        }
    }
}
