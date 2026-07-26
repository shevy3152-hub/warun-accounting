package com.warun.accounting.data.prepaid

import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.PrepaidAccountType
import com.warun.accounting.data.local.PrepaidChargeSource
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class PrepaidLedgerRulesTest {
    @Test
    fun balanceUsesOnlyTheRequestedImmutableLedger() {
        val transactions = listOf(
            transaction(
                id = "charge",
                accountId = PrepaidAccountId.Majica,
                type = PrepaidTransactionType.Charge,
                delta = 10_000L,
                chargeSource = PrepaidChargeSource.Cash
            ),
            transaction(
                id = "purchase",
                accountId = PrepaidAccountId.Majica,
                type = PrepaidTransactionType.Purchase,
                delta = -1_846L,
                expenseId = "expense-1"
            ),
            transaction(
                id = "adjust-plus",
                accountId = PrepaidAccountId.Majica,
                type = PrepaidTransactionType.Adjustment,
                delta = 500L
            ),
            transaction(
                id = "adjust-minus",
                accountId = PrepaidAccountId.Majica,
                type = PrepaidTransactionType.Adjustment,
                delta = -500L
            ),
            transaction(
                id = "refund",
                accountId = PrepaidAccountId.Majica,
                type = PrepaidTransactionType.Refund,
                delta = 100L
            ),
            transaction(
                id = "other-account",
                accountId = PrepaidAccountId.AuPayPrepaid,
                type = PrepaidTransactionType.Charge,
                delta = 50_000L,
                chargeSource = PrepaidChargeSource.CreditCard
            )
        )

        assertEquals(
            8_254L,
            PrepaidLedgerRules.calculateBalance(transactions, PrepaidAccountId.Majica)
        )
        assertEquals(
            50_000L,
            PrepaidLedgerRules.calculateBalance(transactions, PrepaidAccountId.AuPayPrepaid)
        )
        assertEquals(0L, PrepaidLedgerRules.calculateBalance(emptyList(), "missing"))
    }

    @Test
    fun reversalOffsetsTheOriginalTransaction() {
        val original = transaction(
            id = "purchase",
            type = PrepaidTransactionType.Purchase,
            delta = -1_846L,
            expenseId = "expense-1"
        )
        val reversal = transaction(
            id = "reversal",
            type = PrepaidTransactionType.Reversal,
            delta = 1_846L,
            expenseId = "expense-1",
            reversalOf = original.id
        )

        PrepaidLedgerRules.validateTransaction(
            account = majica,
            transaction = reversal,
            reversalTarget = original
        )
        assertEquals(
            0L,
            PrepaidLedgerRules.calculateBalance(listOf(original, reversal), majica.id)
        )
    }

    @Test
    fun rejectsInvalidDeltaAndOverflow() {
        assertFailure(PrepaidValidationFailure.ZeroBalanceDelta) {
            validate(transaction(type = PrepaidTransactionType.Adjustment, delta = 0L))
        }
        assertFailure(PrepaidValidationFailure.InvalidBalanceDelta) {
            validate(
                transaction(
                    type = PrepaidTransactionType.Charge,
                    delta = -1L,
                    chargeSource = PrepaidChargeSource.Cash
                )
            )
        }
        assertFailure(PrepaidValidationFailure.InvalidBalanceDelta) {
            validate(
                transaction(
                    type = PrepaidTransactionType.Purchase,
                    delta = 1L,
                    expenseId = "expense-1"
                )
            )
        }
        assertFailure(PrepaidValidationFailure.InvalidBalanceDelta) {
            validate(transaction(type = PrepaidTransactionType.Refund, delta = -1L))
        }
        assertFailure(PrepaidValidationFailure.ArithmeticOverflow) {
            PrepaidLedgerRules.calculateBalance(
                listOf(
                    transaction(id = "max", delta = Long.MAX_VALUE),
                    transaction(id = "overflow", delta = 1L)
                ),
                majica.id
            )
        }
    }

    @Test
    fun rejectsMissingInactiveAndUnknownAccounts() {
        val candidate = transaction(delta = 1L)
        assertFailure(PrepaidValidationFailure.AccountNotFound) {
            PrepaidLedgerRules.validateTransaction(null, candidate)
        }
        assertFailure(PrepaidValidationFailure.AccountInactive) {
            PrepaidLedgerRules.validateTransaction(
                majica.copy(isActive = false),
                candidate
            )
        }
        assertFailure(PrepaidValidationFailure.UnknownAccountType) {
            PrepaidLedgerRules.validateTransaction(
                majica.copy(type = "UNKNOWN"),
                candidate
            )
        }
    }

    @Test
    fun rejectsUnknownTransactionTypeAndChargeSource() {
        assertFailure(PrepaidValidationFailure.UnknownTransactionType) {
            validate(transaction(type = "UNKNOWN", delta = 1L))
        }
        assertFailure(PrepaidValidationFailure.UnknownChargeSource) {
            validate(
                transaction(
                    type = PrepaidTransactionType.Charge,
                    delta = 1L,
                    chargeSource = "UNKNOWN"
                )
            )
        }
    }

    @Test
    fun purchaseRequiresExpenseAndChargeForbidsExpense() {
        assertFailure(PrepaidValidationFailure.ExpenseRequired) {
            validate(transaction(type = PrepaidTransactionType.Purchase, delta = -1L))
        }
        assertFailure(PrepaidValidationFailure.ExpenseForbidden) {
            validate(
                transaction(
                    type = PrepaidTransactionType.Charge,
                    delta = 1L,
                    expenseId = "expense-1",
                    chargeSource = PrepaidChargeSource.Cash
                )
            )
        }
    }

    @Test
    fun chargeSourcesAreRestrictedByAccountType() {
        assertFailure(PrepaidValidationFailure.ChargeSourceRequired) {
            validate(
                transaction(
                    type = PrepaidTransactionType.Charge,
                    delta = 1L
                )
            )
        }
        assertFailure(PrepaidValidationFailure.UnknownChargeSource) {
            validate(
                transaction(
                    type = PrepaidTransactionType.Charge,
                    delta = 1L,
                    chargeSource = PrepaidChargeSource.CreditCard
                )
            )
        }

        PrepaidChargeSource.Supported.forEach { source ->
            val transaction = transaction(
                accountId = auPay.id,
                type = PrepaidTransactionType.Charge,
                delta = 1L,
                chargeSource = source
            )
            PrepaidLedgerRules.validateTransaction(auPay, transaction)
        }
    }

    @Test
    fun rejectsDuplicateOperationAndInsufficientPurchaseBalance() {
        val purchase = transaction(
            type = PrepaidTransactionType.Purchase,
            delta = -101L,
            expenseId = "expense-1"
        )
        assertFailure(PrepaidValidationFailure.DuplicateOperationKey) {
            PrepaidLedgerRules.validateTransaction(
                account = majica,
                transaction = purchase,
                existingOperation = transaction(id = "existing")
            )
        }
        assertFailure(PrepaidValidationFailure.InsufficientBalance) {
            PrepaidLedgerRules.validateTransaction(
                account = majica,
                transaction = purchase,
                currentBalance = 100L
            )
        }
    }

    @Test
    fun rejectsInvalidAndDuplicateReversals() {
        val original = transaction(
            id = "purchase",
            type = PrepaidTransactionType.Purchase,
            delta = -100L,
            expenseId = "expense-1"
        )
        val reversal = transaction(
            id = "reversal",
            type = PrepaidTransactionType.Reversal,
            delta = 100L,
            expenseId = "expense-1",
            reversalOf = original.id
        )
        assertFailure(PrepaidValidationFailure.DuplicateReversal) {
            PrepaidLedgerRules.validateTransaction(
                majica,
                reversal,
                reversalTarget = original,
                existingReversal = transaction(id = "existing-reversal")
            )
        }
        assertFailure(PrepaidValidationFailure.ReversalDeltaMismatch) {
            PrepaidLedgerRules.validateTransaction(
                majica,
                reversal.copy(balanceDelta = -100L),
                reversalTarget = original
            )
        }
        assertFailure(PrepaidValidationFailure.ReversalTargetNotFound) {
            PrepaidLedgerRules.validateTransaction(
                majica,
                reversal,
                reversalTarget = null
            )
        }
        assertFailure(PrepaidValidationFailure.ReversalOfReversal) {
            PrepaidLedgerRules.validateTransaction(
                majica,
                reversal.copy(reversalOfTransactionId = "prior-reversal"),
                reversalTarget = reversal.copy(id = "prior-reversal")
            )
        }
        assertFailure(PrepaidValidationFailure.ArithmeticOverflow) {
            PrepaidLedgerRules.validateTransaction(
                majica,
                reversal.copy(
                    balanceDelta = Long.MAX_VALUE,
                    reversalOfTransactionId = "min"
                ),
                reversalTarget = original.copy(id = "min", balanceDelta = Long.MIN_VALUE)
            )
        }
    }

    @Test
    fun adjustmentAndReversalCannotMakeBalanceNegative() {
        assertFailure(PrepaidValidationFailure.InsufficientBalance) {
            PrepaidLedgerRules.validateTransaction(
                account = majica,
                transaction = transaction(
                    type = PrepaidTransactionType.Adjustment,
                    delta = -501L
                ),
                currentBalance = 500L
            )
        }
        val charge = transaction(
            id = "charge",
            type = PrepaidTransactionType.Charge,
            delta = 1_000L,
            chargeSource = PrepaidChargeSource.Cash
        )
        assertFailure(PrepaidValidationFailure.InsufficientBalance) {
            PrepaidLedgerRules.validateTransaction(
                account = majica,
                transaction = transaction(
                    id = "reversal",
                    type = PrepaidTransactionType.Reversal,
                    delta = -1_000L,
                    reversalOf = charge.id
                ),
                reversalTarget = charge,
                currentBalance = 700L
            )
        }
    }

    @Test
    fun currentBalanceOverflowIsRejectedBeforeInsert() {
        assertFailure(PrepaidValidationFailure.ArithmeticOverflow) {
            PrepaidLedgerRules.validateTransaction(
                account = majica,
                transaction = transaction(
                    type = PrepaidTransactionType.Charge,
                    delta = 1L,
                    chargeSource = PrepaidChargeSource.Cash
                ),
                currentBalance = Long.MAX_VALUE
            )
        }
    }

    @Test
    fun operationRetryMustMatchTheOriginalBusinessContent() {
        val original = transaction(
            id = "original",
            type = PrepaidTransactionType.Charge,
            delta = 10_000L,
            chargeSource = PrepaidChargeSource.Cash
        )
        val retry = original.copy(id = "retry", createdAt = 99L)

        assertEquals(true, samePrepaidBusinessOperation(original, retry))
        assertEquals(
            false,
            samePrepaidBusinessOperation(original, retry.copy(balanceDelta = 20_000L))
        )
    }

    @Test
    fun linksOnlyMatchingPurchaseOnce() {
        val purchase = transaction(
            id = "purchase",
            type = PrepaidTransactionType.Purchase,
            delta = -100L,
            expenseId = "expense-1"
        )
        val link = ExpensePrepaidLinkRecord(
            expenseId = "expense-1",
            purchaseTransactionId = purchase.id,
            linkedAt = 1L,
            updatedAt = 1L
        )

        PrepaidLedgerRules.validateLink(
            link = link,
            expenseExists = true,
            purchaseTransaction = purchase,
            expectedAccountId = majica.id
        )
        assertFailure(PrepaidValidationFailure.DuplicateExpenseLink) {
            PrepaidLedgerRules.validateLink(
                link,
                expenseExists = true,
                purchaseTransaction = purchase,
                expectedAccountId = majica.id,
                existingExpenseLink = link
            )
        }
        assertFailure(PrepaidValidationFailure.DuplicatePurchaseLink) {
            PrepaidLedgerRules.validateLink(
                link,
                expenseExists = true,
                purchaseTransaction = purchase,
                expectedAccountId = majica.id,
                existingPurchaseLink = link.copy(expenseId = "expense-2")
            )
        }
        assertFailure(PrepaidValidationFailure.LinkExpenseMismatch) {
            PrepaidLedgerRules.validateLink(
                link,
                expenseExists = true,
                purchaseTransaction = purchase.copy(expenseId = "expense-2"),
                expectedAccountId = majica.id
            )
        }
        assertFailure(PrepaidValidationFailure.LinkAccountMismatch) {
            PrepaidLedgerRules.validateLink(
                link,
                expenseExists = true,
                purchaseTransaction = purchase,
                expectedAccountId = auPay.id
            )
        }
        assertFailure(PrepaidValidationFailure.LinkRequiresPurchase) {
            PrepaidLedgerRules.validateLink(
                link,
                expenseExists = true,
                purchaseTransaction = purchase.copy(
                    transactionType = PrepaidTransactionType.Charge
                ),
                expectedAccountId = majica.id
            )
        }
        assertFailure(PrepaidValidationFailure.ExpenseNotFound) {
            PrepaidLedgerRules.validateLink(
                link,
                expenseExists = false,
                purchaseTransaction = purchase,
                expectedAccountId = majica.id
            )
        }
        assertFailure(PrepaidValidationFailure.LinkTransactionNotFound) {
            PrepaidLedgerRules.validateLink(
                link,
                expenseExists = true,
                purchaseTransaction = null,
                expectedAccountId = majica.id
            )
        }
    }

    private fun validate(transaction: PrepaidTransactionRecord) {
        PrepaidLedgerRules.validateTransaction(majica, transaction)
    }

    private fun assertFailure(
        expected: PrepaidValidationFailure,
        block: () -> Unit
    ) {
        val exception = runCatching(block).exceptionOrNull() as? PrepaidValidationException
            ?: throw AssertionError("Expected PrepaidValidationException($expected)")
        assertEquals(expected, exception.failure)
    }

    private fun transaction(
        id: String = "transaction",
        accountId: String = PrepaidAccountId.Majica,
        type: String = PrepaidTransactionType.Adjustment,
        delta: Long = 1L,
        expenseId: String? = null,
        chargeSource: String? = null,
        reversalOf: String? = null
    ) = PrepaidTransactionRecord(
        id = id,
        accountId = accountId,
        transactionDate = "2026-07-26",
        transactionType = type,
        balanceDelta = delta,
        expenseId = expenseId,
        chargeSource = chargeSource,
        reversalOfTransactionId = reversalOf,
        operationKey = "operation-$id",
        memo = "",
        createdAt = 1L
    )

    private companion object {
        val majica = PrepaidAccountRecord(
            id = PrepaidAccountId.Majica,
            type = PrepaidAccountType.Majica,
            name = "majica",
            isActive = true,
            createdAt = 0L,
            updatedAt = 0L
        )
        val auPay = PrepaidAccountRecord(
            id = PrepaidAccountId.AuPayPrepaid,
            type = PrepaidAccountType.AuPayPrepaid,
            name = "au PAY プリペイド",
            isActive = true,
            createdAt = 0L,
            updatedAt = 0L
        )
    }
}
