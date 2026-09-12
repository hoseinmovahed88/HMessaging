package com.hmessaging.ui.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.entity.MessageEntity
import com.hmessaging.data.model.BankTxKind
import com.hmessaging.di.AppGraph
import com.hmessaging.feature.bank.BankRules
import com.hmessaging.feature.bank.NumberSpan
import com.hmessaging.util.PhoneNumbers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which field the next tap on a number assigns. */
enum class TeachField { AMOUNT, BALANCE, ACCOUNT }

data class TeachUiState(
    val loading: Boolean = true,
    /** Messages to choose from, one per sender, newest first. */
    val candidates: List<MessageEntity> = emptyList(),
    val picked: MessageEntity? = null,
    val numbers: List<NumberSpan> = emptyList(),
    val amount: NumberSpan? = null,
    val balance: NumberSpan? = null,
    val account: NumberSpan? = null,
    val field: TeachField = TeachField.AMOUNT,
    val kind: BankTxKind = BankTxKind.WITHDRAWAL,
    val currency: String = "IRR",
    val name: String = "",
    val saved: Boolean = false,
) {
    val canSave: Boolean get() = picked != null && amount != null && name.isNotBlank()
}

/**
 * Drives teaching the app one bank's message format.
 *
 * The candidate list is the only guessing this feature does, and it guesses at nothing more than
 * which messages are worth showing first — a currency word narrows a hundred thousand messages to a
 * few hundred. What each number means is never inferred.
 */
class TeachRuleViewModel(private val graph: AppGraph) : ViewModel() {

    private val _uiState = MutableStateFlow(TeachUiState())
    val uiState: StateFlow<TeachUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val found = CURRENCY_WORDS
                .flatMap { graph.bankDao.inboxContaining(it, CANDIDATE_LIMIT) }
                .sortedByDescending { it.date }
                // One per sender: a bank sends the same shape over and over, and a list of fifty
                // near-identical messages from one bank hides the other banks entirely.
                .distinctBy { PhoneNumbers.threadKey(it.address) }
                .take(CANDIDATE_SHOWN)
            _uiState.value = _uiState.value.copy(loading = false, candidates = found)
        }
    }

    fun pick(message: MessageEntity) {
        val numbers = BankRules.numbersIn(message.body)
        _uiState.value = _uiState.value.copy(
            picked = message,
            numbers = numbers,
            amount = BankRules.suggestAmount(numbers),
            balance = null,
            account = null,
            field = TeachField.AMOUNT,
            currency = BankRules.suggestCurrency(message.body),
            name = graph.contacts.nameFor(message.address) ?: PhoneNumbers.format(message.address),
        )
    }

    fun back() {
        _uiState.value = _uiState.value.copy(picked = null, numbers = emptyList())
    }

    fun setField(field: TeachField) {
        _uiState.value = _uiState.value.copy(field = field)
    }

    /** Assigns the tapped number to whichever field is being filled, or clears it if re-tapped. */
    fun assign(span: NumberSpan) {
        val state = _uiState.value
        _uiState.value = when (state.field) {
            TeachField.AMOUNT -> state.copy(amount = span.takeIf { it != state.amount })
            TeachField.BALANCE -> state.copy(balance = span.takeIf { it != state.balance })
            TeachField.ACCOUNT -> state.copy(account = span.takeIf { it != state.account })
        }
    }

    fun setKind(kind: BankTxKind) {
        _uiState.value = _uiState.value.copy(kind = kind)
    }

    fun setCurrency(currency: String) {
        _uiState.value = _uiState.value.copy(currency = currency)
    }

    fun setName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    /** Saves the rule and immediately re-reads stored messages with it. */
    fun save() = viewModelScope.launch {
        val state = _uiState.value
        val message = state.picked ?: return@launch
        val amount = state.amount ?: return@launch

        graph.bankDao.insertRule(
            BankRules.ruleFrom(
                senderAddress = message.address,
                senderLabel = PhoneNumbers.format(message.address),
                name = state.name.trim(),
                kind = state.kind,
                currency = state.currency,
                body = message.body,
                amount = amount,
                balance = state.balance,
                account = state.account,
                accountLiteral = null,
            ),
        )
        graph.bankLedger.resetScan()
        graph.bankLedger.backfill()
        _uiState.value = _uiState.value.copy(saved = true)
    }

    private companion object {
        val CURRENCY_WORDS = listOf("ریال", "ريال", "تومان")
        const val CANDIDATE_LIMIT = 400
        const val CANDIDATE_SHOWN = 40
    }
}
