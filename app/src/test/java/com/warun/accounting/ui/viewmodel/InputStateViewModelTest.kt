package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.ui.receipt.ReceiptOcrApplyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            memo = "入力中",
            receiptId = "pending-camera-capture",
            sourceType = "manual"
        )
        first.expenseFormDirtyState.value = true
        first.draftExpenseInputState.value = expense

        val restored = InputStateViewModel(handle)

        assertTrue(restored.expenseFormDirtyState.value)
        assertEquals(expense, restored.draftExpenseInputState.value)
        assertEquals("stable-expense-id", restored.draftExpenseInputState.value?.id)
        assertEquals("01000", restored.draftExpenseInputState.value?.amount)
        assertEquals("現金", restored.draftExpenseInputState.value?.paymentMethod)
        assertEquals("入力中", restored.draftExpenseInputState.value?.memo)
        assertEquals("pending-camera-capture", restored.draftExpenseInputState.value?.receiptId)
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

    @Test
    fun ocrAppliesOnlyThreeFieldsAndRetainsPendingCapture() {
        val state = InputStateViewModel(SavedStateHandle())
        state.draftExpenseInputState.value = ExpenseInput(
            id = "expense-id",
            expenseDate = "",
            category = "food_purchase",
            supplierName = "",
            amount = "",
            paymentMethod = "クレジット",
            memo = "カメラ入力保持テスト",
            receiptId = "existing-receipt"
        )
        val capture = ReceiptCaptureResult("capture-ocr", "file:/pending/capture-ocr.jpg", 123L)

        assertTrue(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, "バロー（岐南店）", "2026-07-21", "1540"),
                expectedCaptureId = capture.captureId
            )
        )

        val applied = state.draftExpenseInputState.value!!
        assertEquals("バロー（岐南店）", applied.supplierName)
        assertEquals("2026-07-21", applied.expenseDate)
        assertEquals("1540", applied.amount)
        assertEquals("food_purchase", applied.category)
        assertEquals("クレジット", applied.paymentMethod)
        assertEquals("カメラ入力保持テスト", applied.memo)
        assertEquals("existing-receipt", applied.receiptId)
        assertEquals(capture, state.pendingExpenseCaptureState.value)
        assertEquals(capture, state.pendingCaptureFor("expense-id"))
        assertNull(state.pendingCaptureFor("another-expense-id"))
        assertTrue(state.expenseFormDirtyState.value)
    }

    @Test
    fun sameCaptureIsAppliedOnlyOnceAndPendingReferenceRestores() {
        val handle = SavedStateHandle()
        val state = InputStateViewModel(handle)
        state.draftExpenseInputState.value = ExpenseInput(id = "expense-id", amount = "100")
        val capture = ReceiptCaptureResult("capture-once", "file:/pending/capture-once.jpg", 456L)
        val first = ReceiptOcrApplyResult(capture, "最初の店", "2026-07-21", "1540")

        assertTrue(state.applyReceiptOcr(first, capture.captureId))
        assertFalse(state.applyReceiptOcr(first.copy(supplierName = "二重反映"), capture.captureId))
        assertEquals("最初の店", state.draftExpenseInputState.value?.supplierName)

        val restored = InputStateViewModel(handle)
        assertEquals(capture, restored.pendingExpenseCaptureState.value)
        assertEquals(capture, restored.pendingCaptureFor("expense-id"))
        assertFalse(restored.applyReceiptOcr(first.copy(supplierName = "再生成後の二重反映"), capture.captureId))
        assertEquals("最初の店", restored.draftExpenseInputState.value?.supplierName)
    }

    @Test
    fun secondCaptureDoesNotOverwriteFieldsFilledByFirstCapture() {
        val state = InputStateViewModel(SavedStateHandle())
        state.draftExpenseInputState.value = ExpenseInput(
            id = "shared-expense-id",
            expenseDate = "",
            category = "food_purchase",
            supplierName = "",
            amount = "",
            paymentMethod = "クレジット",
            memo = "保持するメモ"
        )
        val firstCapture = ReceiptCaptureResult("capture-first", "file:/pending/first.jpg", 1L)
        val secondCapture = ReceiptCaptureResult("capture-second", "file:/pending/second.jpg", 2L)

        assertTrue(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(firstCapture, "一回目商店", "2026-07-21", "1540"),
                expectedCaptureId = firstCapture.captureId
            )
        )
        assertTrue(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(secondCapture, "", "2026-07-22", "980"),
                expectedCaptureId = secondCapture.captureId
            )
        )

        val applied = state.draftExpenseInputState.value!!
        assertEquals("一回目商店", applied.supplierName)
        assertEquals("2026-07-21", applied.expenseDate)
        assertEquals("1540", applied.amount)
        assertEquals("food_purchase", applied.category)
        assertEquals("クレジット", applied.paymentMethod)
        assertEquals("保持するメモ", applied.memo)
        assertEquals(secondCapture, state.pendingCaptureFor("shared-expense-id"))
    }

    @Test
    fun storedEvidenceClearsOnlyMatchingTemporaryReference() {
        val handle = SavedStateHandle()
        val state = InputStateViewModel(handle)
        state.draftExpenseInputState.value = ExpenseInput(id = "expense-id", amount = "100")
        val capture = ReceiptCaptureResult("capture-stored", "file:/pending/capture-stored.jpg", 789L)
        val result = ReceiptOcrApplyResult(capture, "店舗", "2026-07-22", "1540")

        assertTrue(state.applyReceiptOcr(result, capture.captureId))
        assertFalse(state.markPendingEvidenceStored("another-expense", capture.captureId))
        assertFalse(state.markPendingEvidenceStored("expense-id", "another-capture"))
        assertEquals(capture, state.pendingCaptureFor("expense-id"))

        assertTrue(state.markPendingEvidenceStored("expense-id", capture.captureId))
        assertNull(state.pendingExpenseCaptureState.value)
        assertNull(state.pendingCaptureFor("expense-id"))

        val restored = InputStateViewModel(handle)
        assertNull(restored.pendingExpenseCaptureState.value)
        assertTrue(restored.applyReceiptOcr(result, capture.captureId))
    }

    @Test
    fun startingReplacementCaptureClearsOnlyPendingCaptureOwnership() {
        val handle = SavedStateHandle()
        val state = InputStateViewModel(handle)
        val originalDraft = ExpenseInput(
            id = "expense-id",
            expenseDate = "2026-07-20",
            category = "food_purchase",
            supplierName = "入力中の店舗",
            amount = "1234",
            paymentMethod = "クレジット",
            memo = "保持するメモ"
        )
        state.draftExpenseInputState.value = originalDraft
        val capture = ReceiptCaptureResult("capture-old", "file:/pending/old.jpg", 10L)
        assertTrue(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, "OCR店舗", "2026-07-21", "1540"),
                expectedCaptureId = capture.captureId
            )
        )
        val appliedDraft = state.draftExpenseInputState.value

        assertEquals(capture, state.discardPendingExpenseCapture())
        assertNull(state.pendingExpenseCaptureState.value)
        assertNull(state.pendingCaptureFor("expense-id"))
        assertEquals(appliedDraft, state.draftExpenseInputState.value)
        assertEquals("food_purchase", state.draftExpenseInputState.value?.category)
        assertEquals("クレジット", state.draftExpenseInputState.value?.paymentMethod)
        assertEquals("保持するメモ", state.draftExpenseInputState.value?.memo)

        val restored = InputStateViewModel(handle)
        assertNull(restored.pendingExpenseCaptureState.value)
        assertNull(restored.pendingCaptureFor("expense-id"))
    }

    @Test
    fun ocrIsNotAppliedWithoutStableExpenseId() {
        val state = InputStateViewModel(SavedStateHandle())
        state.draftExpenseInputState.value = ExpenseInput(amount = "100")
        val capture = ReceiptCaptureResult("capture-no-owner", "file:/pending/capture-no-owner.jpg", 1L)

        assertFalse(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, "店舗", "2026-07-22", "100"),
                expectedCaptureId = capture.captureId
            )
        )
        assertNull(state.pendingExpenseCaptureState.value)
    }

    @Test
    fun ocrResultForDifferentCaptureIsRejectedWithoutChangingDraftOrOwnership() {
        val state = InputStateViewModel(SavedStateHandle())
        val original = ExpenseInput(
            id = "expense-id",
            expenseDate = "2026-07-20",
            category = "food_purchase",
            supplierName = "入力中の店舗",
            amount = "1234",
            paymentMethod = "クレジット",
            memo = "保持するメモ"
        )
        state.draftExpenseInputState.value = original
        val capture = ReceiptCaptureResult("stale-capture", "file:/pending/stale.jpg", 3L)

        assertFalse(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, "誤った店舗", "2026-07-21", "1540"),
                expectedCaptureId = "current-capture"
            )
        )

        assertEquals(original, state.draftExpenseInputState.value)
        assertNull(state.pendingExpenseCaptureState.value)
        assertNull(state.pendingCaptureFor(original.id))
    }

    @Test
    fun emptyOcrFieldsDoNotOverwriteExistingFormValues() {
        val state = InputStateViewModel(SavedStateHandle())
        val original = ExpenseInput(
            id = "expense-id",
            expenseDate = "2026-07-20",
            category = "food_purchase",
            supplierName = "入力中の店舗",
            amount = "1234",
            paymentMethod = "クレジット",
            memo = "保持するメモ"
        )
        state.draftExpenseInputState.value = original
        val capture = ReceiptCaptureResult("capture-empty", "file:/pending/empty.jpg", 4L)

        assertTrue(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, "", "", ""),
                expectedCaptureId = capture.captureId
            )
        )

        assertEquals(original, state.draftExpenseInputState.value)
        assertEquals(capture, state.pendingCaptureFor(original.id))
    }

    @Test
    fun enteredSupplierIsKeptWhileEmptyDateAndAmountAreFilled() {
        val state = InputStateViewModel(SavedStateHandle())
        state.draftExpenseInputState.value = ExpenseInput(
            id = "expense-id",
            expenseDate = "",
            category = "food_purchase",
            supplierName = "手入力店舗",
            amount = "",
            paymentMethod = "クレジット",
            memo = "保持メモ"
        )
        val capture = ReceiptCaptureResult("capture-supplier", "file:/pending/supplier.jpg", 5L)

        assertTrue(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, "OCR店舗", "2026-07-20", "1540"),
                expectedCaptureId = capture.captureId
            )
        )

        val applied = state.draftExpenseInputState.value!!
        assertEquals("手入力店舗", applied.supplierName)
        assertEquals("2026-07-20", applied.expenseDate)
        assertEquals("1540", applied.amount)
        assertEquals("food_purchase", applied.category)
        assertEquals("クレジット", applied.paymentMethod)
        assertEquals("保持メモ", applied.memo)
    }

    @Test
    fun enteredAmountIsKeptWhileEmptySupplierAndDateAreFilled() {
        val state = InputStateViewModel(SavedStateHandle())
        state.draftExpenseInputState.value = ExpenseInput(
            id = "expense-id",
            expenseDate = "",
            category = "food_purchase",
            supplierName = "",
            amount = "999",
            paymentMethod = "電子マネー",
            memo = "保持メモ"
        )
        val capture = ReceiptCaptureResult("capture-amount", "file:/pending/amount.jpg", 6L)

        assertTrue(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, "OCR店舗", "2026-07-20", "1540"),
                expectedCaptureId = capture.captureId
            )
        )

        val applied = state.draftExpenseInputState.value!!
        assertEquals("OCR店舗", applied.supplierName)
        assertEquals("2026-07-20", applied.expenseDate)
        assertEquals("999", applied.amount)
        assertEquals("電子マネー", applied.paymentMethod)
        assertEquals("保持メモ", applied.memo)
    }

    @Test
    fun zeroLikeDraftAmountIsReplacedByConfirmedOcrWithoutChangingOtherFields() {
        listOf("0", "000").forEachIndexed { index, zeroAmount ->
            val state = InputStateViewModel(SavedStateHandle())
            state.draftExpenseInputState.value = ExpenseInput(
                id = "expense-zero-$index",
                expenseDate = "",
                category = "food_purchase",
                supplierName = "",
                amount = zeroAmount,
                paymentMethod = "クレジット",
                memo = "保持メモ"
            )
            val capture = ReceiptCaptureResult(
                "capture-zero-$index",
                "file:/pending/zero-$index.jpg",
                7L + index
            )

            assertTrue(
                state.applyReceiptOcr(
                    ReceiptOcrApplyResult(capture, "ピアゴ", "2026-07-24", "1846"),
                    expectedCaptureId = capture.captureId
                )
            )

            val applied = state.draftExpenseInputState.value!!
            assertEquals("ピアゴ", applied.supplierName)
            assertEquals("2026-07-24", applied.expenseDate)
            assertEquals("1846", applied.amount)
            assertEquals("food_purchase", applied.category)
            assertEquals("クレジット", applied.paymentMethod)
            assertEquals("保持メモ", applied.memo)
            assertEquals(capture, state.pendingCaptureFor(applied.id))
        }
    }

    @Test
    fun newReportStartsWithDefaultOpeningCashButSavedReportValueIsPreserved() {
        assertEquals(DefaultOpeningCashYen.toString(), DailyReportInput().openingCash)

        val state = InputStateViewModel(SavedStateHandle())
        val saved = DailyReportInput(reportDate = "2026-07-24", openingCash = "54321")
        state.openReport(saved)

        assertEquals("54321", state.reportInputState.value.openingCash)
        assertEquals("54321", state.cleanReportInputState.value.openingCash)
    }

    @Test
    fun differingOcrValuesDoNotOverwriteFullyEnteredForm() {
        val state = InputStateViewModel(SavedStateHandle())
        val original = ExpenseInput(
            id = "expense-id",
            expenseDate = "2026-07-23",
            category = "food_purchase",
            supplierName = "手入力店舗",
            amount = "999",
            paymentMethod = "クレジット",
            memo = "保持メモ"
        )
        state.draftExpenseInputState.value = original
        val capture = ReceiptCaptureResult("capture-conflict", "file:/pending/conflict.jpg", 7L)

        assertTrue(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, "OCR店舗", "2026-07-20", "1540"),
                expectedCaptureId = capture.captureId
            )
        )

        assertEquals(original, state.draftExpenseInputState.value)
        assertEquals(capture, state.pendingCaptureFor(original.id))
    }

    @Test
    fun legacyPendingReferenceWithoutOwnerIsNotAppliedToCurrentDraft() {
        val handle = SavedStateHandle()
        val oldState = InputStateViewModel(handle)
        val capture = ReceiptCaptureResult("capture-legacy", "file:/pending/capture-legacy.jpg", 2L)
        oldState.draftExpenseInputState.value = ExpenseInput(id = "legacy-expense-id", amount = "100")
        oldState.pendingExpenseCaptureState.value = capture

        val restored = InputStateViewModel(handle)

        assertNull(restored.pendingCaptureFor("legacy-expense-id"))
        assertNull(restored.pendingCaptureFor("another-expense-id"))
    }

    @Test
    fun discardingReportChangesReturnsAndClearsCurrentDraftCapture() {
        val state = InputStateViewModel(SavedStateHandle())
        state.draftExpenseInputState.value = ExpenseInput(
            id = "owned-expense",
            expenseDate = "2026-07-20",
            category = "food_purchase",
            amount = "",
            paymentMethod = "現金"
        )
        val capture = ReceiptCaptureResult("owned-capture", "file:/pending/owned.jpg", 1L)
        assertTrue(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, "店舗", "2026-07-20", "100"),
                expectedCaptureId = capture.captureId
            )
        )

        assertEquals(capture, state.discardReportChanges())
        assertNull(state.pendingCaptureOwnedByCurrentDraft())
    }

    @Test
    fun discardingReportChangesDoesNotClearCaptureOwnedByAnotherDraft() {
        val handle = SavedStateHandle()
        val state = InputStateViewModel(handle)
        state.draftExpenseInputState.value = ExpenseInput(
            id = "owner-a",
            expenseDate = "2026-07-20",
            category = "food_purchase",
            amount = "",
            paymentMethod = "現金"
        )
        val capture = ReceiptCaptureResult("capture-a", "file:/pending/a.jpg", 1L)
        assertTrue(
            state.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, "店舗", "2026-07-20", "100"),
                expectedCaptureId = capture.captureId
            )
        )
        state.draftExpenseInputState.value = ExpenseInput(
            id = "owner-b",
            expenseDate = "2026-07-20",
            category = "food_purchase",
            amount = "100",
            paymentMethod = "現金"
        )

        assertNull(state.discardReportChanges())
        assertEquals(capture, state.pendingCaptureFor("owner-a"))
    }
}
