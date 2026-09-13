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
    /** Messages to choose from, one per sender, the most bank-like senders first. */
    val candidates: List<MessageEntity> = emptyList(),
    val query: String = "",
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
    val canSave: Boolean
        get() = picked != null && BankRules.canAnchor(amount) && name.isNotBlank()

    /** True when an amount is chosen but nothing is written in front of it to recognise it by. */
    val amountNotAnchorable: Boolean get() = amount != null && !BankRules.canAnchor(amount)
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

    private var all: List<MessageEntity> = emptyList()

    init {
        viewModelScope.launch {
            val latest = graph.bankDao.latestPerSender(CANDIDATE_LIMIT)
            val volume = graph.bankDao.inboxSenderCounts(CANDIDATE_LIMIT)
                .associate { PhoneNumbers.threadKey(it.address) to it.total }
            all = latest.sortedByDescending { score(it, volume) }
            _uiState.value = _uiState.value.copy(loading = false, candidates = all)
        }
    }

    /**
     * How much a sender looks like a bank, used only to decide what to show first.
     *
     * Nothing is filtered out on this. An earlier version showed only messages containing a
     * currency word and so hid the bank that prompted this whole feature — its messages say
     * "برداشت" and "مانده" and never once say ریال. A wrong guess about ordering costs a scroll; a
     * wrong guess about filtering hides the answer entirely.
     */
    private fun score(message: MessageEntity, volume: Map<String, Int>): Int {
        val text = PhoneNumbers.canonical(message.body)
        var score = 0
        // A written-out sum — grouped in threes — is the clearest sign, and unlike the vocabulary
        // it does not depend on which words this particular bank happens to use.
        if (GROUPED_NUMBER.containsMatchIn(text)) score += GROUPED_NUMBER_SCORE
        if (FINANCIAL_WORDS.any { text.contains(it) }) score += FINANCIAL_WORD_SCORE
        // A bank writes over and over; an advertiser writes once.
        score += (volume[PhoneNumbers.threadKey(message.address)] ?: 0).coerceAtMost(VOLUME_CAP)
        return score
    }

    fun search(query: String) {
        val trimmed = query.trim()
        _uiState.value = _uiState.value.copy(
            query = query,
            candidates = if (trimmed.isEmpty()) {
                all
            } else {
                all.filter { it.address.contains(trimmed, true) || it.body.contains(trimmed, true) }
            },
        )
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
        val GROUPED_NUMBER = Regex("[0-9]{1,3}(?:[,،٬][0-9]{3})+")

        val FINANCIAL_WORDS = listOf(
            "مانده", "برداشت", "واریز", "کارمزد", "پایا", "ساتنا", "موجودی", "تراکنش",
            "حساب", "کارت", "ریال", "تومان", "شبا", "صورتحساب",
        )

        const val GROUPED_NUMBER_SCORE = 500
        const val FINANCIAL_WORD_SCORE = 300
        const val VOLUME_CAP = 200
        const val CANDIDATE_LIMIT = 500
    }
}
