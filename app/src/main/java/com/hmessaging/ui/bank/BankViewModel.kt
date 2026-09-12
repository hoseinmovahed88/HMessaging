package com.hmessaging.ui.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hmessaging.data.db.dao.BankAccountSummary
import com.hmessaging.data.db.entity.BankTxEntity
import com.hmessaging.data.model.BankTxKind
import com.hmessaging.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which direction the list is showing. */
enum class BankFilter { ALL, DEPOSIT, WITHDRAWAL }

/** Totals for one currency, kept apart because rial and toman are not interchangeable. */
data class BankTotals(
    val currency: String,
    val deposited: Long,
    val withdrawn: Long,
) {
    val net: Long get() = deposited - withdrawn
}

data class BankUiState(
    val loaded: Boolean = false,
    val transactions: List<BankTxEntity> = emptyList(),
    val accounts: List<BankAccountSummary> = emptyList(),
    val filter: BankFilter = BankFilter.ALL,
    /** Null means every account. */
    val accountKey: String? = null,
    val totals: List<BankTotals> = emptyList(),
    val scanning: Boolean = false,
    val message: String? = null,
)

class BankViewModel(private val graph: AppGraph) : ViewModel() {

    private val filter = MutableStateFlow(BankFilter.ALL)
    private val accountKey = MutableStateFlow<String?>(null)
    private val transient = MutableStateFlow(Transient())

    private data class Transient(val scanning: Boolean = false, val message: String? = null)

    val uiState: StateFlow<BankUiState> = combine(
        graph.bankDao.observeRecent().onStart { emit(emptyList()) },
        graph.bankDao.observeAccounts().onStart { emit(emptyList()) },
        filter,
        accountKey,
        transient,
    ) { rows, accounts, filter, account, state ->
        val forAccount = rows.filter { account == null || it.accountKey == account }
        BankUiState(
            loaded = true,
            // Totals follow the account but not the direction filter: seeing only deposits should
            // not make the withdrawals column read zero.
            transactions = forAccount.filter { matches(it, filter) },
            accounts = accounts,
            filter = filter,
            accountKey = account,
            totals = totalsOf(forAccount),
            scanning = state.scanning,
            message = state.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BankUiState())

    init {
        // The ledger only knows about messages that arrived since it was added, so the first time
        // this screen opens it walks the ones already stored.
        scan()
    }

    fun setFilter(value: BankFilter) {
        filter.value = value
    }

    fun setAccount(value: String?) {
        accountKey.value = value
    }

    /** Walks stored messages for transactions the ledger has not seen yet. */
    fun scan() = viewModelScope.launch {
        if (transient.value.scanning) return@launch
        transient.value = Transient(scanning = true)
        val result = runCatching { graph.bankLedger.backfill() }
        transient.value = Transient(
            scanning = false,
            message = result.getOrNull()?.let { "+${it.filed}" },
        )
    }

    /** Re-reads every message from scratch, for after the parser learns a new bank's wording. */
    fun rescanAll() = viewModelScope.launch {
        if (transient.value.scanning) return@launch
        transient.value = Transient(scanning = true)
        runCatching {
            graph.bankLedger.resetScan()
            graph.bankLedger.backfill()
        }
        transient.value = Transient(scanning = false)
    }

    fun clearMessage() {
        transient.value = transient.value.copy(message = null)
    }

    private fun matches(row: BankTxEntity, filter: BankFilter): Boolean = when (filter) {
        BankFilter.ALL -> true
        BankFilter.DEPOSIT -> row.kind == BankTxKind.DEPOSIT
        BankFilter.WITHDRAWAL -> row.kind == BankTxKind.WITHDRAWAL
    }

    private fun totalsOf(rows: List<BankTxEntity>): List<BankTotals> =
        rows.groupBy { it.currency }.map { (currency, group) ->
            BankTotals(
                currency = currency,
                deposited = group.filter { it.kind == BankTxKind.DEPOSIT }.sumOf { it.amount },
                withdrawn = group.filter { it.kind == BankTxKind.WITHDRAWAL }.sumOf { it.amount },
            )
        }.sortedByDescending { it.deposited + it.withdrawn }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
