package com.warun.accounting.ui

import com.warun.accounting.data.local.ExpenseEvidenceRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class RegisteredReceiptSelectionTest {
    @Test
    fun reportDetailReceivesOnlyEvidenceLinkedToExpensesForThatDate() {
        val firstExpense = expense("expense-1", "2026-07-24")
        val secondExpense = expense("expense-2", "2026-07-24")
        val otherDateExpense = expense("expense-other", "2026-07-25")
        val allEvidence = listOf(
            evidence("evidence-1", firstExpense.id),
            evidence("evidence-2", secondExpense.id),
            evidence("evidence-other", otherDateExpense.id)
        )

        val selected = evidenceForExpenses(
            expenses = listOf(firstExpense, secondExpense),
            evidence = allEvidence
        )

        assertEquals(listOf("evidence-1", "evidence-2"), selected.map { it.evidenceId })
    }

    private fun expense(id: String, date: String) = ExpenseRecord(
        id = id,
        expenseDate = date,
        category = "food_purchase",
        supplierName = "テスト商店",
        amount = 1_000,
        paymentMethod = "現金",
        memo = null,
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun evidence(id: String, expenseId: String) = ExpenseEvidenceRecord(
        expenseId = expenseId,
        evidenceId = id,
        captureId = id,
        storedUri = "file:/stored/$id.jpg",
        byteSize = 10L,
        sha256 = id.padEnd(64, '0'),
        createdAt = 1L,
        storedAt = 2L
    )
}
