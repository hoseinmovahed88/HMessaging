package com.hmessaging.ui.blocked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.entity.BlockRuleEntity
import com.hmessaging.data.db.entity.BlockedMessageEntity
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BlockedUiState(
    val rules: List<BlockRuleEntity> = emptyList(),
    val blockedMessages: List<BlockedMessageEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

class BlockedViewModel(private val graph: AppGraph) : ViewModel() {

    val uiState: StateFlow<BlockedUiState> = combine(
        graph.blockDao.observeRules(),
        graph.blockDao.observeBlockedMessages(),
        graph.prefs.settings,
    ) { rules, messages, settings ->
        BlockedUiState(rules, messages, settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BlockedUiState())

    fun saveRule(rule: BlockRuleEntity) = viewModelScope.launch {
        graph.blockDao.upsertRule(rule)
        graph.invalidateCaches()
    }

    fun setRuleEnabled(id: Long, enabled: Boolean) = viewModelScope.launch {
        graph.blockDao.setRuleEnabled(id, enabled)
    }

    fun deleteRule(id: Long) = viewModelScope.launch {
        graph.blockDao.deleteRuleById(id)
        graph.invalidateCaches()
    }

    fun deleteBlockedMessage(id: Long) = viewModelScope.launch {
        graph.blockDao.deleteBlockedMessage(id)
    }

    fun clearBlockedMessages() = viewModelScope.launch { graph.blockDao.clearBlockedMessages() }

    /** Moves a wrongly blocked message back into the normal inbox and drops the rule behind it. */
    fun restoreBlockedMessage(message: BlockedMessageEntity) = viewModelScope.launch {
        graph.messageRepository.insertIncoming(
            rawAddress = message.address,
            body = message.body,
            date = message.date,
            subscriptionId = -1,
            parts = 1,
            isOtp = false,
            mirrorToSystem = false,
        )
        message.ruleId?.let { graph.blockDao.deleteRuleById(it) }
        graph.blockDao.deleteBlockedMessage(message.id)
        graph.invalidateCaches()
    }

    fun setBlockPrivateNumbers(value: Boolean) = viewModelScope.launch {
        graph.prefs.setBlockPrivateNumbers(value)
    }

    fun setBlockNonContacts(value: Boolean) = viewModelScope.launch {
        graph.prefs.setBlockNonContacts(value)
    }

    fun setScreenCalls(value: Boolean) = viewModelScope.launch { graph.prefs.setScreenCalls(value) }

    fun setKeepBlockedMessages(value: Boolean) = viewModelScope.launch {
        graph.prefs.setKeepBlockedMessages(value)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
