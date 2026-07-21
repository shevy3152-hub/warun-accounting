package com.warun.accounting.ui

import com.warun.accounting.ui.viewmodel.ExpenseInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExpenseSaveDecisionTest {
    @Test
    fun matchingExpenseDateAllowsExistingBatchSave() {
        val draft = expense("2026-07-21")

        val decision = reportExpenseSaveDecision("2026-07-21", draft, expenseFormDirty = true)

        assertTrue(decision is ReportExpenseSaveDecision.Allowed)
        assertSame(draft, (decision as ReportExpenseSaveDecision.Allowed).expenseToSave)
    }

    @Test
    fun mismatchedUnsavedExpenseBlocksBatchSave() {
        val draft = expense("2026-07-20")

        val decision = reportExpenseSaveDecision("2026-07-21", draft, expenseFormDirty = true)

        assertTrue(decision is ReportExpenseSaveDecision.BlockedDateMismatch)
    }

    @Test
    fun blockedDecisionRetainsOriginalDraftWithoutChangingDates() {
        val reportDate = "2026-07-21"
        val draft = expense("2026-07-20")

        val decision = reportExpenseSaveDecision(reportDate, draft, expenseFormDirty = true)
            as ReportExpenseSaveDecision.BlockedDateMismatch

        assertSame(draft, decision.draft)
        assertEquals("2026-07-20", decision.draft.expenseDate)
        assertEquals("2026-07-21", decision.reportDate)
    }

    @Test
    fun individualSaveCompletionClearsWarningAndAllowsReportSave() {
        val decision = reportExpenseSaveDecision(
            reportDate = "2026-07-21",
            draftExpense = null,
            expenseFormDirty = false
        )

        assertTrue(decision is ReportExpenseSaveDecision.Allowed)
        assertNull((decision as ReportExpenseSaveDecision.Allowed).expenseToSave)
    }

    @Test
    fun cleanDraftDoesNotBlockExistingNavigationOrReportSave() {
        val draft = expense("2026-07-20")

        val decision = reportExpenseSaveDecision("2026-07-21", draft, expenseFormDirty = false)

        assertTrue(decision is ReportExpenseSaveDecision.Allowed)
        assertNull((decision as ReportExpenseSaveDecision.Allowed).expenseToSave)
    }

    private fun expense(date: String) = ExpenseInput(
        id = "expense-id",
        expenseDate = date,
        category = "food_purchase",
        supplierName = "バロー",
        amount = "1540",
        paymentMethod = "クレジット",
        memo = "Phase3B入力保持"
    )
}
