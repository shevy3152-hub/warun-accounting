package com.warun.accounting.data.prepaid

import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.PrepaidAccountType
import com.warun.accounting.data.local.PrepaidChargeSource
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType

enum class PrepaidValidationFailure {
    AccountNotFound,
    AccountInactive,
    UnknownAccountType,
    UnknownTransactionType,
    UnknownChargeSource,
    BlankIdentifier,
    ZeroBalanceDelta,
    InvalidBalanceDelta,
    ExpenseRequired,
    ExpenseForbidden,
    ChargeSourceRequired,
    ChargeSourceForbidden,
    ReversalTargetRequired,
    ReversalTargetForbidden,
    ReversalTargetNotFound,
    ReversalOfReversal,
    ReversalAccountMismatch,
    ReversalExpenseMismatch,
    ReversalDeltaMismatch,
    DuplicateOperationKey,
    DuplicateReversal,
    ArithmeticOverflow,
    InsufficientBalance,
    ExpenseNotFound,
    LinkTransactionNotFound,
    LinkRequiresPurchase,
    LinkAccountMismatch,
    LinkExpenseMismatch,
    DuplicateExpenseLink,
    DuplicatePurchaseLink,
    InvalidDate
}

class PrepaidValidationException(
    val failure: PrepaidValidationFailure
) : IllegalArgumentException(failure.name)

object PrepaidLedgerRules {
    fun calculateBalance(
        transactions: Iterable<PrepaidTransactionRecord>,
        accountId: String
    ): Long = transactions
        .asSequence()
        .filter { it.accountId == accountId }
        .fold(0L) { balance, transaction ->
            addExact(balance, transaction.balanceDelta)
        }

    fun validateTransaction(
        account: PrepaidAccountRecord?,
        transaction: PrepaidTransactionRecord,
        existingOperation: PrepaidTransactionRecord? = null,
        reversalTarget: PrepaidTransactionRecord? = null,
        existingReversal: PrepaidTransactionRecord? = null,
        currentBalance: Long? = null
    ) {
        if (account == null) {
            fail(PrepaidValidationFailure.AccountNotFound)
        }
        if (!account.isActive) {
            fail(PrepaidValidationFailure.AccountInactive)
        }
        if (account.type !in PrepaidAccountType.Supported) {
            fail(PrepaidValidationFailure.UnknownAccountType)
        }
        if (
            transaction.id.isBlank() ||
            transaction.accountId.isBlank() ||
            transaction.transactionDate.isBlank() ||
            transaction.operationKey.isBlank()
        ) {
            fail(PrepaidValidationFailure.BlankIdentifier)
        }
        if (transaction.accountId != account.id) {
            fail(PrepaidValidationFailure.AccountNotFound)
        }
        if (transaction.transactionType !in PrepaidTransactionType.Supported) {
            fail(PrepaidValidationFailure.UnknownTransactionType)
        }
        if (transaction.balanceDelta == 0L) {
            fail(PrepaidValidationFailure.ZeroBalanceDelta)
        }
        if (
            transaction.chargeSource != null &&
            transaction.chargeSource !in PrepaidChargeSource.Supported
        ) {
            fail(PrepaidValidationFailure.UnknownChargeSource)
        }
        if (existingOperation != null) {
            fail(PrepaidValidationFailure.DuplicateOperationKey)
        }

        when (transaction.transactionType) {
            PrepaidTransactionType.Charge -> validateCharge(account, transaction)
            PrepaidTransactionType.Purchase -> validatePurchase(transaction)
            PrepaidTransactionType.Adjustment -> validateOrdinaryTransaction(transaction)
            PrepaidTransactionType.Refund -> {
                if (transaction.balanceDelta < 0L) {
                    fail(PrepaidValidationFailure.InvalidBalanceDelta)
                }
                validateOrdinaryTransaction(transaction)
            }
            PrepaidTransactionType.Reversal -> validateReversal(
                transaction = transaction,
                target = reversalTarget,
                existingReversal = existingReversal
            )
        }
        currentBalance?.let { balance ->
            val resultingBalance = addExact(balance, transaction.balanceDelta)
            if (
                resultingBalance < 0L &&
                transaction.transactionType in setOf(
                    PrepaidTransactionType.Purchase,
                    PrepaidTransactionType.Adjustment,
                    PrepaidTransactionType.Reversal
                )
            ) {
                fail(PrepaidValidationFailure.InsufficientBalance)
            }
        }
    }

