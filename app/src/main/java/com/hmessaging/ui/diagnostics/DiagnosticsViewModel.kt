package com.hmessaging.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.di.AppGraph
import com.hmessaging.sms.SmsImporter
import com.hmessaging.sms.SystemSmsWriter
import com.hmessaging.system.Diagnostics
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
        val total: Int = 0,
        val threaded: Int = 0,
        val awaitingBackfill: Int = 0,
        val rows: List<SystemSmsWriter.ProviderRow> = emptyList(),
    )

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        busy.value = true
        report.value = runCatching { graph.diagnostics.collect() }.getOrNull()
        val (total, threaded) = runCatching { graph.systemSmsWriter.rowStats() }.getOrDefault(0 to 0)
        _provider.value = ProviderView(
            canWrite = graph.systemSmsWriter.canWrite(),
            total = total,
            threaded = threaded,
            awaitingBackfill = runCatching { graph.messageDao.countWithoutSystemId() }.getOrDefault(0),
            rows = runCatching { graph.systemSmsWriter.recentRows() }.getOrDefault(emptyList()),
        )
        busy.value = false
    }

    /** Writes everything still missing from the platform store, rather than a capped pass. */
    fun backfillProviderNow() = viewModelScope.launch {
        busy.value = true
        var written = 0
        var passes = 0
        // Repeats until a pass writes nothing, so one tap finishes a long history instead of
        // leaving the rest for a later open.
        while (passes < MAX_BACKFILL_PASSES) {
            val pass = runCatching { graph.messageRepository.backfillSystemProvider() }.getOrNull()
                ?: break
            written += pass.written
            passes++
            if (pass.written == 0) break
        }
        graph.diagnostics.record(
            Diagnostics.KIND_SYNC,
            if (graph.systemSmsWriter.canWrite()) {
                "wrote $written message(s) into the system SMS store"
            } else {
                "system SMS store refused the write — this app is not the platform's default"
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
