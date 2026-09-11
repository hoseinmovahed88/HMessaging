package com.hmessaging.ui.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.entity.ScheduledMessageEntity
import com.hmessaging.data.model.ScheduleStatus
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduledUiState(
    val pending: List<ScheduledMessageEntity> = emptyList(),
    val history: List<ScheduledMessageEntity> = emptyList(),
    val exactAlarmsAllowed: Boolean = true,
)

class ScheduledViewModel(private val graph: AppGraph) : ViewModel() {

    val uiState: StateFlow<ScheduledUiState> = graph.scheduleDao.observeAll()
        .map { all ->
            ScheduledUiState(
                pending = all.filter { it.status == ScheduleStatus.PENDING }.sortedBy { it.scheduledAt },
                history = all.filterNot { it.status == ScheduleStatus.PENDING }
                    .sortedByDescending { it.lastAttemptAt ?: it.scheduledAt },
                exactAlarmsAllowed = graph.scheduleManager.canScheduleExactAlarms(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ScheduledUiState())

    fun save(message: ScheduledMessageEntity) = viewModelScope.launch {
        graph.scheduleManager.save(message)
    }

    fun cancel(id: Long) = viewModelScope.launch { graph.scheduleManager.cancel(id) }

    fun delete(id: Long) = viewModelScope.launch { graph.scheduleManager.delete(id) }

    fun sendNow(id: Long) = viewModelScope.launch { graph.scheduleManager.fire(id) }

    fun clearFinished() = viewModelScope.launch { graph.scheduleDao.clearFinished() }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
