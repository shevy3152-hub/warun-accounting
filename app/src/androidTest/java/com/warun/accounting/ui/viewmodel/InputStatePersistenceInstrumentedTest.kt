package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.ui.receipt.ReceiptOcrApplyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun currentOcrCaptureUpdatesRestoredExpenseDraftAndKeepsOwnership() {
        val handle = SavedStateHandle()
        val state = InputStateViewModel(handle)
        state.draftExpenseInputState.value = ExpenseInput(
            id = "device-expense-id",
            expenseDate = "",
            category = "food_purchase",
            supplierName = "",
            amount = "",
            paymentMethod = "クレジット",
            memo = "A90保持"
        )
        val capture = ReceiptCaptureResult(
            captureId = "device-capture-id",
            localUri = "file:/pending/device.jpg",
            capturedAt = 1L
        )

        assertTrue(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, "バロー（岐南店）", "2026-07-20", "1540"),
                expectedCaptureId = capture.captureId
            )
        )

        val restored = InputStateViewModel(handle)
        assertEquals("バロー（岐南店）", restored.draftExpenseInputState.value?.supplierName)
        assertEquals("2026-07-20", restored.draftExpenseInputState.value?.expenseDate)
        assertEquals("1540", restored.draftExpenseInputState.value?.amount)
        assertEquals("クレジット", restored.draftExpenseInputState.value?.paymentMethod)
        assertEquals("A90保持", restored.draftExpenseInputState.value?.memo)
        assertEquals(capture, restored.pendingCaptureFor("device-expense-id"))
    }
}
