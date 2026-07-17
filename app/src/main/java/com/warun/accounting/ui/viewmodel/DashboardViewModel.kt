package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warun.accounting.data.AccountingRepository
import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.MonthlySubmissionStatus
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.ui.model.DashboardUiState
import com.warun.accounting.ui.util.todayString
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val CurrentExpenseInputSchemaVersion = 2

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: AccountingRepository
) : ViewModel() {
    val uiState: StateFlow<DashboardUiState> = combine(
        repository.observeDailyReports(),
        repository.observeReceipts(),
        repository.observeMonthlySubmissions(),
        repository.observeAppSettings()
    ) { reports, receipts, submissions, settings ->
        DashboardUiState(
            reports = reports,
            receipts = receipts,
            monthlySubmissions = submissions,
            appSettings = settings
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
                    foodPurchases = if (input.expenseInputSchemaVersion >= CurrentExpenseInputSchemaVersion) 0L else input.foodPurchases.toLongOrZero(),
                    alcoholPurchases = if (input.expenseInputSchemaVersion >= CurrentExpenseInputSchemaVersion) 0L else input.alcoholPurchases.toLongOrZero(),
                    consumablesExpense = input.consumablesExpense.toLongOrZero(),
                    utilitiesExpense = input.utilitiesExpense.toLongOrZero(),
                    miscellaneousExpense = input.miscellaneousExpense.toLongOrZero(),
                    otherExpense = input.otherExpense.toLongOrZero(),
                    openingCash = input.openingCash.toLongOrZero(),
                    actualClosingCash = input.actualClosingCash.toLongOrZero(),
                    customerCount = input.customerCount.toIntOrZero(),
                    groupCount = input.groupCount.toIntOrZero(),
                    memo = input.memo.ifBlank { null },
                    expenseInputSchemaVersion = input.expenseInputSchemaVersion,
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
                    paymentMethod = input.paymentMethod.ifBlank { null },
                    isConfirmed = input.isConfirmed && purchaseDate != null,
                    memo = input.memo.ifBlank { null },
                    updatedAt = now
                )
            )
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

    private fun String.toLongOrZero(): Long = filter { it.isDigit() }.toLongOrNull() ?: 0L
    private fun String.toIntOrZero(): Int = filter { it.isDigit() }.toIntOrNull() ?: 0
}

data class DailyReportInput(
    val id: String = "",
    val reportDate: String = todayString(),
    val status: String = DailyReportStatus.Draft,
    val expenseInputSchemaVersion: Int = CurrentExpenseInputSchemaVersion,
    val authorName: String = "本人",
    val cashSales: String = "",
    val cardSales: String = "",
    val qrSales: String = "",
    val accountsReceivableSales: String = "",
    val otherSales: String = "",
    val foodPurchaseSupplier: String = "",
    val foodPurchases: String = "",
    val alcoholPurchaseSupplier: String = "",
    val alcoholPurchases: String = "",
    val consumablesSupplier: String = "",
    val consumablesExpense: String = "",
    val utilitiesSupplier: String = "",
    val utilitiesExpense: String = "",
    val miscellaneousSupplier: String = "",
    val miscellaneousExpense: String = "",
    val otherExpenseSupplier: String = "",
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
    val paymentMethod: String = "",
    val isConfirmed: Boolean = false,
    val memo: String = ""
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
