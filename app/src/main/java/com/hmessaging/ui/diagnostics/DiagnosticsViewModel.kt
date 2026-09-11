package com.hmessaging.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.di.AppGraph
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

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        busy.value = true
        report.value = runCatching { graph.diagnostics.collect() }.getOrNull()
        busy.value = false
    }

    fun clearLog() = viewModelScope.launch {
        graph.diagnostics.clear()
        refresh()
    }

    /** Re-runs the history import even if it already ran once. */
    fun importNow() = viewModelScope.launch {
        busy.value = true
        val progress = runCatching { graph.smsImporter.importAll() }.getOrNull()
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
