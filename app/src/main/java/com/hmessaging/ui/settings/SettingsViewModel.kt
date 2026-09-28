package com.hmessaging.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.model.ThemeMode
import com.hmessaging.data.prefs.AppSettings
import com.hmessaging.backup.BackupWorker
import com.hmessaging.di.AppGraph
import com.hmessaging.sms.SimSlot
import com.hmessaging.sms.SmsImporter
import com.hmessaging.sms.SmsSyncService
import com.hmessaging.sms.WatcherNeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    fun setPersianCalendar(value: Boolean) =
        viewModelScope.launch { graph.prefs.setPersianCalendar(value) }

    fun setUpdateManifestUrl(value: String) =
        viewModelScope.launch { graph.prefs.setUpdateManifestUrl(value) }

    fun setQuickReplyPopupEnabled(value: Boolean) =
        viewModelScope.launch { graph.prefs.setQuickReplyPopupEnabled(value) }
    fun setAppLockEnabled(value: Boolean) = viewModelScope.launch { graph.prefs.setAppLockEnabled(value) }

    fun setLiveSyncEnabled(value: Boolean) = viewModelScope.launch {
        graph.prefs.setLiveSyncEnabled(value)
        val settings = graph.prefs.settings.first()
        if (WatcherNeed.shouldRun(context, settings, graph.prefs.deliveryStats.first())) {
            SmsSyncService.start(context)
        } else {
            SmsSyncService.stop(context)
        }
    }

    /** Live row counter while a full import runs. */
    val importProgress: StateFlow<SmsImporter.Running?> = graph.smsImporter.progress

    fun importSystemSms() = viewModelScope.launch {
        status.value = null
        // Everything, not a page at a time: a hundred thousand messages is twenty presses of a
        // five-thousand-row button, which is not a feature.
        val progress = graph.smsImporter.importAll(SmsImporter.NO_LIMIT)
        if (progress.succeeded) graph.prefs.setSystemSmsImported(true)
        status.value = if (progress.succeeded) {
            "Imported ${progress.imported}, skipped ${progress.skipped}"
        } else {
            "Import failed: ${progress.error}"
        }
    }

    fun setAutoBackupEnabled(value: Boolean) = viewModelScope.launch {
        graph.prefs.setAutoBackupEnabled(value)
        if (value) BackupWorker.enqueue(context)
    }

    fun backupNow() = viewModelScope.launch {
        status.value = null
        status.value = graph.autoBackup.writeNow().fold(
            onSuccess = { "Backed up ${it.messages} messages to ${it.where}" },
            onFailure = { it.message ?: "Backup failed" },
        )
    }

    fun restoreFromFolder(folder: Uri) = viewModelScope.launch {
        status.value = null
        status.value = graph.autoBackup.restoreFromFolder(folder).fold(
            onSuccess = { "Restored ${it.messages} messages" + if (it.settingsFile) " and settings" else "" },
            onFailure = { it.message ?: "Restore failed" },
        )
        graph.invalidateCaches()
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
