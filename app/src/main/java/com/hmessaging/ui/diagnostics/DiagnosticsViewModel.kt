package com.hmessaging.ui.diagnostics

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.di.AppGraph
import com.hmessaging.notify.AlertOutcome
import com.hmessaging.notify.AlertProblem
import com.hmessaging.sms.SmsImporter
import com.hmessaging.sms.SystemSmsWriter
import com.hmessaging.system.Diagnostics
import com.hmessaging.util.AppRoles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiagnosticsViewModel(private val graph: AppGraph) : ViewModel() {

    private val report = MutableStateFlow<Diagnostics.Report?>(null)
    val state: StateFlow<Diagnostics.Report?> = report.asStateFlow()

    private val busy = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = busy.asStateFlow()

    val importProgress: StateFlow<SmsImporter.Running?> = graph.smsImporter.progress

    /**
     * What `content://sms` actually holds, read back the way any other app reads it.
     *
     * The point of writing to the platform store is that other software can see the messages, and
     * nothing else in this app can show whether that worked. This is the answer to "is it really
     * there": the rows, with the thread id each carries, because a row without one is invisible to
     * every conversation view on the phone however complete the rest of it is.
     */
    private val _provider = MutableStateFlow(ProviderView())
    val provider: StateFlow<ProviderView> = _provider.asStateFlow()

    data class ProviderView(
        val canWrite: Boolean = false,
        val roleHeld: Boolean = false,
        val platformDefault: String? = null,
        val writeOp: AppRoles.OpState = AppRoles.OpState.UNKNOWN,
        val readOp: AppRoles.OpState = AppRoles.OpState.UNKNOWN,
        val total: Int = 0,
        val threaded: Int = 0,
        val awaitingBackfill: Int = 0,
        val rows: List<SystemSmsWriter.ProviderRow> = emptyList(),
    )

    /** What is silencing messages right now, or null; re-read on every refresh. */
    private val _alertProblem = MutableStateFlow<AlertProblem?>(null)
    val alertProblem: StateFlow<AlertProblem?> = _alertProblem.asStateFlow()

    init {
        refresh()
    }

    /**
     * Posts a message-channel notification with nothing behind it and logs what happened to it.
     *
     * The one experiment that separates "the phone will not let this app be heard" from "the
     * message never reached the notification": it uses the same channel and builder a real
     * message does, so the reader can tell at once whether it sounded and reached the lock screen.
     */
    /** The settings page that can fix [problem]; the app's own notification page when there is none. */
    fun alertSettingsIntent(problem: AlertProblem?): Intent =
        graph.notifications.alertSettingsIntent(problem ?: AlertProblem.APP_BLOCKED)

    fun sendTestNotification() = viewModelScope.launch {
        val outcome = runCatching { graph.notifications.showTest() }.getOrDefault(AlertOutcome.FAILED)
        graph.diagnostics.record(Diagnostics.KIND_NOTIFY, "test notification — ${outcome.name.lowercase()}")
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        busy.value = true
        report.value = runCatching { graph.diagnostics.collect() }.getOrNull()
        _alertProblem.value = runCatching { graph.notifications.alertProblem() }.getOrNull()
        val (total, threaded) = runCatching { graph.systemSmsWriter.rowStats() }.getOrDefault(0 to 0)
        _provider.value = ProviderView(
            canWrite = graph.systemSmsWriter.canWrite(),
            roleHeld = AppRoles.isSmsRoleHeld(graph.appContext),
            platformDefault = AppRoles.platformDefaultSmsPackage(graph.appContext),
            writeOp = AppRoles.smsWriteOp(graph.appContext),
            readOp = AppRoles.smsReadOp(graph.appContext),
            total = total,
            threaded = threaded,
            awaitingBackfill = runCatching { graph.messageDao.countWithoutSystemId() }.getOrDefault(0),
            rows = runCatching { graph.systemSmsWriter.recentRows() }.getOrDefault(emptyList()),
        )
        busy.value = false
    }

    /**
     * Writes everything still missing from the platform store, rather than a capped pass.
     *
     * Three steps, in this order, because each one uncovers work for the next: check that the rows
     * the app believes it wrote are still there, write whatever is missing, then give a thread id
     * to any row that has none. A row without a thread id is in the store and still invisible to
     * every other app on the phone.
     */
    fun backfillProviderNow() = viewModelScope.launch {
        busy.value = true
        val before = runCatching { graph.systemSmsWriter.rowStats().first }.getOrDefault(0)
        val forgotten = runCatching { graph.messageRepository.reconcileSystemProvider() }.getOrDefault(0)
        var written = 0
        var refused = 0
        var passes = 0
        // Repeats until a pass writes nothing, so one tap finishes a long history instead of
        // leaving the rest for a later open.
        while (passes < MAX_BACKFILL_PASSES) {
            val pass = runCatching { graph.messageRepository.backfillSystemProvider() }.getOrNull()
                ?: break
            written += pass.written
            refused = pass.refused
            passes++
            if (pass.written == 0) break
        }
        val rethreaded = runCatching { graph.messageRepository.repairProviderThreadIds() }.getOrDefault(0)
        val after = runCatching { graph.systemSmsWriter.rowStats().first }.getOrDefault(0)
        graph.diagnostics.record(
            Diagnostics.KIND_SYNC,
            buildString {
                if (forgotten > 0) append("$forgotten message(s) had gone missing from the store; ")
                when {
                    written > 0 -> append("wrote $written message(s) into the system SMS store")
                    refused > 0 -> append(
                        "the system SMS store dropped every write — its write permission for " +
                            "this app is ${AppRoles.smsWriteOp(graph.appContext).name.lowercase()}",
                    )

                    else -> append("nothing left to write into the system SMS store")
                }
                if (rethreaded > 0) append("; gave $rethreaded row(s) a thread id")
                append("; rows in content://sms: $before → $after")
            },
        )
        busy.value = false
        refresh()
    }

    private companion object {
        const val MAX_BACKFILL_PASSES = 60
    }

    fun clearLog() = viewModelScope.launch {
        graph.diagnostics.clear()
        refresh()
    }

    /** Re-runs the history import even if it already ran once. */
    fun importNow() = viewModelScope.launch {
        busy.value = true
        val progress = runCatching { graph.smsImporter.importAll(SmsImporter.NO_LIMIT) }.getOrNull()
        graph.diagnostics.record(
            Diagnostics.KIND_IMPORT,
            when {
                progress == null -> "manual import threw"
                !progress.succeeded -> "manual import FAILED — ${progress.error}"
                else -> "manual import: ${progress.imported} imported, ${progress.skipped} skipped"
            },
        )
        if (progress?.succeeded == true) graph.prefs.setSystemSmsImported(true)
        busy.value = false
        refresh()
    }
}
