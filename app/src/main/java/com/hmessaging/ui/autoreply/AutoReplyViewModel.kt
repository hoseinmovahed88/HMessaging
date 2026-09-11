package com.hmessaging.ui.autoreply

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.entity.AutoReplyRuleEntity
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AutoReplyUiState(
    val rules: List<AutoReplyRuleEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val repliesToday: Int = 0,
)

class AutoReplyViewModel(private val graph: AppGraph) : ViewModel() {

    val uiState: StateFlow<AutoReplyUiState> = combine(
        graph.autoReplyDao.observeRules(),
        graph.prefs.settings,
        graph.autoReplyDao.observeReplyCountSince(startOfToday()),
    ) { rules, settings, repliesToday ->
        AutoReplyUiState(rules, settings, repliesToday)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AutoReplyUiState())

    fun setEnabled(value: Boolean) = viewModelScope.launch { graph.prefs.setAutoReplyEnabled(value) }

    /** Temporary override that answers around the clock until [until]; 0 turns it off. */
    fun setAwayUntil(until: Long) = viewModelScope.launch { graph.prefs.setAwayUntil(until) }

    fun saveRule(rule: AutoReplyRuleEntity) = viewModelScope.launch {
        graph.autoReplyDao.upsert(rule)
        graph.invalidateCaches()
    }

    fun setRuleEnabled(id: Long, enabled: Boolean) = viewModelScope.launch {
        graph.autoReplyDao.setEnabled(id, enabled)
    }

    fun deleteRule(id: Long) = viewModelScope.launch {
        graph.autoReplyDao.deleteById(id)
        graph.invalidateCaches()
    }

    private fun startOfToday(): Long = com.hmessaging.util.TimeFormat.startOfDay(System.currentTimeMillis())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
