package com.hmessaging.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hmessaging.di.AppGraph
import com.hmessaging.ui.autoreply.AutoReplyViewModel
import com.hmessaging.ui.bank.BankViewModel
import com.hmessaging.ui.bank.TeachRuleViewModel
import com.hmessaging.ui.blocked.BlockedViewModel
import com.hmessaging.ui.compose.NewMessageViewModel
import com.hmessaging.ui.conversations.ConversationsViewModel
import com.hmessaging.ui.diagnostics.DiagnosticsViewModel
import com.hmessaging.ui.forward.ForwardViewModel
import com.hmessaging.ui.otp.OtpViewModel
import com.hmessaging.ui.scheduled.ScheduledViewModel
import com.hmessaging.ui.settings.SettingsViewModel
import com.hmessaging.ui.stats.StatsViewModel
import com.hmessaging.ui.templates.TemplatesViewModel
import com.hmessaging.ui.thread.ThreadViewModel

/**
 * Single factory for every screen's ViewModel.
 *
 * With a hand-rolled graph there is no injector to consult, so construction lives here where the
 * dependencies of each screen are visible in one place.
 */
object HmViewModelFactory {

    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { ConversationsViewModel(graph()) }
        initializer { ThreadViewModel(graph(), createSavedStateHandle()) }
        initializer { NewMessageViewModel(graph()) }
        initializer { ScheduledViewModel(graph()) }
        initializer { BlockedViewModel(graph()) }
        initializer { AutoReplyViewModel(graph()) }
        initializer { ForwardViewModel(graph()) }
        initializer { OtpViewModel(graph()) }
        initializer { TemplatesViewModel(graph()) }
        initializer { BankViewModel(graph()) }
        initializer { TeachRuleViewModel(graph(), createSavedStateHandle()) }
        initializer { StatsViewModel(graph()) }
        initializer { SettingsViewModel(graph()) }
        initializer { DiagnosticsViewModel(graph()) }
    }

    private fun CreationExtras.graph(): AppGraph {
        val application = requireNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
            "ViewModel created outside of an Application context"
        }
        return AppGraph.from(application)
    }
}
