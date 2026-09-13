package com.hmessaging.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.entity.ScheduledMessageEntity
import com.hmessaging.data.db.entity.TemplateEntity
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import com.hmessaging.sms.SimSlot
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.SmsLength
import com.hmessaging.util.SmsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Someone to write to, from the phone's contacts or from a conversation already had. */
data class RecipientSuggestion(
    val name: String?,
    val address: String,
    /** True when this came from an existing conversation rather than the contact book. */
    val recent: Boolean,
) {
    val label: String get() = name ?: PhoneNumbers.format(address)
    val key: String get() = PhoneNumbers.threadKey(address)
}

data class NewMessageUiState(
    /** Who the message is addressed to. Chips, not a comma-separated string to be parsed back. */
    val recipients: List<RecipientSuggestion> = emptyList(),
    val query: String = "",
    val suggestions: List<RecipientSuggestion> = emptyList(),
    val contactsPermission: Boolean = true,
    val body: String = "",
    val templates: List<TemplateEntity> = emptyList(),
    val simSlots: List<SimSlot> = emptyList(),
    val selectedSubscriptionId: Int = AppSettings.SUBSCRIPTION_UNSET,
    val sending: Boolean = false,
    val error: String? = null,
) {
    val addresses: List<String> get() = recipients.map { it.address }
    val length: SmsLength get() = SmsText.measure(body)
    val canSend: Boolean get() = body.isNotBlank() && recipients.isNotEmpty() && !sending
}

/** Backs the "new message" screen, including multi-recipient sends and scheduling. */
class NewMessageViewModel(private val graph: AppGraph) : ViewModel() {

    private val form = MutableStateFlow(FormState())

    private data class FormState(
        val recipients: List<RecipientSuggestion> = emptyList(),
        val query: String = "",
        val suggestions: List<RecipientSuggestion> = emptyList(),
        val body: String = "",
        val subscriptionId: Int = AppSettings.SUBSCRIPTION_UNSET,
        val sending: Boolean = false,
        val error: String? = null,
    )

    /** Conversations already had, which is the fastest answer to "who am I writing to". */
    private var recents: List<RecipientSuggestion> = emptyList()

    init {
        // Text shared in from another app arrives without a recipient; claim it here.
        graph.consumePendingShareBody()?.let { body ->
            form.value = form.value.copy(body = body)
        }
        viewModelScope.launch {
            recents = withContext(Dispatchers.IO) {
                graph.threadDao.all()
                    .sortedByDescending { it.lastMessageAt }
                    .take(RECENT_COUNT)
                    .map { RecipientSuggestion(it.contactName, it.address, recent = true) }
            }
            if (form.value.query.isBlank()) {
                form.value = form.value.copy(suggestions = recents)
            }
        }
    }

    val uiState: StateFlow<NewMessageUiState> = combine(
        form,
        graph.templateDao.observeAll().onStart { emit(emptyList()) },
    ) { state, templates ->
        NewMessageUiState(
            recipients = state.recipients,
            query = state.query,
            suggestions = state.suggestions,
            contactsPermission = graph.contacts.hasPermission(),
            body = state.body,
            templates = templates,
            simSlots = graph.simManager.slots(),
            selectedSubscriptionId = state.subscriptionId,
            sending = state.sending,
            error = state.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NewMessageUiState())

    /**
     * Searches contacts and past conversations as the name is typed.
     *
     * Both sources, because neither alone is enough: the contact book has people never written to,
     * and the conversations have numbers never saved as contacts. Recents come first — someone
     * written to last week is a likelier target than the fourth match in an address book.
     */
    fun onQueryChange(value: String) {
        form.value = form.value.copy(query = value)
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            form.value = form.value.copy(suggestions = recents)
            return
        }
        viewModelScope.launch {
            val fromThreads = recents.filter {
                it.label.contains(trimmed, true) || it.address.contains(trimmed, true)
            }
            val fromContacts = withContext(Dispatchers.IO) {
                graph.contacts.search(trimmed).map {
                    RecipientSuggestion(it.name, it.number, recent = false)
                }
            }
            // Only report against the query that is still being typed; a slow contacts query
            // returning after the next keystroke must not replace newer results.
            if (form.value.query.trim() == trimmed) {
                form.value = form.value.copy(
                    suggestions = (fromThreads + fromContacts).distinctBy { it.key },
                )
            }
        }
    }

    fun addRecipient(suggestion: RecipientSuggestion) {
        val current = form.value.recipients
        if (current.none { it.key == suggestion.key }) {
            form.value = form.value.copy(recipients = current + suggestion)
        }
        onQueryChange("")
    }

    /** Accepts whatever was typed as a number, for someone not in the contact book. */
    fun addTypedNumber() {
        val typed = form.value.query.trim()
        if (typed.isEmpty()) return
        addRecipient(RecipientSuggestion(name = null, address = typed, recent = false))
    }

    fun removeRecipient(suggestion: RecipientSuggestion) {
        form.value = form.value.copy(
            recipients = form.value.recipients.filterNot { it.key == suggestion.key },
        )
    }

    fun onBodyChange(value: String) {
        form.value = form.value.copy(body = value)
    }

    fun onSubscriptionChange(subscriptionId: Int) {
        form.value = form.value.copy(subscriptionId = subscriptionId)
    }

    fun applyTemplate(template: TemplateEntity) = viewModelScope.launch {
        val current = form.value.body
        form.value = form.value.copy(
            body = if (current.isBlank()) template.body else "$current\n${template.body}",
        )
        graph.templateDao.incrementUsage(template.id)
    }

    /** Invokes [onSent] with the thread to open, or with null when several recipients were used. */
    fun send(onSent: (Long?) -> Unit) = viewModelScope.launch {
        val state = form.value
        val recipients = state.recipients.map { it.address }
        if (recipients.isEmpty() || state.body.isBlank()) return@launch

        form.value = state.copy(sending = true, error = null)
        val outcome = graph.smsSender.send(
            recipients = recipients,
            body = state.body,
            subscriptionId = state.subscriptionId,
        )
        form.value = form.value.copy(sending = false, error = outcome.errors.firstOrNull())
        if (outcome.isSuccess) {
            val threadId = recipients.singleOrNull()?.let { graph.messageRepository.threadIdFor(it) }
            form.value = FormState(suggestions = recents)
            onSent(threadId)
        }
    }

    fun schedule(atEpochMillis: Long, repeat: RepeatMode, onScheduled: () -> Unit) = viewModelScope.launch {
        val state = form.value
        val recipients = state.recipients.map { it.address }
        if (recipients.isEmpty() || state.body.isBlank()) return@launch

        graph.scheduleManager.save(
            ScheduledMessageEntity(
                recipients = PhoneNumbers.joinRecipients(recipients),
                body = state.body,
                scheduledAt = atEpochMillis,
                repeat = repeat,
                subscriptionId = state.subscriptionId,
            ),
        )
        form.value = FormState(suggestions = recents)
        onScheduled()
    }

    fun clearError() {
        form.value = form.value.copy(error = null)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val RECENT_COUNT = 30
    }
}
