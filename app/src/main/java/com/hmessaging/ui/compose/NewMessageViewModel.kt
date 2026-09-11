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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NewMessageUiState(
    val recipients: String = "",
    val body: String = "",
    val templates: List<TemplateEntity> = emptyList(),
    val simSlots: List<SimSlot> = emptyList(),
    val selectedSubscriptionId: Int = AppSettings.SUBSCRIPTION_UNSET,
    val sending: Boolean = false,
    val error: String? = null,
) {
    val parsedRecipients: List<String> get() = PhoneNumbers.splitRecipients(recipients)
    val length: SmsLength get() = SmsText.measure(body)
    val canSend: Boolean get() = body.isNotBlank() && parsedRecipients.isNotEmpty() && !sending
}

/** Backs the "new message" screen, including multi-recipient sends and scheduling. */
class NewMessageViewModel(private val graph: AppGraph) : ViewModel() {

    private val form = MutableStateFlow(FormState())

    init {
        // Text shared in from another app arrives without a recipient; claim it here.
        graph.consumePendingShareBody()?.let { body ->
            form.value = form.value.copy(body = body)
        }
    }
    private data class FormState(
        val recipients: String = "",
        val body: String = "",
        val subscriptionId: Int = AppSettings.SUBSCRIPTION_UNSET,
        val sending: Boolean = false,
        val error: String? = null,
    )

    val uiState: StateFlow<NewMessageUiState> = combine(
        form,
        graph.templateDao.observeAll(),
    ) { state, templates ->
        NewMessageUiState(
            recipients = state.recipients,
            body = state.body,
            templates = templates,
            simSlots = graph.simManager.slots(),
            selectedSubscriptionId = state.subscriptionId,
            sending = state.sending,
            error = state.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NewMessageUiState())

    fun onRecipientsChange(value: String) {
        form.value = form.value.copy(recipients = value)
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
        val recipients = PhoneNumbers.splitRecipients(state.recipients)
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
            form.value = FormState()
            onSent(threadId)
        }
    }

    fun schedule(atEpochMillis: Long, repeat: RepeatMode, onScheduled: () -> Unit) = viewModelScope.launch {
        val state = form.value
        val recipients = PhoneNumbers.splitRecipients(state.recipients)
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
        form.value = FormState()
        onScheduled()
    }

    fun clearError() {
        form.value = form.value.copy(error = null)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
