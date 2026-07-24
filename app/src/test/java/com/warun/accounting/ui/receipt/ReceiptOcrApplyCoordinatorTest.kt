package com.warun.accounting.ui.receipt

import androidx.lifecycle.SavedStateHandle
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.ui.viewmodel.ExpenseInput
import com.warun.accounting.ui.viewmodel.InputStateViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptOcrApplyCoordinatorTest {
    private val capture = ReceiptCaptureResult(
        captureId = "current-capture",
        localUri = "file:/pending/current.jpg",
        capturedAt = 1L
    )
    private val result = ReceiptOcrApplyResult(
        capture = capture,
        supplierName = "バロー（岐南店）",
        expenseDate = "2026-07-20",
        amount = "1540"
    )

    @Test
    fun successfulApplyConsumesOcrStateExactlyOnce() {
        var applyCalls = 0
        var consumeCalls = 0

        assertTrue(
            applyCurrentReceiptOcr(
                result = result,
                expectedCaptureId = capture.captureId,
                applyToExpense = { _, _ -> applyCalls++; true },
                onConsumed = { consumeCalls++ }
            )
        )

        assertTrue(applyCalls == 1)
        assertTrue(consumeCalls == 1)
    }

    @Test
    fun failedApplyKeepsOcrStateUnconsumed() {
        var consumeCalls = 0

        assertFalse(
            applyCurrentReceiptOcr(
                result = result,
                expectedCaptureId = capture.captureId,
                applyToExpense = { _, _ -> false },
                onConsumed = { consumeCalls++ }
            )
        )

        assertTrue(consumeCalls == 0)
    }

    @Test
    fun staleCaptureNeverReachesExpenseStateOrConsume() {
        var applyCalls = 0
        var consumeCalls = 0

        assertFalse(
            applyCurrentReceiptOcr(
                result = result,
                expectedCaptureId = "new-capture",
                applyToExpense = { _, _ -> applyCalls++; true },
                onConsumed = { consumeCalls++ }
            )
        )

        assertTrue(applyCalls == 0)
        assertTrue(consumeCalls == 0)
    }

    @Test
    fun panelApplyPathUpdatesSharedExpenseStateThenConsumesOcr() {
        val inputState = InputStateViewModel(SavedStateHandle())
        inputState.draftExpenseInputState.value = ExpenseInput(
            id = "expense-id",
            expenseDate = "2026-07-23",
            category = "food_purchase",
            supplierName = "",
            amount = "",
            paymentMethod = "クレジット",
            memo = "既存メモ"
        )
        var consumed = false

        assertTrue(
            applyCurrentReceiptOcr(
                result = result,
                expectedCaptureId = capture.captureId,
                applyToExpense = inputState::applyReceiptOcr,
                onConsumed = { consumed = true }
            )
        )

        val applied = inputState.draftExpenseInputState.value!!
        assertEquals("バロー（岐南店）", applied.supplierName)
        assertEquals("2026-07-23", applied.expenseDate)
        assertEquals("1540", applied.amount)
        assertEquals("food_purchase", applied.category)
        assertEquals("クレジット", applied.paymentMethod)
        assertEquals("既存メモ", applied.memo)
        assertEquals(capture, inputState.pendingCaptureFor("expense-id"))
        assertTrue(consumed)
    }
}
