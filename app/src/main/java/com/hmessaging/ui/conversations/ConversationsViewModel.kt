package com.hmessaging.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.db.entity.ThreadEntity
import com.hmessaging.di.AppGraph
import com.hmessaging.sms.SimSlot
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

/**
 * The cuts of the conversation list worth one tap.
 *
 * Deliberately few. A filter row is only useful while it can be read at a glance, and these are
 * the four questions a messages list actually gets asked: what is new, who is a person, what is a
 * service, and what did I put aside.
 */
enum class ConversationFilter { ALL, UNREAD, CONTACTS, UNKNOWN }

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
    /** Whether the search box is open. Held here rather than on the screen; see [ConversationsViewModel]. */
    val searchOpen: Boolean = false,
    val showArchived: Boolean = false,
    val filter: ConversationFilter = ConversationFilter.ALL,
    /** How many conversations each filter would show, so a tap is never into an empty screen. */
    val filterCounts: Map<ConversationFilter, Int> = emptyMap(),
    /** Empty on a single-SIM phone, which is what keeps the badge off every row there. */
    val simSlots: List<SimSlot> = emptyList(),
    /** Conversations picked by long press, for one action on all of them at once. */
    val selected: Set<Long> = emptySet(),
) {
    val selecting: Boolean get() = selected.isNotEmpty()

    /**
     * Results replace the conversation list only while the box that produced them is on screen.
     *
     * Both halves of that are one flag now. They used to be two: the query lived here and the
     * box's visibility lived in the screen, so opening a conversation and coming back destroyed
     * the second and kept the first — leaving a list of search results, no search box, and no way
     * to get back to the conversations.
     */
    val isSearching: Boolean get() = searchOpen && query.isNotBlank()
}

class ConversationsViewModel(private val graph: AppGraph) : ViewModel() {

    private val query = MutableStateFlow("")
    private val searchOpen = MutableStateFlow(false)
    private val showArchived = MutableStateFlow(false)
    private val filter = MutableStateFlow(ConversationFilter.ALL)
    private val selected = MutableStateFlow<Set<Long>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val searchResults = query.flatMapLatest { text ->
        if (text.isBlank()) flowOf(emptyList()) else graph.messageRepository.search(text.trim())
    }

    // combine waits for every source to emit once. The archived query is a second trip to the
    // database that nothing on screen needs yet, so it starts empty rather than holding up the
    // conversation list behind it.
    private val archived = graph.messageRepository.observeArchivedThreads()
        .onStart { emit(emptyList()) }

    /** Query and box visibility travel together, so neither can outlive the other. */
    private val search = combine(query, searchOpen) { text, open -> text to open }

    private val view = combine(showArchived, filter, selected) { archivedVisible, chosen, picked ->
        Triple(archivedVisible, chosen, picked)
    }

    val uiState: StateFlow<ConversationsUiState> = combine(
        graph.messageRepository.observeThreads(),
        archived,
        searchResults,
        search,
        view,
    ) { threads, archived, results, (text, open), (archivedVisible, chosen, picked) ->
        ConversationsUiState(
            loaded = true,
            threads = threads.filter { matches(chosen, it) },
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
            searchOpen = open,
            showArchived = archivedVisible,
            simSlots = graph.simManager.slots(),
            filter = chosen,
            filterCounts = ConversationFilter.entries.associateWith { candidate ->
                threads.count { matches(candidate, it) }
            },
            // Conversations deleted while selected must not leave a selection nothing can act on.
            selected = picked.intersect((threads + archived).mapTo(mutableSetOf()) { it.id }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConversationsUiState())

    /** The pointer to HyperOS's sound switch is shown once; opening it or waving it away ends it. */
    fun dismissXiaomiSoundHint() = viewModelScope.launch { graph.prefs.setXiaomiSoundHintDone(true) }

    fun dismissXiaomiAutostartHint() = viewModelScope.launch { graph.prefs.setXiaomiAutostartHintDone(true) }

    val queryState: StateFlow<String> = query.asStateFlow()

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Closing the search clears what was typed: a hidden query would still filter the list. */
    fun toggleSearch() {
        val open = !searchOpen.value
        searchOpen.value = open
        if (!open) query.value = ""
    }

    /**
     * Whether one conversation belongs in one cut.
     *
     * "Unknown" is asked of the stored contact name rather than of the address book directly: the
     * name is already resolved on every row, so a list of several hundred can be filtered without
     * a contacts-provider query per line.
     */
    private fun matches(filter: ConversationFilter, thread: ThreadEntity): Boolean = when (filter) {
        ConversationFilter.ALL -> true
        ConversationFilter.UNREAD -> thread.unreadCount > 0
        ConversationFilter.CONTACTS -> thread.contactName != null
        ConversationFilter.UNKNOWN -> thread.contactName == null
    }

    fun setFilter(value: ConversationFilter) {
        filter.value = value
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

    fun markUnread(threadId: Long) = viewModelScope.launch {
        graph.messageRepository.markThreadUnread(threadId)
    }

    // ---- selection ----

    fun toggleSelected(threadId: Long) {
        val current = selected.value
        selected.value = if (threadId in current) current - threadId else current + threadId
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    /** Everything the current cut shows — so "select all" in Unread is every unread conversation. */
    fun selectAllVisible() {
        selected.value = uiState.value.threads.mapTo(mutableSetOf()) { it.id }
    }

    fun markSelectedRead() = onSelected { id ->
        graph.messageRepository.markThreadRead(id)
        graph.notifications.cancelThread(id)
    }

    fun markSelectedUnread() = onSelected { graph.messageRepository.markThreadUnread(it) }

    fun archiveSelected() = onSelected { graph.messageRepository.setArchived(it, true) }

    fun deleteSelected() = onSelected { id ->
        graph.messageRepository.deleteThread(id)
        graph.notifications.cancelThread(id)
    }

    private fun onSelected(action: suspend (Long) -> Unit) = viewModelScope.launch {
        val picked = selected.value.toList()
        selected.value = emptySet()
        picked.forEach { action(it) }
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