    fun validateLink(
        link: ExpensePrepaidLinkRecord,
        expenseExists: Boolean,
        purchaseTransaction: PrepaidTransactionRecord?,
        expectedAccountId: String,
        existingExpenseLink: ExpensePrepaidLinkRecord? = null,
        existingPurchaseLink: ExpensePrepaidLinkRecord? = null
    ) {
        if (link.expenseId.isBlank() || link.purchaseTransactionId.isBlank()) {
            fail(PrepaidValidationFailure.BlankIdentifier)
        }
        if (!expenseExists) {
            fail(PrepaidValidationFailure.ExpenseNotFound)
        }
        if (purchaseTransaction == null) {
            fail(PrepaidValidationFailure.LinkTransactionNotFound)
        }
        if (purchaseTransaction.transactionType != PrepaidTransactionType.Purchase) {
            fail(PrepaidValidationFailure.LinkRequiresPurchase)
        }
        if (purchaseTransaction.accountId != expectedAccountId) {
            fail(PrepaidValidationFailure.LinkAccountMismatch)
        }
        if (purchaseTransaction.expenseId != link.expenseId) {
            fail(PrepaidValidationFailure.LinkExpenseMismatch)
        }
        if (existingExpenseLink != null) {
            fail(PrepaidValidationFailure.DuplicateExpenseLink)
        }
        if (existingPurchaseLink != null) {
            fail(PrepaidValidationFailure.DuplicatePurchaseLink)
        }
    }

    private fun validateCharge(
        account: PrepaidAccountRecord,
        transaction: PrepaidTransactionRecord
    ) {
        if (transaction.balanceDelta < 0L) {
            fail(PrepaidValidationFailure.InvalidBalanceDelta)
        }
        if (transaction.expenseId != null) {
            fail(PrepaidValidationFailure.ExpenseForbidden)
        }
        if (transaction.reversalOfTransactionId != null) {
            fail(PrepaidValidationFailure.ReversalTargetForbidden)
        }
        val chargeSource = transaction.chargeSource
            ?: fail(PrepaidValidationFailure.ChargeSourceRequired)
        val allowedSources = when (account.type) {
            PrepaidAccountType.Majica -> setOf(PrepaidChargeSource.Cash)
            PrepaidAccountType.AuPayPrepaid -> PrepaidChargeSource.Supported
            else -> emptySet()
        }
        if (chargeSource !in allowedSources) {
            fail(PrepaidValidationFailure.UnknownChargeSource)
        }
    }

    private fun validatePurchase(transaction: PrepaidTransactionRecord) {
        if (transaction.balanceDelta > 0L) {
            fail(PrepaidValidationFailure.InvalidBalanceDelta)
        }
        if (transaction.expenseId.isNullOrBlank()) {
            fail(PrepaidValidationFailure.ExpenseRequired)
        }
        validateOrdinaryTransaction(transaction)
    }

    private fun validateOrdinaryTransaction(transaction: PrepaidTransactionRecord) {
        if (transaction.chargeSource != null) {
            fail(PrepaidValidationFailure.ChargeSourceForbidden)
        }
        if (transaction.reversalOfTransactionId != null) {
            fail(PrepaidValidationFailure.ReversalTargetForbidden)
        }
    }

    private fun validateReversal(
        transaction: PrepaidTransactionRecord,
        target: PrepaidTransactionRecord?,
        existingReversal: PrepaidTransactionRecord?
    ) {
        if (transaction.chargeSource != null) {
            fail(PrepaidValidationFailure.ChargeSourceForbidden)
        }
        val targetId = transaction.reversalOfTransactionId
            ?: fail(PrepaidValidationFailure.ReversalTargetRequired)
        if (target == null || target.id != targetId) {
            fail(PrepaidValidationFailure.ReversalTargetNotFound)
        }
        if (target.transactionType == PrepaidTransactionType.Reversal) {
            fail(PrepaidValidationFailure.ReversalOfReversal)
        }
        if (target.accountId != transaction.accountId) {
            fail(PrepaidValidationFailure.ReversalAccountMismatch)
        }
        if (target.expenseId != transaction.expenseId) {
            fail(PrepaidValidationFailure.ReversalExpenseMismatch)
        }
        if (existingReversal != null) {
            fail(PrepaidValidationFailure.DuplicateReversal)
        }
        val expectedDelta = try {
            Math.negateExact(target.balanceDelta)
        } catch (_: ArithmeticException) {
            fail(PrepaidValidationFailure.ArithmeticOverflow)
        }
        if (transaction.balanceDelta != expectedDelta) {
            fail(PrepaidValidationFailure.ReversalDeltaMismatch)
        }
    }

    private fun fail(failure: PrepaidValidationFailure): Nothing {
        throw PrepaidValidationException(failure)
    }

    private fun addExact(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        fail(PrepaidValidationFailure.ArithmeticOverflow)
    }
}
