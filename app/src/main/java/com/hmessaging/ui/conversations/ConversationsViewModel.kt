package com.hmessaging.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.db.entity.ThreadEntity
import com.hmessaging.di.AppGraph
import com.hmessaging.util.SearchMatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationsUiState(
    /**
     * False until the first database emission arrives.
     *
     * Without it an empty list means both "still loading" and "you have no conversations", and
     * the screen announces the second while it means the first.
     */
    val loaded: Boolean = false,
    val threads: List<ThreadEntity> = emptyList(),
    val archived: List<ThreadEntity> = emptyList(),
    /** Conversations whose contact name or number matches the query. */
    val matchingThreads: List<ThreadEntity> = emptyList(),
    val searchResults: List<MessageEntity> = emptyList(),
    val query: String = "",
    val showArchived: Boolean = false,
) {
    val isSearching: Boolean get() = query.isNotBlank()
}

class ConversationsViewModel(private val graph: AppGraph) : ViewModel() {

    private val query = MutableStateFlow("")
    private val showArchived = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val searchResults = query.flatMapLatest { text ->
        if (text.isBlank()) flowOf(emptyList()) else graph.messageRepository.search(text.trim())
    }

    // combine waits for every source to emit once. The archived query is a second trip to the
    // database that nothing on screen needs yet, so it starts empty rather than holding up the
    // conversation list behind it.
    private val archived = graph.messageRepository.observeArchivedThreads()
        .onStart { emit(emptyList()) }

    val uiState: StateFlow<ConversationsUiState> = combine(
        graph.messageRepository.observeThreads(),
        archived,
        searchResults,
        query,
        showArchived,
    ) { threads, archived, results, text, archivedVisible ->
        ConversationsUiState(
            loaded = true,
            threads = threads,
            archived = archived,
            // Matched here rather than in SQL because both sides need normalising first: the
            // name may be spelled with Arabic letters, the number saved with a country code.
            matchingThreads = if (text.isBlank()) {
                emptyList()
            } else {
                (threads + archived).filter { SearchMatch.matches(text, it.contactName, it.address) }
            },
            searchResults = results,
            query = text,
            showArchived = archivedVisible,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConversationsUiState())

    val queryState: StateFlow<String> = query.asStateFlow()

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun toggleArchivedVisible() {
        showArchived.value = !showArchived.value
    }

    fun setPinned(threadId: Long, pinned: Boolean) = viewModelScope.launch {
        graph.messageRepository.setPinned(threadId, pinned)
    }

    fun setArchived(threadId: Long, archived: Boolean) = viewModelScope.launch {
        graph.messageRepository.setArchived(threadId, archived)
    }

    fun setMuted(threadId: Long, muted: Boolean) = viewModelScope.launch {
        graph.messageRepository.setMuted(threadId, muted)
    }

    fun markRead(threadId: Long) = viewModelScope.launch {
        graph.messageRepository.markThreadRead(threadId)
        graph.notifications.cancelThread(threadId)
    }

    fun deleteThread(threadId: Long) = viewModelScope.launch {
        graph.messageRepository.deleteThread(threadId)
        graph.notifications.cancelThread(threadId)
    }

    fun blockThread(thread: ThreadEntity) = viewModelScope.launch {
        graph.blockEngine.blockNumber(thread.address, thread.contactName)
        graph.messageRepository.setArchived(thread.id, true)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
