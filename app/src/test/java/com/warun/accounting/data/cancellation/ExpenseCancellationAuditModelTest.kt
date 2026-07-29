package com.warun.accounting.data.cancellation

import com.warun.accounting.data.local.ExpenseCancellationRecord
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseEvidenceRecord
import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.PrepaidAccountType
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import com.warun.accounting.util.PaymentMethodPrepaid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseCancellationAuditModelTest {
    @Test
    fun buildsCompleteAuditRelationAndKeepsEvidence() {
        val item = build().single()

        assertEquals("expense-1", item.expense.id)
        assertEquals("取消理由", item.cancellation.reason)
        assertEquals(PrepaidAccountId.Majica, item.prepaidAccount?.id)
        assertEquals("purchase-1", item.originalPurchase?.id)
        assertEquals("reversal-1", item.reversal?.id)
        assertEquals(listOf("evidence-1"), item.evidence.map { it.evidenceId })
        assertTrue(item.hasCompleteLedgerRelation)
    }

    @Test
    fun blankReasonRemainsAbsentForReadOnlyDisplay() {
        val item = build(
            cancellations = listOf(cancellation().copy(reason = null)),
            transactions = listOf(purchase(), reversal().copy(memo = ""))
        ).single()

        assertNull(item.cancellation.reason)
        assertTrue(item.hasCompleteLedgerRelation)
    }

    @Test
    fun missingLedgerReferenceDoesNotHideAuditExpense() {
        val item = build(transactions = listOf(purchase())).single()

        assertEquals("expense-1", item.expense.id)
        assertNull(item.reversal)
        assertFalse(item.hasCompleteLedgerRelation)
    }

    @Test
    fun mismatchedAmountOrAccountIsNeverMarkedAsComplete() {
        val wrongAmount = build(
            transactions = listOf(
                purchase().copy(balanceDelta = -499L),
                reversal()
            )
        ).single()
        val wrongAccount = build(
            transactions = listOf(
                purchase(),
                reversal().copy(accountId = PrepaidAccountId.AuPayPrepaid)
            )
        ).single()

        assertFalse(wrongAmount.hasCompleteLedgerRelation)
        assertFalse(wrongAccount.hasCompleteLedgerRelation)
    }

    @Test
    fun unrelatedEvidenceIsNotExposed() {
        val items = build(
            evidence = listOf(evidence(), evidence().copy(expenseId = "expense-other"))
        )

        assertEquals(listOf("evidence-1"), items.single().evidence.map { it.evidenceId })
    }

    private fun build(
        cancellations: List<ExpenseCancellationRecord> = listOf(cancellation()),
        transactions: List<PrepaidTransactionRecord> = listOf(purchase(), reversal()),
        evidence: List<ExpenseEvidenceRecord> = listOf(evidence())
    ) = buildExpenseCancellationAuditItems(
        expenses = listOf(expense()),
        cancellations = cancellations,
        links = listOf(link()),
        transactions = transactions,
        accounts = listOf(account()),
        evidence = evidence
    )

    private fun expense() = ExpenseRecord(
        id = "expense-1",
        expenseDate = "2026-07-29",
        category = ExpenseCategory.FoodPurchase,
        supplierName = "テスト店舗",
        amount = 500L,
        paymentMethod = PaymentMethodPrepaid,
        memo = "memo",
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1L,
        updatedAt = 2L
    )

    private fun cancellation() = ExpenseCancellationRecord(
        expenseId = "expense-1",
        operationKey = "expense-cancel:11111111-1111-1111-1111-111111111111",
        requestFingerprint = "fingerprint",
        originalPurchaseTransactionId = "purchase-1",
        reversalTransactionId = "reversal-1",
        cancellationDate = "2026-07-29",
        cancelledAt = 3L,
        reason = "取消理由"
    )

    private fun link() = ExpensePrepaidLinkRecord(
        expenseId = "expense-1",
        purchaseTransactionId = "purchase-1",
        linkedAt = 1L,
        updatedAt = 1L
    )

    private fun purchase() = PrepaidTransactionRecord(
        id = "purchase-1",
        accountId = PrepaidAccountId.Majica,
        transactionDate = "2026-07-29",
        transactionType = PrepaidTransactionType.Purchase,
        balanceDelta = -500L,
        expenseId = "expense-1",
        chargeSource = null,
        reversalOfTransactionId = null,
        operationKey = "purchase-operation",
        memo = "",
        createdAt = 1L
    )

    private fun reversal() = PrepaidTransactionRecord(
        id = "reversal-1",
        accountId = PrepaidAccountId.Majica,
        transactionDate = "2026-07-29",
        transactionType = PrepaidTransactionType.Reversal,
        balanceDelta = 500L,
        expenseId = "expense-1",
        chargeSource = null,
        reversalOfTransactionId = "purchase-1",
        operationKey = "expense-cancel:11111111-1111-1111-1111-111111111111:reversal",
        memo = "取消理由",
        createdAt = 3L
    )

    private fun account() = PrepaidAccountRecord(
        id = PrepaidAccountId.Majica,
        type = PrepaidAccountType.Majica,
        name = "majica",
        isActive = true,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun evidence() = ExpenseEvidenceRecord(
        expenseId = "expense-1",
        evidenceId = "evidence-1",
        captureId = "capture-1",
        storedUri = "stored.jpg",
        byteSize = 100L,
        sha256 = "sha",
        createdAt = 1L,
        storedAt = 2L
    )
}
