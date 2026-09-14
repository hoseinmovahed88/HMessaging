package com.hmessaging.ui.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.db.entity.ScheduledMessageEntity
import com.hmessaging.data.db.entity.TemplateEntity
import com.hmessaging.data.db.entity.ThreadEntity
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import com.hmessaging.sms.SimSlot
import com.hmessaging.util.SmsLength
import com.hmessaging.util.SmsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ThreadUiState(
    /** False until the conversation's own queries have returned; see ConversationsUiState. */
    val loaded: Boolean = false,
    val thread: ThreadEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val input: String = "",
    val templates: List<TemplateEntity> = emptyList(),
    val simSlots: List<SimSlot> = emptyList(),
    val selectedSubscriptionId: Int = AppSettings.SUBSCRIPTION_UNSET,
    val sending: Boolean = false,
    val error: String? = null,
    /** Ids of the messages picked out for copying, sharing or forwarding together. */
    val selected: Set<Long> = emptySet(),
) {
    val length: SmsLength get() = SmsText.measure(input)
    val canSend: Boolean get() = input.isNotBlank() && !sending
    val selecting: Boolean get() = selected.isNotEmpty()
}

class ThreadViewModel(
    private val graph: AppGraph,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val threadId: Long = savedStateHandle.get<String>(ARG_THREAD_ID)?.toLongOrNull()
        ?: savedStateHandle.get<Long>(ARG_THREAD_ID)
        ?: 0L

    private val input = MutableStateFlow("")
    private val selectedSubscription = MutableStateFlow(AppSettings.SUBSCRIPTION_UNSET)
    private val transient = MutableStateFlow(TransientState())
    private val selected = MutableStateFlow<Set<Long>>(emptySet())

    private data class TransientState(val sending: Boolean = false, val error: String? = null)

    // Templates are only needed once the user opens the picker, so they start empty rather than
    // holding the whole conversation behind a second table's query.
    private val templates = graph.templateDao.observeAll().onStart { emit(emptyList()) }

    val uiState: StateFlow<ThreadUiState> = combine(
        graph.messageRepository.observeThread(threadId),
        graph.messageRepository.observeMessages(threadId),
        input,
        templates,
        combine(selectedSubscription, transient, selected) { subscription, state, picked ->
            Triple(subscription, state, picked)
        },
    ) { thread, messages, text, templates, (subscription, state, picked) ->
        ThreadUiState(
            loaded = true,
            thread = thread,
            messages = messages,
            input = text,
            templates = templates,
            simSlots = graph.simManager.slots(),
            selectedSubscriptionId = subscription,
            sending = state.sending,
            error = state.error,
            // Messages deleted while selected must not leave a selection nothing can act on.
            selected = picked.intersect(messages.mapTo(mutableSetOf()) { it.id }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ThreadUiState())

    init {
        viewModelScope.launch {
            graph.messageRepository.threadById(threadId)?.let { thread ->
                input.value = thread.draft.orEmpty()
            }
            markRead()
        }
    }

    fun onInputChange(value: String) {
        input.value = value
    }

    fun onSubscriptionChange(subscriptionId: Int) {
        selectedSubscription.value = subscriptionId
    }

    fun markRead() = viewModelScope.launch {
        graph.messageRepository.markThreadRead(threadId)
        graph.notifications.cancelThread(threadId)
    }

    fun saveDraft() = viewModelScope.launch {
        graph.messageRepository.setDraft(threadId, input.value)
    }

    fun send() = viewModelScope.launch {
        val text = input.value
        if (text.isBlank() || transient.value.sending) return@launch
        // Claimed before the first suspension point, not after it: the send button is disabled by
        // this flag, and a second tap that lands while the thread is being read would otherwise
        // pass the same check and send the message twice.
        transient.value = TransientState(sending = true)

        val thread = graph.messageRepository.threadById(threadId)
        if (thread == null) {
            transient.value = TransientState(sending = false)
            return@launch
        }
        val outcome = graph.smsSender.send(
            recipients = listOf(thread.address),
            body = text,
            subscriptionId = selectedSubscription.value,
        )
        transient.value = TransientState(sending = false, error = outcome.errors.firstOrNull())
        if (outcome.isSuccess) {
            input.value = ""
            graph.messageRepository.setDraft(threadId, null)
        }
    }

    fun scheduleSend(atEpochMillis: Long, repeat: RepeatMode = RepeatMode.NONE) = viewModelScope.launch {
        val text = input.value
        val thread = graph.messageRepository.threadById(threadId) ?: return@launch
        if (text.isBlank()) return@launch

        graph.scheduleManager.save(
            ScheduledMessageEntity(
                recipients = thread.address,
                body = text,
                scheduledAt = atEpochMillis,
                repeat = repeat,
                subscriptionId = selectedSubscription.value,
            ),
        )
        input.value = ""
        graph.messageRepository.setDraft(threadId, null)
    }

    fun applyTemplate(template: TemplateEntity) = viewModelScope.launch {
        input.value = if (input.value.isBlank()) template.body else "${input.value}\n${template.body}"
        graph.templateDao.incrementUsage(template.id)
    }

    fun toggleSelected(messageId: Long) {
        val current = selected.value
        selected.value = if (messageId in current) current - messageId else current + messageId
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    fun selectAll() {
        selected.value = uiState.value.messages.mapTo(mutableSetOf()) { it.id }
    }

    /**
     * The selected messages as text, oldest first and each on its own line.
     *
     * Sender and time are left out deliberately: this text goes to the clipboard, to another app,
     * or into a new message, and in every one of those the quoted body is what was wanted.
     */
    fun selectedText(): String {
        val picked = uiState.value.selected
        return uiState.value.messages
            .filter { it.id in picked }
            .joinToString("\n\n") { it.body }
    }

    fun deleteSelected() = viewModelScope.launch {
        val picked = uiState.value.selected
        selected.value = emptySet()
        picked.forEach { graph.messageRepository.deleteMessage(it) }
    }

    fun deleteMessage(messageId: Long) = viewModelScope.launch {
        graph.messageRepository.deleteMessage(messageId)
    }

    fun resend(message: MessageEntity) = viewModelScope.launch {
        transient.value = TransientState(sending = true)
        val outcome = graph.smsSender.send(
            recipients = listOf(message.address),
            body = message.body,
            subscriptionId = message.subscriptionId,
            appendSignature = false,
        )
        transient.value = TransientState(sending = false, error = outcome.errors.firstOrNull())
        if (outcome.isSuccess) graph.messageRepository.deleteMessage(message.id)
    }

    fun blockSender() = viewModelScope.launch {
        val thread = graph.messageRepository.threadById(threadId) ?: return@launch
        graph.blockEngine.blockNumber(thread.address, thread.contactName)
        graph.messageRepository.setArchived(threadId, true)
    }

    fun setPinned(pinned: Boolean) = viewModelScope.launch {
        graph.messageRepository.setPinned(threadId, pinned)
    }

    fun setMuted(muted: Boolean) = viewModelScope.launch {
        graph.messageRepository.setMuted(threadId, muted)
    }

    fun setArchived(archived: Boolean) = viewModelScope.launch {
        graph.messageRepository.setArchived(threadId, archived)
    }

    fun deleteThread() = viewModelScope.launch {
        graph.messageRepository.deleteThread(threadId)
    }

    suspend fun exportText(): String = graph.messageRepository.exportThread(threadId)

    suspend fun currentSettings(): AppSettings = graph.prefs.settings.first()

    fun clearError() {
        transient.value = transient.value.copy(error = null)
    }

    companion object {
        const val ARG_THREAD_ID = "threadId"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
