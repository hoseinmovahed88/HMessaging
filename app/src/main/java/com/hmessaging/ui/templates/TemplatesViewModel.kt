package com.hmessaging.ui.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.entity.TemplateEntity
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TemplatesViewModel(private val graph: AppGraph) : ViewModel() {

    val templates: StateFlow<List<TemplateEntity>> = graph.templateDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun save(template: TemplateEntity) = viewModelScope.launch { graph.templateDao.upsert(template) }

    fun delete(id: Long) = viewModelScope.launch { graph.templateDao.deleteById(id) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
