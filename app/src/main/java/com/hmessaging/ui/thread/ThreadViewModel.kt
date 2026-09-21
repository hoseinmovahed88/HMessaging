package com.hmessaging.ui.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.dao.ThreadAccountSummary
import com.hmessaging.data.model.BankTxKind
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.db.entity.ScheduledMessageEntity
import com.hmessaging.data.db.entity.TemplateEntity
import com.hmessaging.data.db.entity.ThreadEntity
import com.hmessaging.data.model.RepeatMode
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import com.hmessaging.feature.bank.BankAccounts
import com.hmessaging.sms.SimSlot
import com.hmessaging.util.PhoneNumbers
import com.hmessaging.util.SmsLength
import com.hmessaging.util.SmsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One tab above a bank's conversation. */
data class BankTab(val key: String, val count: Int)

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
    /** The conversation below this one in the list, reachable by pulling past the newest message. */
    val nextThreadId: Long? = null,
    val nextThreadTitle: String? = null,
    /** The accounts this bank names in this conversation, each with how many messages it has. */
    val bankAccounts: List<BankTab> = emptyList(),
    /** How many messages the bank called money in, and money out. */
    val depositCount: Int = 0,
    val withdrawalCount: Int = 0,
    /** When set, the conversation shows only the messages about that account. */
    val accountFilter: String? = null,
    /** When set, only money in or only money out. */
    val directionFilter: BankTxKind? = null,
) {
    /** True once there is anything to separate — the tabs are hidden until then. */
    val hasBankTabs: Boolean
        get() = bankAccounts.isNotEmpty() || depositCount > 0 || withdrawalCount > 0

    val bankFiltered: Boolean get() = accountFilter != null || directionFilter != null
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
    private val accountFilter = MutableStateFlow<String?>(null)
    private val directionFilter = MutableStateFlow<BankTxKind?>(null)

    private data class TransientState(val sending: Boolean = false, val error: String? = null)

    /** Four small sources folded into one, because combine takes at most five. */
    private data class Extras(
        val subscription: Int,
        val transient: TransientState,
        val selected: Set<Long>,
        val next: ThreadEntity?,
        val account: String?,
        val direction: BankTxKind?,
    )

    // Templates are only needed once the user opens the picker, so they start empty rather than
    // holding the whole conversation behind a second table's query.
    private val templates = graph.templateDao.observeAll().onStart { emit(emptyList()) }

    /**
     * The conversation immediately after this one in the list, or null at the end of it.
     *
     * Positional rather than "the next unread": the gesture exists to walk the list in the order
     * it is shown without leaving it, and a jump that skips the conversation directly underneath
     * would not be the list any more.
     */
    private val nextThread = graph.messageRepository.observeThreads()
        .map { threads ->
            val index = threads.indexOfFirst { it.id == threadId }
            if (index < 0) null else threads.getOrNull(index + 1)
        }
        .onStart { emit(null) }

    /**
     * The conversation with each message placed: which account it names, and which way the bank
     * said the money went.
     *
     * Computed off the main thread. It walks every message in the conversation with a handful of
     * regexes, which is nothing for a chat with a friend and several thousand messages for a bank
     * that has been writing for years.
     */
    private data class Analysed(
        val messages: List<MessageEntity> = emptyList(),
        val accounts: List<BankTab> = emptyList(),
        val accountOf: Map<Long, String?> = emptyMap(),
        val directionOf: Map<Long, BankTxKind?> = emptyMap(),
    )

    private val analysed: Flow<Analysed> = combine(
        graph.messageRepository.observeMessages(threadId),
        graph.bankDao.observeAccountAnchorsForThread(threadId).onStart { emit(emptyList()) },
        graph.bankDao.observeThreadAccounts(threadId).onStart { emit(emptyList()) },
    ) { messages, anchors, ledger -> analyse(messages, anchors, ledger) }
        .flowOn(Dispatchers.Default)

    private fun analyse(
        messages: List<MessageEntity>,
        anchors: List<String>,
        ledger: List<ThreadAccountSummary>,
    ): Analysed {
        // Only a conversation the reader has already called a bank is taken apart this way.
        // Everywhere else the tabs never appear and none of this runs.
        val isBank = anchors.isNotEmpty() || ledger.isNotEmpty()
        if (!isBank) return Analysed(messages = messages)

        val discovered = LinkedHashSet<String>()
        ledger.forEach { row -> row.accountKey.takeIf { it.isNotBlank() && !it.startsWith("@") }?.let(discovered::add) }
        messages.forEach { discovered.addAll(BankAccounts.accountsIn(it.body, anchors)) }

        val keys = discovered.toList()
        val accountOf = messages.associate { it.id to BankAccounts.accountFor(it.body, keys) }
        val directionOf = messages.associate { it.id to BankAccounts.directionOf(it.body) }
        val counts = accountOf.values.filterNotNull().groupingBy { it }.eachCount()

        return Analysed(
            messages = messages,
            // Busiest first: the account most of the conversation is about is the one most often
            // wanted, and an account with nothing in it is not offered at all.
            accounts = keys.mapNotNull { key ->
                counts[key]?.takeIf { it > 0 }?.let { BankTab(key, it) }
            }.sortedByDescending { it.count },
            accountOf = accountOf,
            directionOf = directionOf,
        )
    }

    val uiState: StateFlow<ThreadUiState> = combine(
        graph.messageRepository.observeThread(threadId),
        analysed,
        input,
        templates,
        combine(
            selectedSubscription,
            transient,
            selected,
            nextThread,
            combine(accountFilter, directionFilter) { account, direction -> account to direction },
        ) { subscription, state, picked, next, filters ->
            Extras(subscription, state, picked, next, filters.first, filters.second)
        },
    ) { thread, analysis, text, templates, extras ->
        val (subscription, state, picked, next, account, direction) = extras
        val shown = analysis.messages.filter { message ->
            (account == null || analysis.accountOf[message.id] == account) &&
                (direction == null || analysis.directionOf[message.id] == direction)
        }

        ThreadUiState(
            loaded = true,
            thread = thread,
            messages = shown,
            input = text,
            templates = templates,
            simSlots = graph.simManager.slots(),
            selectedSubscriptionId = subscription,
            sending = state.sending,
            error = state.error,
            // Messages deleted while selected must not leave a selection nothing can act on.
            selected = picked.intersect(shown.mapTo(mutableSetOf()) { it.id }),
            nextThreadId = next?.id,
            nextThreadTitle = next?.let { it.contactName ?: PhoneNumbers.format(it.address) },
            bankAccounts = analysis.accounts,
            depositCount = analysis.directionOf.values.count { it == BankTxKind.DEPOSIT },
            withdrawalCount = analysis.directionOf.values.count { it == BankTxKind.WITHDRAWAL },
            accountFilter = account,
            directionFilter = direction,
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

    /** Narrows the conversation to one account, or clears the narrowing when given null. */
    fun setAccountFilter(accountKey: String?) {
        accountFilter.value = accountKey
    }

    fun setDirectionFilter(kind: BankTxKind?) {
        directionFilter.value = kind
    }

    fun clearBankFilters() {
        accountFilter.value = null
        directionFilter.value = null
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
