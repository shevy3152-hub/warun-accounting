package com.warun.accounting.ui.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.ui.receipt.ReceiptOcrApplyResult
import com.warun.accounting.ui.receipt.planReceiptOcrMerge
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class InputStateViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private companion object {
        const val ReportInputKey = "input.report"
        const val CleanReportInputKey = "input.report.clean"
        const val ReportInitializedKey = "input.report.initialized"
        const val PendingReportDateKey = "input.report.pendingDate"
        const val ExpenseDirtyKey = "input.expense.dirty"
        const val UtilityEditedKey = "input.utility.edited"
        const val ExpenseDraftKey = "input.expense.draft"
        const val PendingExpenseCaptureKey = "input.expense.pendingCapture"
        const val PendingExpenseCaptureOwnerKey = "input.expense.pendingCaptureOwner"
        const val AppliedOcrCaptureIdKey = "input.expense.appliedOcrCaptureId"
        const val ReceiptInputKey = "input.receipt"
    }

    val reportInputState = persistedState(
        ReportInputKey,
        savedStateHandle.get<ArrayList<String>>(ReportInputKey)?.toDailyReportInput() ?: DailyReportInput()
    ) { it.toSavedStrings() }

    val cleanReportInputState = persistedState(
        CleanReportInputKey,
        savedStateHandle.get<ArrayList<String>>(CleanReportInputKey)?.toDailyReportInput() ?: DailyReportInput()
    ) { it.toSavedStrings() }

    val pendingReportDateState = persistedNullableString(PendingReportDateKey)
    val expenseFormDirtyState = persistedState(ExpenseDirtyKey, savedStateHandle[ExpenseDirtyKey] ?: false) { it }
    val utilityFieldsEditedState = persistedState(UtilityEditedKey, savedStateHandle[UtilityEditedKey] ?: false) { it }
    val draftExpenseInputState = persistedNullableList(
        ExpenseDraftKey,
        savedStateHandle.get<ArrayList<String>>(ExpenseDraftKey)?.toExpenseInput(),
        { it.toSavedStrings() }
    )
    val pendingExpenseCaptureState = persistedNullableList(
        PendingExpenseCaptureKey,
        savedStateHandle.get<ArrayList<String>>(PendingExpenseCaptureKey)?.toReceiptCaptureResult(),
        { it.toSavedStrings() }
    )
    val receiptInputState = persistedState(
        ReceiptInputKey,
        savedStateHandle.get<ArrayList<String>>(ReceiptInputKey)?.toReceiptInput() ?: newReceiptInput()
    ) { it.toSavedStrings() }

    val reportSavingStatusState = mutableStateOf<String?>(null)
    val receiptSavingState = mutableStateOf(false)

    fun initializeReport(input: DailyReportInput) {
        if (savedStateHandle.get<Boolean>(ReportInitializedKey) == true) return
        reportInputState.value = input
        cleanReportInputState.value = input
        savedStateHandle[ReportInitializedKey] = true
    }

    fun openReport(input: DailyReportInput) {
        reportInputState.value = input
        cleanReportInputState.value = input
        pendingReportDateState.value = null
        expenseFormDirtyState.value = false
        utilityFieldsEditedState.value = false
        draftExpenseInputState.value = null
    }

    fun markReportSaved(input: DailyReportInput) {
        reportInputState.value = input
        cleanReportInputState.value = input
        expenseFormDirtyState.value = false
        utilityFieldsEditedState.value = false
        draftExpenseInputState.value = null
    }

    fun pendingCaptureOwnedByCurrentDraft(): ReceiptCaptureResult? {
        val currentDraftId = draftExpenseInputState.value?.id?.takeIf { it.isNotBlank() }
            ?: return null
        return pendingExpenseCaptureState.value?.takeIf {
            savedStateHandle.get<String>(PendingExpenseCaptureOwnerKey) == currentDraftId
        }
    }

    fun discardReportChanges(): ReceiptCaptureResult? {
        val ownedPendingCapture = pendingCaptureOwnedByCurrentDraft()
        reportInputState.value = cleanReportInputState.value
        expenseFormDirtyState.value = false
        utilityFieldsEditedState.value = false
        draftExpenseInputState.value = null
        if (ownedPendingCapture != null) {
            clearPendingExpenseCapture()
        }
        return ownedPendingCapture
    }

    fun completeReceiptSave(): ReceiptInput {
        val next = newReceiptInput()
        receiptInputState.value = next
        return next
    }

    fun applyReceiptOcr(result: ReceiptOcrApplyResult, expectedCaptureId: String): Boolean {
        val current = draftExpenseInputState.value ?: return false
        if (current.id.isBlank()) return false
        if (result.capture.captureId != expectedCaptureId) return false
        if (savedStateHandle.get<String>(AppliedOcrCaptureIdKey) == result.capture.captureId) {
            return false
        }
        draftExpenseInputState.value = planReceiptOcrMerge(current, result).mergedExpense
        pendingExpenseCaptureState.value = result.capture
        savedStateHandle[PendingExpenseCaptureOwnerKey] = current.id
        savedStateHandle[AppliedOcrCaptureIdKey] = result.capture.captureId
        expenseFormDirtyState.value = true
        return true
    }

    fun pendingCaptureFor(expenseId: String): ReceiptCaptureResult? {
        if (expenseId.isBlank()) return null
        return pendingExpenseCaptureState.value?.takeIf {
            savedStateHandle.get<String>(PendingExpenseCaptureOwnerKey) == expenseId
        }
    }

    fun markPendingEvidenceStored(expenseId: String, captureId: String): Boolean {
        val capture = pendingCaptureFor(expenseId) ?: return false
        if (capture.captureId != captureId) return false
        clearPendingExpenseCapture()
        return true
    }

    fun discardPendingExpenseCapture(): ReceiptCaptureResult? {
        val capture = pendingExpenseCaptureState.value ?: return null
        clearPendingExpenseCapture()
        return capture
    }

    private fun clearPendingExpenseCapture() {
        pendingExpenseCaptureState.value = null
        savedStateHandle.remove<String>(PendingExpenseCaptureOwnerKey)
        savedStateHandle.remove<String>(AppliedOcrCaptureIdKey)
    }

    private fun newReceiptInput() = ReceiptInput(
        id = UUID.randomUUID().toString(),
        capturedDate = java.time.LocalDate.now().toString()
    )

    private fun <T> persistedState(
        key: String,
        initialValue: T,
        encode: (T) -> Any
    ): MutableState<T> = PersistedMutableState(initialValue) { value ->
        savedStateHandle[key] = encode(value)
    }

    private fun persistedNullableString(key: String): MutableState<String?> =
        PersistedMutableState(savedStateHandle[key]) { value ->
            if (value == null) savedStateHandle.remove<String>(key) else savedStateHandle[key] = value
        }

    private fun <T> persistedNullableList(
        key: String,
        initialValue: T?,
        encode: (T) -> ArrayList<String>
    ): MutableState<T?> = PersistedMutableState(initialValue) { value ->
        if (value == null) savedStateHandle.remove<ArrayList<String>>(key) else savedStateHandle[key] = encode(value)
    }
}

