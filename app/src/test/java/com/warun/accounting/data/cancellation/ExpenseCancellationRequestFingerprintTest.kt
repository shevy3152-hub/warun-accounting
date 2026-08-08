package com.warun.accounting.data.cancellation

import com.warun.accounting.data.local.ExpenseCancellationRecord
import com.warun.accounting.util.PaymentMethodCash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpenseCancellationRequestFingerprintTest {
    @Test
    fun identicalLogicalRequestsHaveStableFingerprint() {
        val first = input(reason = "  仕入取消  ")
        val second = input(reason = "仕入取消")

        assertEquals(
            ExpenseCancellationRequestFingerprint.create(first),
            ExpenseCancellationRequestFingerprint.create(second)
        )
        assertEquals(
            ExpenseCancellationRequestFingerprint.create(first),
            ExpenseCancellationRequestFingerprint.create(first)
        )
    }

    @Test
    fun everyBusinessFieldChangesFingerprint() {
        val original = input(reason = "取消")
        val fingerprint = ExpenseCancellationRequestFingerprint.create(original)
        val changes = listOf(
            original.copy(expenseId = "expense-2"),
            original.copy(expectedExpenseUpdatedAt = 43L),
            original.copy(originalPurchaseTransactionId = "purchase-2"),
            original.copy(prepaidAccountId = "prepaid-au-pay"),
            original.copy(amount = 1_541L),
            original.copy(purchaseDate = "2026-07-29"),
            original.copy(cancellationDate = "2026-07-30"),
            original.copy(reason = "別理由")
        )

        changes.forEach { changed ->
            assertNotEquals(
                fingerprint,
                ExpenseCancellationRequestFingerprint.create(changed)
            )
        }
    }

    @Test
    fun nullAndBlankReasonNormalizeToNull() {
        assertNull(ExpenseCancellationRules.normalizeReason(null))
        assertNull(ExpenseCancellationRules.normalizeReason(""))
        assertNull(ExpenseCancellationRules.normalizeReason(" \t\n "))
        assertEquals(
            ExpenseCancellationRequestFingerprint.create(input(reason = null)),
            ExpenseCancellationRequestFingerprint.create(input(reason = "  "))
        )
    }

    @Test
    fun operationKeyMustUseExpenseCancelUuidFormat() {
        ExpenseCancellationRules.validateOperationKey(
            "expense-cancel:123e4567-e89b-42d3-a456-426614174000"
        )

        listOf(
            "",
            "123e4567-e89b-42d3-a456-426614174000",
            "expense-edit:123e4567-e89b-42d3-a456-426614174000",
            "expense-cancel:not-a-uuid",
            "expense-cancel:123E4567-E89B-42D3-A456-426614174000"
        ).forEach { invalid ->
            assertFailure(ExpenseCancellationValidationFailure.InvalidOperationKey) {
                ExpenseCancellationRules.validateOperationKey(invalid)
            }
        }
    }

    @Test
    fun recordRejectsSamePurchaseAndReversalAndUnnormalizedReason() {
        val record = record()
        ExpenseCancellationRules.validateRecord(record)

        assertFailure(ExpenseCancellationValidationFailure.SamePurchaseAndReversal) {
            ExpenseCancellationRules.validateRecord(
                record.copy(reversalTransactionId = record.originalPurchaseTransactionId)
            )
        }
        assertFailure(ExpenseCancellationValidationFailure.UnnormalizedReason) {
            ExpenseCancellationRules.validateRecord(record.copy(reason = " 取消 "))
        }
    }

    @Test
    fun invalidAmountDateTimestampAndIdentifiersAreRejected() {
        listOf(
            input(amount = 0L) to ExpenseCancellationValidationFailure.InvalidAmount,
            input(amount = -1L) to ExpenseCancellationValidationFailure.InvalidAmount,
            input(expenseId = " ") to ExpenseCancellationValidationFailure.InvalidIdentifier,
            input(originalPurchaseTransactionId = "") to
                ExpenseCancellationValidationFailure.InvalidIdentifier,
            input(prepaidAccountId = "\t") to
                ExpenseCancellationValidationFailure.InvalidIdentifier,
            input(expectedExpenseUpdatedAt = -1L) to
                ExpenseCancellationValidationFailure.InvalidTimestamp,
            input(purchaseDate = "2026-02-30") to
                ExpenseCancellationValidationFailure.InvalidDate,
            input(cancellationDate = "not-a-date") to
                ExpenseCancellationValidationFailure.InvalidDate
        ).forEach { (invalid, expectedFailure) ->
            assertFailure(expectedFailure) {
                ExpenseCancellationRequestFingerprint.create(invalid)
            }
        }
    }

    @Test
    fun reasonLengthUsesTheSameCanonicalLimitAsTheViewModel() {
        ExpenseCancellationRequestFingerprint.create(
            input(reason = "あ".repeat(ExpenseCancellationRules.MaxReasonLength))
        )

        assertFailure(ExpenseCancellationValidationFailure.ReasonTooLong) {
            ExpenseCancellationRequestFingerprint.create(
                input(reason = "あ".repeat(ExpenseCancellationRules.MaxReasonLength + 1))
            )
        }
    }

    @Test
    fun nonPrepaidFingerprintIsStableAndIncludesEveryBusinessField() {
        val original = nonPrepaidInput(reason = "  reason  ")
        val fingerprint = ExpenseCancellationRequestFingerprint.createNonPrepaid(original)

        assertEquals(
            fingerprint,
            ExpenseCancellationRequestFingerprint.createNonPrepaid(
                nonPrepaidInput(reason = "reason")
            )
        )
        listOf(
            original.copy(expenseId = "expense-2"),
            original.copy(expectedExpenseUpdatedAt = 43L),
            original.copy(paymentMethod = "クレジット"),
            original.copy(amount = 1_541L),
            original.copy(expenseDate = "2026-07-29"),
            original.copy(cancellationDate = "2026-07-30"),
            original.copy(reason = "different")
        ).forEach { changed ->
            assertNotEquals(
                fingerprint,
                ExpenseCancellationRequestFingerprint.createNonPrepaid(changed)
            )
        }
    }

    @Test
    fun cancellationRecordAllowsEitherBothLedgerIdsOrNeither() {
        ExpenseCancellationRules.validateRecord(
            record().copy(
                originalPurchaseTransactionId = null,
                reversalTransactionId = null
            )
        )

        listOf(
            record().copy(originalPurchaseTransactionId = null),
            record().copy(reversalTransactionId = null)
        ).forEach { invalid ->
            assertFailure(ExpenseCancellationValidationFailure.InvalidIdentifier) {
                ExpenseCancellationRules.validateRecord(invalid)
            }
        }
    }

    private fun input(
        expenseId: String = "expense-1",
        expectedExpenseUpdatedAt: Long = 42L,
        originalPurchaseTransactionId: String = "purchase-1",
        prepaidAccountId: String = "prepaid-majica",
        amount: Long = 1_540L,
        purchaseDate: String = "2026-07-28",
        cancellationDate: String = "2026-07-29",
        reason: String? = null
    ) = ExpenseCancellationRequestFingerprintInput(
        expenseId = expenseId,
        expectedExpenseUpdatedAt = expectedExpenseUpdatedAt,
        originalPurchaseTransactionId = originalPurchaseTransactionId,
        prepaidAccountId = prepaidAccountId,
        amount = amount,
        purchaseDate = purchaseDate,
        cancellationDate = cancellationDate,
        reason = reason
    )

    private fun nonPrepaidInput(
        expenseId: String = "expense-1",
        expectedExpenseUpdatedAt: Long = 42L,
        paymentMethod: String = PaymentMethodCash,
        amount: Long = 1_540L,
        expenseDate: String = "2026-07-28",
        cancellationDate: String = "2026-07-29",
        reason: String? = null
    ) = NonPrepaidExpenseCancellationRequestFingerprintInput(
        expenseId = expenseId,
        expectedExpenseUpdatedAt = expectedExpenseUpdatedAt,
        paymentMethod = paymentMethod,
        amount = amount,
        expenseDate = expenseDate,
        cancellationDate = cancellationDate,
        reason = reason
    )

    private fun record() = ExpenseCancellationRecord(
        expenseId = "expense-1",
        operationKey = "expense-cancel:123e4567-e89b-42d3-a456-426614174000",
        requestFingerprint = "a".repeat(64),
        originalPurchaseTransactionId = "purchase-1",
        reversalTransactionId = "reversal-1",
        cancellationDate = "2026-07-29",
        cancelledAt = 42L,
        reason = "取消"
    )

    private fun assertFailure(
        expected: ExpenseCancellationValidationFailure,
        block: () -> Unit
    ) {
        val error = runCatching(block).exceptionOrNull()
        assertEquals(expected, (error as? ExpenseCancellationValidationException)?.failure)
    }
}
