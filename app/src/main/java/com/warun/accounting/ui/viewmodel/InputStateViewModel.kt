package com.warun.accounting.ui.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseSourceType
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

    fun discardReportChanges() {
        reportInputState.value = cleanReportInputState.value
        expenseFormDirtyState.value = false
        utilityFieldsEditedState.value = false
        draftExpenseInputState.value = null
    }

    fun completeReceiptSave(): ReceiptInput {
        val next = newReceiptInput()
        receiptInputState.value = next
        return next
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
