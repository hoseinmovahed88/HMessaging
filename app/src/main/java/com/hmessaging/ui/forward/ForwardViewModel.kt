package com.hmessaging.ui.forward

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.entity.ForwardLogEntity
import com.hmessaging.data.db.entity.ForwardRuleEntity
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ForwardUiState(
    val rules: List<ForwardRuleEntity> = emptyList(),
    val log: List<ForwardLogEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

class ForwardViewModel(private val graph: AppGraph) : ViewModel() {

    val uiState: StateFlow<ForwardUiState> = combine(
        graph.forwardDao.observeRules(),
        graph.forwardDao.observeLog(),
        graph.prefs.settings,
    ) { rules, log, settings ->
        ForwardUiState(rules, log, settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ForwardUiState())

    fun setEnabled(value: Boolean) = viewModelScope.launch { graph.prefs.setForwardingEnabled(value) }

    fun setMaxPerHour(value: Int) = viewModelScope.launch { graph.prefs.setForwardMaxPerHour(value) }

    fun saveRule(rule: ForwardRuleEntity) = viewModelScope.launch {
        graph.forwardDao.upsert(rule)
        graph.invalidateCaches()
    }

    fun setRuleEnabled(id: Long, enabled: Boolean) = viewModelScope.launch {
        graph.forwardDao.setEnabled(id, enabled)
    }

    fun deleteRule(id: Long) = viewModelScope.launch {
        graph.forwardDao.deleteById(id)
        graph.invalidateCaches()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
