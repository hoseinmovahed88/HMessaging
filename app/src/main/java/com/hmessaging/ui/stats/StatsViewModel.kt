package com.hmessaging.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.model.MessageType
import com.hmessaging.di.AppGraph
import com.hmessaging.util.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatsUiState(
    val received: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val last7Days: Int = 0,
    val blocked: Int = 0,
    val topSenders: List<String> = emptyList(),
)

class StatsViewModel(private val graph: AppGraph) : ViewModel() {

    private val topSenders = MutableStateFlow<List<String>>(emptyList())

    val uiState: StateFlow<StatsUiState> = combine(
        graph.messageDao.countByType(MessageType.INBOX),
        graph.messageDao.countByType(MessageType.SENT),
        graph.messageDao.countByType(MessageType.FAILED),
        graph.messageDao.countSince(System.currentTimeMillis() - WEEK_MILLIS),
        combine(graph.blockDao.observeBlockedCount(), topSenders) { blocked, senders -> blocked to senders },
    ) { received, sent, failed, recent, (blocked, senders) ->
        StatsUiState(
            received = received,
            sent = sent,
            failed = failed,
            last7Days = recent,
            blocked = blocked,
            topSenders = senders,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatsUiState())

    init {
        viewModelScope.launch {
            topSenders.value = graph.messageDao.topAddresses(MessageType.INBOX, TOP_SENDER_COUNT)
        }
    }

    fun weekLabel(): String = TimeFormat.dayHeader(System.currentTimeMillis() - WEEK_MILLIS)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val TOP_SENDER_COUNT = 5
    }
}
