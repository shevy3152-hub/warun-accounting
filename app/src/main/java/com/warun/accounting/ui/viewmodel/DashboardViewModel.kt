package com.warun.accounting.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.data.AccountingRepository
import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.EvidenceRecordState
import com.warun.accounting.data.local.ExpenseEvidenceLinkRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.MonthlySubmissionStatus
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.SupplierCandidateRecord
import com.warun.accounting.data.prepaid.PrepaidRepository
import com.warun.accounting.evidence.EvidenceSaveCoordinator
import com.warun.accounting.evidence.EvidenceFileReference
import com.warun.accounting.evidence.EvidenceFinalizationEntry
import com.warun.accounting.evidence.EvidenceRecoveryNotice
import com.warun.accounting.evidence.EvidenceRecoveryNoticeController
import com.warun.accounting.evidence.noticeIssueKeys
import com.warun.accounting.ui.model.DashboardUiState
import com.warun.accounting.ui.model.normalizeSupplierCandidateName
import com.warun.accounting.ui.util.todayString
import com.warun.accounting.util.isSupportedPaymentMethod
import com.warun.accounting.util.normalizePaymentMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: AccountingRepository,
    private val prepaidRepository: PrepaidRepository,
    private val evidenceSaveCoordinator: EvidenceSaveCoordinator,
    private val evidenceRecoveryNoticeController: EvidenceRecoveryNoticeController
) : ViewModel() {
    companion object {
        private const val LogTag = "DashboardViewModel"
    }
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
        repository.observeSupplierCandidates(),
        repository.observeStoredExpenseEvidence(),
        prepaidRepository.observeAllTransactions()
    ) { parts, supplierCandidates, expenseEvidence, prepaidTransactions ->
        DashboardUiState(
            reports = parts.reports,
            receipts = parts.receipts,
            expenses = parts.expenses,
            expenseEvidence = expenseEvidence,
            monthlySubmissions = parts.submissions,
            supplierCandidates = supplierCandidates,
            appSettings = parts.settings,
            prepaidTransactions = prepaidTransactions
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState()
    )

    private val _evidenceRecoveryNotice = MutableStateFlow(EvidenceRecoveryNotice())
    val evidenceRecoveryNotice: StateFlow<EvidenceRecoveryNotice> =
        _evidenceRecoveryNotice.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                evidenceSaveCoordinator.recoverPendingFinalizations(
                    savedExpenses = repository.observeExpenseRecords().first(),
                    hasPersistedEvidenceLink = repository::hasExpenseEvidenceLink,
                    onPromoted = ::persistPromotedEvidence
                )
            }.onSuccess { recovery ->
                recovery.failures.forEach { failure ->
                    Log.e(LogTag, "Failed to recover evidence ${failure.captureId}", failure.error)
                }
                recovery.quarantinedCaptureIds.forEach { captureId ->
                    Log.e(LogTag, "Quarantined corrupt evidence journal for captureId=$captureId")
                }
                if (recovery.unidentifiedQuarantinedCount > 0) {
                    Log.e(
                        LogTag,
                        "Quarantined corrupt evidence journals with unknown owner: " +
                            recovery.unidentifiedQuarantinedCount
                    )
                }
                _evidenceRecoveryNotice.value = evidenceRecoveryNoticeController.evaluate(
                    recovery.noticeIssueKeys()
                )
            }.onFailure { error ->
                Log.e(LogTag, "Failed to load evidence finalization journal", error)
                _evidenceRecoveryNotice.value = evidenceRecoveryNoticeController.evaluate(
                    listOf("journal-load:${error::class.java.name}:${error.message.orEmpty()}")
                )
            }
        }
    }

    fun dismissEvidenceRecoveryNotice() {
        _evidenceRecoveryNotice.value = evidenceRecoveryNoticeController.acknowledge(
            _evidenceRecoveryNotice.value
        )
    }

    fun saveDailyReport(input: DailyReportInput, onResult: (Result<Unit>) -> Unit = {}) {
        saveDailyReportWithExpense(input, null, onResult)
    }

    fun saveDailyReportWithExpense(
        input: DailyReportInput,
        expenseInput: ExpenseInput?,
        onResult: (Result<Unit>) -> Unit = {}
    ) = saveDailyReportWithExpenseAndEvidence(input, expenseInput, null, onResult)

    fun saveDailyReportWithExpenseAndEvidence(
        input: DailyReportInput,
        expenseInput: ExpenseInput?,
        pendingCapture: ReceiptCaptureResult?,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val now = System.currentTimeMillis()
                val report = input.toDailyReport(now)
                val expense = expenseInput?.toExpenseRecord(now)
                evidenceSaveCoordinator.saveDailyReportWithExpenseAndLinkEvidence(
                    expense = expense,
                    expenseDraftId = expenseInput?.id,
                    pendingCapture = pendingCapture,
                    findSavedExpense = ::findSavedExpense,
                    hasPersistedEvidenceLink = repository::hasExpenseEvidenceLink,
                    saveAccounting = { inspectedEvidence ->
                        if (inspectedEvidence == null) {
                            repository.saveDailyReportWithExpense(report = report, expense = expense)
                        } else {
                            val linkedExpense = requireNotNull(expense)
                            repository.saveDailyReportWithExpenseAndEvidence(
                                report = report,
                                expense = linkedExpense,
                                evidence = inspectedEvidence.toEvidenceRecord(
                                    state = EvidenceRecordState.Pending,
                                    createdAt = pendingCapture?.capturedAt ?: now,
                                    storedAt = null,
                                    updatedAt = now
                                ),
                                link = inspectedEvidence.toExpenseLink(linkedExpense.id, now)
                            )
                        }
                    },
                    onPromoted = ::persistPromotedEvidence
                )
            }
            result.onFailure { Log.e(LogTag, "Failed to save daily report transaction", it) }
            onResult(result)
        }
    }

    fun saveReceipt(input: ReceiptInput, onResult: (Result<Unit>) -> Unit = {}) {
        saveReceiptWithExpense(input, null, onResult)
    }

    fun saveReceiptWithExpense(
        input: ReceiptInput,
        expenseInput: ExpenseInput?,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val now = System.currentTimeMillis()
                val receipt = input.toReceiptRecord(now)
                val expense = expenseInput
                    ?.copy(receiptId = receipt.id, sourceType = ExpenseSourceType.Receipt)
                    ?.toExpenseRecord(now)
                repository.saveReceiptWithExpense(receipt = receipt, expense = expense)
            }
            result.onFailure { Log.e(LogTag, "Failed to save receipt transaction", it) }
            onResult(result)
        }
    }

    fun saveExpense(input: ExpenseInput, onResult: (Result<Unit>) -> Unit = {}) {
        saveExpenseWithEvidence(input, null, onResult)
    }

    fun saveExpenseWithEvidence(
        input: ExpenseInput,
        pendingCapture: ReceiptCaptureResult?,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val expense = input.toExpenseRecord(System.currentTimeMillis())
                evidenceSaveCoordinator.saveExpenseAndLinkEvidence(
                    expense = expense,
                    expenseDraftId = input.id,
                    pendingCapture = pendingCapture,
                    findSavedExpense = ::findSavedExpense,
                    hasPersistedEvidenceLink = repository::hasExpenseEvidenceLink,
                    saveAccounting = { inspectedEvidence ->
                        if (inspectedEvidence == null) {
                            repository.saveExpenseRecord(expense)
                        } else {
                            val now = System.currentTimeMillis()
                            repository.saveExpenseWithEvidence(
                                expense = expense,
                                evidence = inspectedEvidence.toEvidenceRecord(
                                    state = EvidenceRecordState.Pending,
                                    createdAt = pendingCapture?.capturedAt ?: now,
                                    storedAt = null,
                                    updatedAt = now
                                ),
                                link = inspectedEvidence.toExpenseLink(expense.id, now)
                            )
                        }
                    },
                    onPromoted = ::persistPromotedEvidence
                )
            }
            result.onFailure { Log.e(LogTag, "Failed to save expense", it) }
            onResult(result)
        }
    }

    private suspend fun findSavedExpense(expenseId: String): ExpenseRecord? =
        repository.observeExpenseRecords().first().firstOrNull { it.id == expenseId }

    private suspend fun persistPromotedEvidence(
        entry: EvidenceFinalizationEntry,
        reference: EvidenceFileReference
    ) {
        val now = System.currentTimeMillis()
        repository.finalizeExpenseEvidence(
            expenseId = entry.expenseRecordId,
            evidence = reference.toEvidenceRecord(
                state = EvidenceRecordState.Stored,
                createdAt = entry.createdAt,
                storedAt = reference.storedAt,
                updatedAt = now
            ),
            link = reference.toExpenseLink(entry.expenseRecordId, entry.createdAt)
        )
    }

    private fun EvidenceFileReference.toEvidenceRecord(
        state: String,
        createdAt: Long,
        storedAt: Long?,
        updatedAt: Long
    ) = EvidenceRecord(
        id = evidenceId,
        captureId = evidenceId,
        storedUri = localUri,
        byteSize = byteSize,
        sha256 = sha256,
        state = state,
        createdAt = createdAt,
        storedAt = storedAt,
        updatedAt = updatedAt
    )

    private fun EvidenceFileReference.toExpenseLink(expenseId: String, linkedAt: Long) =
        ExpenseEvidenceLinkRecord(
            expenseId = expenseId,
            evidenceId = evidenceId,
            linkedAt = linkedAt
        )

    fun addSupplierCandidate(category: String, name: String, paymentMethod: String) {
        val normalizedName = normalizeSupplierCandidateName(name)
        if (category.isBlank() || normalizedName.isBlank()) return
        viewModelScope.launch {
            val alreadyRegistered = repository.observeSupplierCandidates().first().any { candidate ->
                candidate.category == category &&
                    normalizeSupplierCandidateName(candidate.name) == normalizedName
            }
            if (alreadyRegistered) return@launch
            val now = System.currentTimeMillis()
            repository.saveSupplierCandidate(
                SupplierCandidateRecord(
                    id = "supplier-${category}-${normalizedName}",
                    category = category,
                    name = normalizedName,
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

    private fun DailyReportInput.toDailyReport(now: Long): DailyReport {
        val reportDateValue = reportDate.ifBlank { todayString() }
        val utilityBreakdownTotal = utilityBreakdownTotal()
        val utilitiesTotal = utilityBreakdownTotal.takeIf { it > 0L } ?: utilitiesExpense.toLongOrZero()
        return DailyReport(
            id = id.ifBlank { reportDateValue },
            reportDate = reportDateValue,
            status = status,
            authorName = authorName.ifBlank { null },
            cashSales = cashSales.toLongOrZero(),
            cardSales = cardSales.toLongOrZero(),
            qrSales = qrSales.toLongOrZero(),
            accountsReceivableSales = accountsReceivableSales.toLongOrZero(),
            otherSales = otherSales.toLongOrZero(),
            foodPurchases = 0L,
            alcoholPurchases = 0L,
            consumablesExpense = consumablesExpense.toLongOrZero(),
            utilitiesExpense = utilitiesTotal,
            electricityExpense = electricityExpense.toLongOrZero(),
            gasExpense = gasExpense.toLongOrZero(),
            waterExpense = waterExpense.toLongOrZero(),
            communicationExpense = communicationExpense.toLongOrZero(),
            rentExpense = rentExpense.toLongOrZero(),
            accountantFeeExpense = accountantFeeExpense.toLongOrZero(),
            miscellaneousExpense = miscellaneousExpense.toLongOrZero(),
            otherExpense = 0L,
            openingCash = openingCash.toLongOrZero(),
            actualClosingCash = actualClosingCash.toLongOrZero(),
            customerCount = customerCount.toIntOrZero(),
            groupCount = groupCount.toIntOrZero(),
            memo = memo.ifBlank { null },
            createdAt = now,
            updatedAt = now,
            hasActualClosingCash = actualClosingCash.isNotBlank()
        )
    }

    private fun ReceiptInput.toReceiptRecord(now: Long): ReceiptRecord {
        val purchaseDateValue = purchaseDate.ifBlank { null }
        return ReceiptRecord(
            id = id.ifBlank { "receipt-$now" },
            purchaseDate = purchaseDateValue,
            capturedDate = capturedDate.ifBlank { null },
            registeredAt = registeredAt ?: now,
            storeName = storeName.ifBlank { null },
            totalAmount = totalAmount.toLongOrZero(),
            taxAmount = taxAmount.toLongOrZero(),
            registrationNumber = registrationNumber.ifBlank { null },
            expenseCategory = expenseCategory.ifBlank { null },
            isConfirmed = isConfirmed && purchaseDateValue != null,
            memo = memo.ifBlank { null },
            updatedAt = now
        )
    }

    private fun ExpenseInput.toExpenseRecord(now: Long): ExpenseRecord {
        val amountValue = amount.trim().toLongOrNull()?.takeIf { it > 0L }
            ?: error("1円以上の金額を入力してください")
        val paymentMethodValue = normalizePaymentMethod(paymentMethod)
        check(isSupportedPaymentMethod(paymentMethodValue)) { "支払方法を選択してください" }
        return ExpenseRecord(
            id = id.ifBlank { UUID.randomUUID().toString() },
            expenseDate = expenseDate.ifBlank { todayString() },
            category = category,
            supplierName = supplierName.ifBlank { null },
            amount = amountValue,
            paymentMethod = paymentMethodValue,
            memo = memo.ifBlank { null },
            receiptId = receiptId.ifBlank { null },
            sourceType = sourceType.ifBlank { ExpenseSourceType.Manual },
            createdAt = createdAt ?: now,
            updatedAt = now
        )
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
    val openingCash: String = DefaultOpeningCashYen.toString(),
    val actualClosingCash: String = "",
    val customerCount: String = "",
    val groupCount: String = "",
    val memo: String = ""
)

const val DefaultOpeningCashYen = 100_000L

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
