package com.hmessaging.ui.otp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.entity.OtpEntity
import com.hmessaging.data.db.entity.OtpVetoEntity
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OtpUiState(
    val codes: List<OtpEntity> = emptyList(),
    /** Kinds of message the reader has ruled out, so a mistaken tap can be taken back. */
    val vetoes: List<OtpVetoEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

class OtpViewModel(private val graph: AppGraph) : ViewModel() {

    val uiState: StateFlow<OtpUiState> = combine(
        graph.otpDao.observeRecent(),
        graph.otpDao.observeVetoes(),
        graph.prefs.settings,
    ) { codes, vetoes, settings -> OtpUiState(codes, vetoes, settings) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OtpUiState())

    fun markCopied(id: Long) = viewModelScope.launch { graph.otpDao.markCopied(id) }

    fun delete(id: Long) = viewModelScope.launch { graph.otpDao.deleteById(id) }

    /** Rules out this kind of message and forgets the entry it produced. */
    fun notACode(code: OtpEntity) = viewModelScope.launch {
        graph.otpVetoes.veto(code.sender, code.body)
    }

    fun undoVeto(id: Long) = viewModelScope.launch { graph.otpVetoes.remove(id) }

    fun clear() = viewModelScope.launch { graph.otpDao.clear() }

    fun setDetectionEnabled(value: Boolean) = viewModelScope.launch {
        graph.prefs.setOtpDetectionEnabled(value)
    }

    fun setPopupEnabled(value: Boolean) = viewModelScope.launch { graph.prefs.setOtpPopupEnabled(value) }

    fun setAutoCopy(value: Boolean) = viewModelScope.launch { graph.prefs.setOtpAutoCopy(value) }

    fun setAutoDeleteDays(value: Int) = viewModelScope.launch { graph.prefs.setOtpAutoDeleteDays(value) }

    fun setPopupSeconds(value: Int) = viewModelScope.launch { graph.prefs.setOtpPopupSeconds(value) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
