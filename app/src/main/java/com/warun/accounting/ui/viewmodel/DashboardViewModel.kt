package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warun.accounting.data.AccountingRepository
import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.MonthlySubmissionStatus
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.SupplierCandidateRecord
import com.warun.accounting.ui.model.DashboardUiState
import com.warun.accounting.ui.util.todayString
import com.warun.accounting.util.normalizePaymentMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: AccountingRepository
) : ViewModel() {
    private data class BaseUiStateParts(
        val reports: List<DailyReport>,
        val receipts: List<ReceiptRecord>,
        val expenses: List<ExpenseRecord>,
        val submissions: List<MonthlySubmission>,
        val settings: AppSettings?
    )

    private val baseUiStateParts = combine(
        repository.observeDailyReports(),
        repository.observeReceipts(),
        repository.observeExpenseRecords(),
        repository.observeMonthlySubmissions(),
        repository.observeAppSettings()
    ) { reports, receipts, expenses, submissions, settings ->
        BaseUiStateParts(
            reports = reports,
            receipts = receipts,
            expenses = expenses,
            submissions = submissions,
            settings = settings
        )
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        baseUiStateParts,
        repository.observeSupplierCandidates()
    ) { parts, supplierCandidates ->
        DashboardUiState(
            reports = parts.reports,
            receipts = parts.receipts,
            expenses = parts.expenses,
            monthlySubmissions = parts.submissions,
            supplierCandidates = supplierCandidates,
            appSettings = parts.settings
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState()
    )
    fun saveDailyReport(input: DailyReportInput) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val reportDate = input.reportDate.ifBlank { todayString() }
            val utilityBreakdownTotal = input.utilityBreakdownTotal()
            val utilitiesTotal = utilityBreakdownTotal.takeIf { it > 0L } ?: input.utilitiesExpense.toLongOrZero()
            repository.saveDailyReport(
                DailyReport(
                    id = input.id.ifBlank { reportDate },
                    reportDate = reportDate,
                    status = input.status,
                    authorName = input.authorName.ifBlank { null },
                    cashSales = input.cashSales.toLongOrZero(),
                    cardSales = input.cardSales.toLongOrZero(),
                    qrSales = input.qrSales.toLongOrZero(),
                    accountsReceivableSales = input.accountsReceivableSales.toLongOrZero(),
                    otherSales = input.otherSales.toLongOrZero(),
                    foodPurchases = 0L,
                    alcoholPurchases = 0L,
                    consumablesExpense = input.consumablesExpense.toLongOrZero(),
                    utilitiesExpense = utilitiesTotal,
                    electricityExpense = input.electricityExpense.toLongOrZero(),
                    gasExpense = input.gasExpense.toLongOrZero(),
                    waterExpense = input.waterExpense.toLongOrZero(),
                    communicationExpense = input.communicationExpense.toLongOrZero(),
                    rentExpense = input.rentExpense.toLongOrZero(),
                    accountantFeeExpense = input.accountantFeeExpense.toLongOrZero(),
                    miscellaneousExpense = input.miscellaneousExpense.toLongOrZero(),
                    otherExpense = 0L,
                    openingCash = input.openingCash.toLongOrZero(),
                    actualClosingCash = input.actualClosingCash.toLongOrZero(),
                    customerCount = input.customerCount.toIntOrZero(),
                    groupCount = input.groupCount.toIntOrZero(),
                    memo = input.memo.ifBlank { null },
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    fun saveReceipt(input: ReceiptInput) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val purchaseDate = input.purchaseDate.ifBlank { null }
            repository.saveReceipt(
                ReceiptRecord(
                    id = input.id.ifBlank { "receipt-$now" },
                    purchaseDate = purchaseDate,
                    capturedDate = input.capturedDate.ifBlank { null },
                    registeredAt = input.registeredAt ?: now,
                    storeName = input.storeName.ifBlank { null },
                    totalAmount = input.totalAmount.toLongOrZero(),
                    taxAmount = input.taxAmount.toLongOrZero(),
                    registrationNumber = input.registrationNumber.ifBlank { null },
                    expenseCategory = input.expenseCategory.ifBlank { null },
                    isConfirmed = input.isConfirmed && purchaseDate != null,
                    memo = input.memo.ifBlank { null },
                    updatedAt = now
                )
            )
        }
    }

    fun saveExpense(input: ExpenseInput) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val expenseDate = input.expenseDate.ifBlank { todayString() }
            val amount = input.amount.trim().toLongOrNull()?.takeIf { it > 0L } ?: return@launch
            val paymentMethod = normalizePaymentMethod(input.paymentMethod)
            repository.saveExpenseRecord(
                ExpenseRecord(
                    id = input.id.ifBlank { UUID.randomUUID().toString() },
                    expenseDate = expenseDate,
                    category = input.category,
                    supplierName = input.supplierName.ifBlank { null },
                    amount = amount,
                    paymentMethod = paymentMethod,
                    memo = input.memo.ifBlank { null },
                    receiptId = input.receiptId.ifBlank { null },
                    sourceType = input.sourceType.ifBlank { ExpenseSourceType.Manual },
                    createdAt = input.createdAt ?: now,
                    updatedAt = now
                )
            )
        }
    }

    fun addSupplierCandidate(category: String, name: String, paymentMethod: String) {
        val trimmedName = name.trim()
        if (category.isBlank() || trimmedName.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repository.saveSupplierCandidate(
                SupplierCandidateRecord(
                    id = "supplier-${category}-${trimmedName}",
                    category = category,
                    name = trimmedName,
                    paymentMethod = normalizePaymentMethod(paymentMethod),
                    isDefault = false,
                    isHidden = false,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    fun hideSupplierCandidate(candidate: SupplierCandidateRecord) {
        viewModelScope.launch {
            repository.saveSupplierCandidate(
                candidate.copy(
                    isHidden = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }
    fun deleteExpense(expense: ExpenseRecord) {
        viewModelScope.launch {
            repository.deleteExpenseRecord(expense)
        }
    }

    fun deleteReceipt(receipt: ReceiptRecord) {
        viewModelScope.launch {
            repository.deleteReceipt(receipt)
        }
    }

    fun markMonthSubmitted(targetMonth: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repository.saveMonthlySubmission(
                MonthlySubmission(
                    targetMonth = targetMonth,
                    status = MonthlySubmissionStatus.Submitted,
                    submittedAt = now,
                    updatedAt = now
                )
            )
        }
    }

    fun saveAppSettings(input: AppSettingsInput) {
        viewModelScope.launch {
            repository.saveAppSettings(
                AppSettings(
                    storeName = input.storeName.ifBlank { "小規模飲食店" },
                    ownerName = input.ownerName,
                    address = input.address,
                    accountantNote = input.accountantNote,
                    useCashPayment = input.useCashPayment,
                    useCardPayment = input.useCardPayment,
                    useQrPayment = input.useQrPayment,
                    useAccountsReceivablePayment = input.useAccountsReceivablePayment,
                    useOtherPayment = input.useOtherPayment
                )
            )
        }
    }

    private fun DailyReportInput.utilityBreakdownTotal(): Long =
        electricityExpense.toLongOrZero() + gasExpense.toLongOrZero() + waterExpense.toLongOrZero()

    private fun String.toLongOrZero(): Long = filter { it.isDigit() }.toLongOrNull() ?: 0L
    private fun String.toIntOrZero(): Int = filter { it.isDigit() }.toIntOrNull() ?: 0
}

data class DailyReportInput(
    val id: String = "",
    val reportDate: String = todayString(),
    val status: String = DailyReportStatus.Draft,
    val authorName: String = "本人",
    val cashSales: String = "",
    val cardSales: String = "",
    val qrSales: String = "",
    val accountsReceivableSales: String = "",
    val otherSales: String = "",
    val foodPurchases: String = "",
    val alcoholPurchases: String = "",
    val consumablesExpense: String = "",
    val utilitiesExpense: String = "",
    val electricityExpense: String = "",
    val gasExpense: String = "",
    val waterExpense: String = "",
    val communicationExpense: String = "",
    val rentExpense: String = "",
    val accountantFeeExpense: String = "",
    val miscellaneousExpense: String = "",
    val otherExpense: String = "",
    val openingCash: String = "",
    val actualClosingCash: String = "",
    val customerCount: String = "",
    val groupCount: String = "",
    val memo: String = ""
)

data class ReceiptInput(
    val id: String = "",
    val purchaseDate: String = "",
    val capturedDate: String = "",
    val registeredAt: Long? = null,
    val storeName: String = "",
    val totalAmount: String = "",
    val taxAmount: String = "",
    val registrationNumber: String = "",
    val expenseCategory: String = "",
    val isConfirmed: Boolean = false,
    val memo: String = ""
)

data class ExpenseInput(
    val id: String = "",
    val expenseDate: String = "",
    val category: String = "",
    val supplierName: String = "",
    val amount: String = "",
    val paymentMethod: String = "",
    val memo: String = "",
    val receiptId: String = "",
    val sourceType: String = ExpenseSourceType.Manual,
    val createdAt: Long? = null
)

data class AppSettingsInput(
    val storeName: String = "",
    val ownerName: String = "",
    val address: String = "",
    val accountantNote: String = "",
    val useCashPayment: Boolean = true,
    val useCardPayment: Boolean = false,
    val useQrPayment: Boolean = false,
    val useAccountsReceivablePayment: Boolean = false,
    val useOtherPayment: Boolean = false
)