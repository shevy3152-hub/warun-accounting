package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputStateViewModelTest {
    @Test
    fun reportInputAndPendingDateRestoreFromSavedState() {
        val handle = SavedStateHandle()
        val first = InputStateViewModel(handle)
        val initial = DailyReportInput(reportDate = "2026-07-20")
        first.initializeReport(initial)
        first.reportInputState.value = initial.copy(cashSales = "15000", customerCount = "")
        first.pendingReportDateState.value = "2026-07-21"

        val restored = InputStateViewModel(handle)

        assertEquals("15000", restored.reportInputState.value.cashSales)
        assertEquals("", restored.reportInputState.value.customerCount)
        assertEquals("2026-07-21", restored.pendingReportDateState.value)
        assertEquals(initial, restored.cleanReportInputState.value)
    }

    @Test
    fun expenseInputAndStableUuidRestoreFromSavedState() {
        val handle = SavedStateHandle()
        val first = InputStateViewModel(handle)
        val expense = ExpenseInput(
            id = "stable-expense-id",
            expenseDate = "2026-07-20",
            category = "food_purchase",
            supplierName = "バロー",
            amount = "01000",
            paymentMethod = "現金",
            memo = "入力中"
        )
        first.expenseFormDirtyState.value = true
        first.draftExpenseInputState.value = expense

        val restored = InputStateViewModel(handle)

        assertTrue(restored.expenseFormDirtyState.value)
        assertEquals(expense, restored.draftExpenseInputState.value)
        assertEquals("stable-expense-id", restored.draftExpenseInputState.value?.id)
        assertEquals("01000", restored.draftExpenseInputState.value?.amount)
    }

    @Test
    fun receiptInputRestoresAndOnlySuccessCreatesNextUuid() {
        val handle = SavedStateHandle()
        val first = InputStateViewModel(handle)
        val originalId = first.receiptInputState.value.id
        first.receiptInputState.value = first.receiptInputState.value.copy(
            storeName = "入力中の店舗",
            totalAmount = "",
            memo = "未保存"
        )

        val restored = InputStateViewModel(handle)
        assertEquals(originalId, restored.receiptInputState.value.id)
        assertEquals("入力中の店舗", restored.receiptInputState.value.storeName)
        assertEquals("", restored.receiptInputState.value.totalAmount)

        // Failure performs no reset, so both the input and UUID remain unchanged.
        assertEquals(originalId, restored.receiptInputState.value.id)
        val next = restored.completeReceiptSave()
        assertNotEquals(originalId, next.id)
        assertEquals("", next.storeName)
    }

    @Test
    fun reportSaveAndDiscardResetOnlyDraftState() {
        val handle = SavedStateHandle()
        val state = InputStateViewModel(handle)
        val initial = DailyReportInput(reportDate = "2026-07-20")
        state.initializeReport(initial)
        state.reportInputState.value = initial.copy(cashSales = "10000")
        state.expenseFormDirtyState.value = true
        state.draftExpenseInputState.value = ExpenseInput(id = "expense-id", amount = "1")

        // A failed save does not call markReportSaved, so the draft remains.
        assertEquals("10000", state.reportInputState.value.cashSales)
        assertEquals("expense-id", state.draftExpenseInputState.value?.id)

        val saved = state.reportInputState.value
        state.markReportSaved(saved)
        assertEquals(saved, state.cleanReportInputState.value)
        assertNull(state.draftExpenseInputState.value)
        assertTrue(!state.expenseFormDirtyState.value)
    }
}
