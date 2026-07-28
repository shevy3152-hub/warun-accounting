package com.warun.accounting.data.cancellation

import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import com.warun.accounting.util.PaymentMethodCash
import com.warun.accounting.util.PaymentMethodPrepaid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpenseCancellationPlanTest {
    @Test
    fun validPrepaidExpenseCreatesCancellationPlan() {
        val plan = plan()

        assertEquals(expense(), plan.expense)
        assertEquals(link(), plan.link)
        assertEquals(purchase(), plan.purchase)
        assertEquals(request(), plan.request)
    }

    @Test
    fun nonPrepaidExpenseIsStale() {
        assertFailure(ExpenseCancellationFailure.StaleState) {
            plan(expense = expense().copy(paymentMethod = PaymentMethodCash))
        }
    }

    @Test
    fun changedExpenseTimestampIsStale() {
        assertFailure(ExpenseCancellationFailure.StaleState) {
            plan(expense = expense().copy(updatedAt = 11L))
        }
    }

    @Test
    fun changedPurchaseLinkIsStale() {
        assertFailure(ExpenseCancellationFailure.StaleState) {
            plan(link = link().copy(purchaseTransactionId = "purchase-new"))
        }
        assertFailure(ExpenseCancellationFailure.StaleState) {
            plan(
                request = request().copy(expectedPurchaseDate = "2026-07-27")
            )
        }
    }

    @Test
    fun changedPrepaidAccountIsStale() {
        assertFailure(ExpenseCancellationFailure.StaleState) {
            plan(purchase = purchase().copy(accountId = PrepaidAccountId.AuPayPrepaid))
        }
    }

    @Test
    fun changedExpenseOrPurchaseAmountIsStale() {
        assertFailure(ExpenseCancellationFailure.StaleState) {
            plan(expense = expense().copy(amount = 501L))
        }
        assertFailure(ExpenseCancellationFailure.StaleState) {
            plan(purchase = purchase().copy(balanceDelta = -501L))
        }
    }

    @Test
    fun nonPurchaseTransactionIsRejected() {
        assertFailure(ExpenseCancellationFailure.PrepaidStateInconsistent) {
            plan(
                purchase = purchase().copy(
                    transactionType = PrepaidTransactionType.Adjustment
                )
            )
        }
    }

    @Test
    fun alreadyReversedPurchaseIsRejected() {
        assertFailure(ExpenseCancellationFailure.PrepaidStateInconsistent) {
            plan(existingReversal = reversal())
        }
    }

    @Test
    fun operationKeyMustUseCancellationUuidFormat() {
        assertFailure(ExpenseCancellationFailure.InvalidRequest) {
            plan(request = request().copy(operationKey = "invalid"))
        }
    }

    @Test
    fun fingerprintIsStableAndIncludesEveryExpectedValue() {
        val first = plan(request = request().copy(reason = "  取消  "))
        val second = plan(request = request().copy(reason = "取消"))

        assertEquals(first.requestFingerprint, second.requestFingerprint)
        assertNotEquals(
            first.requestFingerprint,
            plan(
                request = request().copy(
                    expectedExpenseUpdatedAt = 11L
                ),
                expense = expense().copy(updatedAt = 11L)
            ).requestFingerprint
        )
    }

    @Test
    fun reasonIsNormalizedAndBlankBecomesNull() {
        assertEquals("取消", plan(request = request().copy(reason = "  取消  ")).request.reason)
        assertNull(plan(request = request().copy(reason = " \t ")).request.reason)
    }

    @Test
    fun reversalUsesOriginalAccountAmountAndReferences() {
        val reversal = createExpenseCancellationReversal(
            plan = plan(request = request().copy(reason = "取消")),
            reversalId = "reversal-new",
            cancelledAt = 20L
        )

        assertEquals("reversal-new", reversal.id)
        assertEquals(PrepaidAccountId.Majica, reversal.accountId)
        assertEquals(500L, reversal.balanceDelta)
        assertEquals(PrepaidTransactionType.Reversal, reversal.transactionType)
        assertEquals("expense-1", reversal.expenseId)
        assertEquals("purchase-1", reversal.reversalOfTransactionId)
        assertEquals("2026-07-29", reversal.transactionDate)
        assertEquals("取消", reversal.memo)
        assertEquals(20L, reversal.createdAt)
    }

    private fun plan(
        request: ExpenseCancellationRequest = request(),
        expense: ExpenseRecord? = expense(),
        link: ExpensePrepaidLinkRecord? = link(),
        purchase: PrepaidTransactionRecord? = purchase(),
        existingReversal: PrepaidTransactionRecord? = null
    ) = planExpenseCancellation(
        request = request,
        expense = expense,
        link = link,
        purchase = purchase,
        existingReversal = existingReversal
    )

    private fun request() = ExpenseCancellationRequest(
        operationKey = "expense-cancel:123e4567-e89b-42d3-a456-426614174000",
        expenseId = "expense-1",
        expectedExpenseUpdatedAt = 10L,
        expectedOriginalPurchaseTransactionId = "purchase-1",
        expectedPrepaidAccountId = PrepaidAccountId.Majica,
        expectedAmount = 500L,
        expectedPurchaseDate = "2026-07-28",
        cancellationDate = "2026-07-29",
        reason = null
    )

    private fun expense() = ExpenseRecord(
        id = "expense-1",
        expenseDate = "2026-07-28",
        category = ExpenseCategory.OtherExpense,
        supplierName = "test supplier",
        amount = 500L,
        paymentMethod = PaymentMethodPrepaid,
        memo = "memo",
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1L,
        updatedAt = 10L
    )

    private fun link() = ExpensePrepaidLinkRecord(
        expenseId = "expense-1",
        purchaseTransactionId = "purchase-1",
        linkedAt = 2L,
        updatedAt = 2L
    )

    private fun purchase() = PrepaidTransactionRecord(
        id = "purchase-1",
        accountId = PrepaidAccountId.Majica,
        transactionDate = "2026-07-28",
        transactionType = PrepaidTransactionType.Purchase,
        balanceDelta = -500L,
        expenseId = "expense-1",
        chargeSource = null,
        reversalOfTransactionId = null,
        operationKey = "purchase-operation-1",
        memo = "",
        createdAt = 2L
    )

    private fun reversal() = PrepaidTransactionRecord(
        id = "reversal-old",
        accountId = PrepaidAccountId.Majica,
        transactionDate = "2026-07-29",
        transactionType = PrepaidTransactionType.Reversal,
        balanceDelta = 500L,
        expenseId = "expense-1",
        chargeSource = null,
        reversalOfTransactionId = "purchase-1",
        operationKey = "old-reversal-operation",
        memo = "",
        createdAt = 3L
    )

    private fun assertFailure(
        expected: ExpenseCancellationFailure,
        block: () -> Unit
    ) {
        val error = runCatching(block).exceptionOrNull()
        assertEquals(expected, (error as? ExpenseCancellationException)?.failure)
    }
}
