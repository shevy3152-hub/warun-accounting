package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputStatePersistenceInstrumentedTest {
    @Test
    fun reportAndPendingDateSurviveStateHolderRecreation() {
        val handle = SavedStateHandle()
        val first = InputStateViewModel(handle)
        val initial = DailyReportInput(reportDate = "2026-07-30")
        first.initializeReport(initial)
        first.reportInputState.value = initial.copy(cashSales = "15000", groupCount = "")
        first.pendingReportDateState.value = "2026-07-31"

        val restored = InputStateViewModel(handle)

        assertEquals("15000", restored.reportInputState.value.cashSales)
        assertEquals("", restored.reportInputState.value.groupCount)
        assertEquals("2026-07-31", restored.pendingReportDateState.value)
    }

    @Test
    fun expenseDraftAndUuidSurviveStateHolderRecreation() {
        val handle = SavedStateHandle()
        val first = InputStateViewModel(handle)
        first.expenseFormDirtyState.value = true
        first.draftExpenseInputState.value = ExpenseInput(
            id = "device-stable-uuid",
            expenseDate = "2026-07-30",
            category = "food_purchase",
            supplierName = "A90入力",
            amount = "01000",
            paymentMethod = "現金"
        )

        val restored = InputStateViewModel(handle)

        assertEquals("device-stable-uuid", restored.draftExpenseInputState.value?.id)
        assertEquals("01000", restored.draftExpenseInputState.value?.amount)
    }

    @Test
    fun receiptFailureKeepsUuidAndSuccessResetsIt() {
        val handle = SavedStateHandle()
        val first = InputStateViewModel(handle)
        val originalId = first.receiptInputState.value.id
        first.receiptInputState.value = first.receiptInputState.value.copy(storeName = "A90未保存")

        val restoredAfterFailure = InputStateViewModel(handle)
        assertEquals(originalId, restoredAfterFailure.receiptInputState.value.id)
        assertEquals("A90未保存", restoredAfterFailure.receiptInputState.value.storeName)

        val reset = restoredAfterFailure.completeReceiptSave()
        assertNotEquals(originalId, reset.id)
        assertNull(restoredAfterFailure.draftExpenseInputState.value)
    }
}
