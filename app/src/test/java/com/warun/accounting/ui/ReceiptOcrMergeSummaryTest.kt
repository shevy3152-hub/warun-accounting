package com.warun.accounting.ui

import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.ui.receipt.ReceiptOcrApplyResult
import com.warun.accounting.ui.receipt.ReceiptOcrMergeAction
import com.warun.accounting.ui.receipt.planReceiptOcrMerge
import com.warun.accounting.ui.viewmodel.ExpenseInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptOcrMergeSummaryTest {
    private val capture = ReceiptCaptureResult("capture", "file:/pending/capture.jpg", 1L)

    @Test
    fun summarySeparatesFilledKeptAndMatchingFields() {
        val current = ExpenseInput(
            id = "expense-id",
            supplierName = "手入力店舗",
            expenseDate = "",
            amount = "1540"
        )
        val result = ReceiptOcrApplyResult(
            capture = capture,
            supplierName = "OCR店舗",
            expenseDate = "2026-07-20",
            amount = "1540"
        )

        val summary = summarizeReceiptOcrMerge(current, result)

        assertEquals(listOf("支出日"), summary.filledFields)
        assertEquals(listOf("支払先"), summary.keptFields)
        assertEquals(listOf("金額"), summary.matchingFields)
        assertTrue(summary.toFeedbackText().contains("OCRで補完: 支出日"))
        assertTrue(summary.toFeedbackText().contains("現在入力を維持: 支払先"))
    }

    @Test
    fun blankOcrValuesAreReportedAsKeptWithoutChangingCurrentInput() {
        val current = ExpenseInput(
            id = "expense-id",
            supplierName = "手入力店舗",
            expenseDate = "2026-07-23",
            amount = "999"
        )
        val result = ReceiptOcrApplyResult(capture, "", "", "")

        val summary = summarizeReceiptOcrMerge(current, result)

        assertEquals(emptyList<String>(), summary.filledFields)
        assertEquals(listOf("支払先", "支出日", "金額"), summary.keptFields)
        assertEquals(emptyList<String>(), summary.matchingFields)
    }

    @Test
    fun displayedMergePlanAndAppliedExpenseUseTheSameDecisions() {
        val current = ExpenseInput(
            id = "expense-id",
            supplierName = "",
            expenseDate = "2026-07-23",
            amount = ""
        )
        val result = ReceiptOcrApplyResult(
            capture = capture,
            supplierName = "バロー",
            expenseDate = "2026-07-20",
            amount = "1540"
        )

        val plan = planReceiptOcrMerge(current, result)

        assertEquals("バロー", plan.mergedExpense.supplierName)
        assertEquals("2026-07-23", plan.mergedExpense.expenseDate)
        assertEquals("1540", plan.mergedExpense.amount)
        assertEquals(
            listOf(
                ReceiptOcrMergeAction.FillFromOcr,
                ReceiptOcrMergeAction.KeepCurrent,
                ReceiptOcrMergeAction.FillFromOcr
            ),
            plan.fields.map { it.action }
        )
        assertEquals(
            listOf(
                "支払先：OCR値を反映：バロー",
                "支出日：現在値を維持：2026-07-23（OCR候補：2026-07-20）",
                "金額：OCR値を反映：1540"
            ),
            plan.displayLines()
        )
        assertTrue(plan.displayLines()[1].contains("現在値を維持"))
        assertTrue(plan.displayLines()[1].contains("OCR候補"))
        assertTrue(plan.displayLines().none { it.contains("上書き") || it.contains("変更") })
    }
}
