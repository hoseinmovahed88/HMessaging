package com.hmessaging.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.model.ThemeMode
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.di.AppGraph
import com.hmessaging.sms.SimSlot
import com.hmessaging.sms.SmsSyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {

    private val context get() = graph.appContext

    val settings: StateFlow<AppSettings> = graph.prefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppSettings())

    private val status = MutableStateFlow<String?>(null)

    /** One-line result of the last long-running action, shown in a snackbar. */
    val statusMessage: StateFlow<String?> = status.asStateFlow()

    fun simSlots(): List<SimSlot> = graph.simManager.slots()

    fun setThemeMode(value: ThemeMode) = viewModelScope.launch { graph.prefs.setThemeMode(value) }
    fun setDynamicColor(value: Boolean) = viewModelScope.launch { graph.prefs.setDynamicColor(value) }
    fun setDeliveryReports(value: Boolean) = viewModelScope.launch { graph.prefs.setDeliveryReports(value) }
    fun setNumberLongMessages(value: Boolean) = viewModelScope.launch { graph.prefs.setNumberLongMessages(value) }
    fun setChunkChars(value: Int) = viewModelScope.launch { graph.prefs.setChunkChars(value) }
    fun setSignature(value: String) = viewModelScope.launch { graph.prefs.setSignature(value) }
    fun setSignatureEnabled(value: Boolean) = viewModelScope.launch { graph.prefs.setSignatureEnabled(value) }
    fun setDefaultSubscriptionId(value: Int) = viewModelScope.launch { graph.prefs.setDefaultSubscriptionId(value) }
    fun setNotificationPreview(value: Boolean) = viewModelScope.launch { graph.prefs.setNotificationPreview(value) }
    fun setAppLockEnabled(value: Boolean) = viewModelScope.launch { graph.prefs.setAppLockEnabled(value) }

    fun setLiveSyncEnabled(value: Boolean) = viewModelScope.launch {
        graph.prefs.setLiveSyncEnabled(value)
        if (value) SmsSyncService.start(context) else SmsSyncService.stop(context)
    }

    fun importSystemSms() = viewModelScope.launch {
        status.value = null
        val progress = graph.smsImporter.importAll()
        if (progress.succeeded) graph.prefs.setSystemSmsImported(true)
        status.value = if (progress.succeeded) {
            "Imported ${progress.imported}, skipped ${progress.skipped}"
        } else {
            "Import failed: ${progress.error}"
        }
    }

    fun exportBackup(target: Uri, includeMessages: Boolean) = viewModelScope.launch {
        status.value = graph.backupManager.export(target, includeMessages).fold(
            onSuccess = { "Backup written" },
            onFailure = { it.message ?: "Backup failed" },
        )
    }

    fun importBackup(source: Uri, replaceExisting: Boolean) = viewModelScope.launch {
        status.value = graph.backupManager.import(source, replaceExisting).fold(
            onSuccess = { result ->
                "Restored ${result.blockRules} block, ${result.autoReplyRules} reply, " +
                    "${result.forwardRules} forward rules, ${result.templates} templates, " +
                    "${result.scheduled} scheduled, ${result.messages} messages"
            },
            onFailure = { it.message ?: "Restore failed" },
        )
        graph.invalidateCaches()
    }

    fun clearStatus() {
        status.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