private class PersistedMutableState<T>(
    initialValue: T,
    private val onChanged: (T) -> Unit
) : MutableState<T> {
    private val delegate = mutableStateOf(initialValue)

    override var value: T
        get() = delegate.value
        set(value) {
            delegate.value = value
            onChanged(value)
        }

    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

private fun DailyReportInput.toSavedStrings() = arrayListOf(
    id, reportDate, status, authorName, cashSales, cardSales, qrSales,
    accountsReceivableSales, otherSales, foodPurchases, alcoholPurchases,
    consumablesExpense, utilitiesExpense, electricityExpense, gasExpense,
    waterExpense, communicationExpense, rentExpense, accountantFeeExpense,
    miscellaneousExpense, otherExpense, openingCash, actualClosingCash,
    customerCount, groupCount, memo
)

private fun List<String>.toDailyReportInput() = DailyReportInput(
    id = getOrElse(0) { "" },
    reportDate = getOrElse(1) { "" },
    status = getOrElse(2) { DailyReportStatus.Draft },
    authorName = getOrElse(3) { "本人" },
    cashSales = getOrElse(4) { "" },
    cardSales = getOrElse(5) { "" },
    qrSales = getOrElse(6) { "" },
    accountsReceivableSales = getOrElse(7) { "" },
    otherSales = getOrElse(8) { "" },
    foodPurchases = getOrElse(9) { "" },
    alcoholPurchases = getOrElse(10) { "" },
    consumablesExpense = getOrElse(11) { "" },
    utilitiesExpense = getOrElse(12) { "" },
    electricityExpense = getOrElse(13) { "" },
    gasExpense = getOrElse(14) { "" },
    waterExpense = getOrElse(15) { "" },
    communicationExpense = getOrElse(16) { "" },
    rentExpense = getOrElse(17) { "" },
    accountantFeeExpense = getOrElse(18) { "" },
    miscellaneousExpense = getOrElse(19) { "" },
    otherExpense = getOrElse(20) { "" },
    openingCash = getOrElse(21) { "" },
    actualClosingCash = getOrElse(22) { "" },
    customerCount = getOrElse(23) { "" },
    groupCount = getOrElse(24) { "" },
    memo = getOrElse(25) { "" }
)

private fun ExpenseInput.toSavedStrings() = arrayListOf(
    id, expenseDate, category, supplierName, amount, paymentMethod, memo,
    receiptId, sourceType, createdAt?.toString().orEmpty()
)

private fun List<String>.toExpenseInput() = ExpenseInput(
    id = getOrElse(0) { "" },
    expenseDate = getOrElse(1) { "" },
    category = getOrElse(2) { "" },
    supplierName = getOrElse(3) { "" },
    amount = getOrElse(4) { "" },
    paymentMethod = getOrElse(5) { "" },
    memo = getOrElse(6) { "" },
    receiptId = getOrElse(7) { "" },
    sourceType = getOrElse(8) { ExpenseSourceType.Manual },
    createdAt = getOrElse(9) { "" }.toLongOrNull()
)

private fun ReceiptCaptureResult.toSavedStrings() = arrayListOf(
    captureId, localUri, capturedAt.toString()
)

private fun List<String>.toReceiptCaptureResult(): ReceiptCaptureResult? {
    val captureId = getOrElse(0) { "" }
    val capturedAt = getOrElse(2) { "" }.toLongOrNull() ?: return null
    if (captureId.isBlank()) return null
    return ReceiptCaptureResult(
        captureId = captureId,
        localUri = getOrElse(1) { "" },
        capturedAt = capturedAt
    )
}

private fun ReceiptInput.toSavedStrings() = arrayListOf(
    id, purchaseDate, capturedDate, registeredAt?.toString().orEmpty(), storeName,
    totalAmount, taxAmount, registrationNumber, expenseCategory, isConfirmed.toString(), memo
)

private fun List<String>.toReceiptInput() = ReceiptInput(
    id = getOrElse(0) { "" },
    purchaseDate = getOrElse(1) { "" },
    capturedDate = getOrElse(2) { "" },
    registeredAt = getOrElse(3) { "" }.toLongOrNull(),
    storeName = getOrElse(4) { "" },
    totalAmount = getOrElse(5) { "" },
    taxAmount = getOrElse(6) { "" },
    registrationNumber = getOrElse(7) { "" },
    expenseCategory = getOrElse(8) { "" },
    isConfirmed = getOrElse(9) { "false" }.toBoolean(),
    memo = getOrElse(10) { "" }
)
