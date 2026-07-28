package com.warun.accounting.data.edit

import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.ExpenseEvidenceLinkRecord
import com.warun.accounting.data.local.ExpenseCancellationDao
import com.warun.accounting.data.local.ExpensePrepaidLinkDao
import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.PrepaidAccountDao
import com.warun.accounting.data.local.PrepaidTransactionDao
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import com.warun.accounting.data.local.WarunDao
import com.warun.accounting.data.prepaid.PrepaidLedgerRules
import com.warun.accounting.data.prepaid.PrepaidValidationException
import com.warun.accounting.data.prepaid.PrepaidValidationFailure
import com.warun.accounting.util.PaymentMethodPrepaid
import com.warun.accounting.util.isSupportedPaymentMethod
import com.warun.accounting.util.normalizePaymentMethod
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject

data class SavedExpenseEditRequest(
    val operationKey: String,
    val expense: ExpenseRecord,
    val prepaidAccountId: String?,
    val requestedAt: Long,
    val newEvidence: EvidenceRecord? = null,
    val newEvidenceLink: ExpenseEvidenceLinkRecord? = null
)

data class SavedExpenseEditResult(
    val operation: com.warun.accounting.data.local.ExpenseEditOperationRecord,
    val wasAlreadyApplied: Boolean
)

enum class SavedExpenseEditFailure {
    ExpenseNotFound,
    InvalidRequest,
    ExistingPrepaidStateInconsistent,
    EvidenceContentMismatch
}

internal enum class ExpensePrepaidLinkTransition {
    None,
    Keep,
    Create,
    Replace,
    Remove
}

internal data class ExpensePrepaidEditPlan(
    val createReversal: Boolean,
    val createPurchase: Boolean,
    val linkTransition: ExpensePrepaidLinkTransition
)

internal fun planExpensePrepaidEdit(
    currentAccountId: String?,
    currentAmount: Long?,
    requestedAccountId: String?,
    requestedAmount: Long,
    requestedPrepaid: Boolean
): ExpensePrepaidEditPlan {
    val currentlyPrepaid = currentAccountId != null
    return when {
        !currentlyPrepaid && !requestedPrepaid ->
            ExpensePrepaidEditPlan(false, false, ExpensePrepaidLinkTransition.None)
        !currentlyPrepaid ->
            ExpensePrepaidEditPlan(false, true, ExpensePrepaidLinkTransition.Create)
        !requestedPrepaid ->
            ExpensePrepaidEditPlan(true, false, ExpensePrepaidLinkTransition.Remove)
        currentAccountId == requestedAccountId && currentAmount == requestedAmount ->
            ExpensePrepaidEditPlan(false, false, ExpensePrepaidLinkTransition.Keep)
        else ->
            ExpensePrepaidEditPlan(true, true, ExpensePrepaidLinkTransition.Replace)
    }
}

class SavedExpenseEditException(
    val failure: SavedExpenseEditFailure
) : IllegalStateException(failure.name)

class CancelledExpenseEditException(
    val expenseId: String
) : IllegalStateException("ExpenseCancelled")

class ExpenseEditRepository @Inject constructor(
    private val operationExecutor: ExpenseEditOperationExecutor,
    private val warunDao: WarunDao,
    private val accountDao: PrepaidAccountDao,
    private val transactionDao: PrepaidTransactionDao,
    private val prepaidLinkDao: ExpensePrepaidLinkDao,
    private val cancellationDao: ExpenseCancellationDao
) {
    suspend fun editExpense(request: SavedExpenseEditRequest): SavedExpenseEditResult {
        val normalized = request.normalized()
        val execution = operationExecutor.execute(
            request = ExpenseEditOperationRequest(
                operationKey = normalized.operationKey,
                fingerprintInput = normalized.fingerprintInput(),
                createdAt = normalized.requestedAt
            ),
            precondition = {
                if (cancellationDao.existsByExpenseId(normalized.expense.id)) {
                    throw CancelledExpenseEditException(normalized.expense.id)
                }
            }
        ) {
            applyEdit(normalized)
            ExpenseEditOperationCompletion(
                paymentMethod = normalized.expense.paymentMethod.orEmpty(),
                prepaidAccountId = normalized.prepaidAccountId,
                amount = normalized.expense.amount,
                completedAt = normalized.requestedAt
            )
        }
        return SavedExpenseEditResult(
            operation = execution.operation,
            wasAlreadyApplied = execution.wasAlreadyCompleted
        )
    }

    private suspend fun applyEdit(request: SavedExpenseEditRequest) {
        val currentExpense = warunDao.getExpenseRecord(request.expense.id)
            ?: fail(SavedExpenseEditFailure.ExpenseNotFound)
        val currentPrepaid = loadCurrentPrepaidState(currentExpense)
        val wantsPrepaid =
            normalizePaymentMethod(request.expense.paymentMethod) == PaymentMethodPrepaid
        val prepaidPlan = planExpensePrepaidEdit(
            currentAccountId = currentPrepaid?.purchase?.accountId,
            currentAmount = currentExpense.amount.takeIf { currentPrepaid != null },
            requestedAccountId = request.prepaidAccountId,
            requestedAmount = request.expense.amount,
            requestedPrepaid = wantsPrepaid
        )

        var newPurchase: PrepaidTransactionRecord? = null
        if (prepaidPlan.createReversal) {
            insertReversal(
                operationKey = request.operationKey,
                currentExpense = currentExpense,
                purchase = requireNotNull(currentPrepaid).purchase,
                createdAt = request.requestedAt
            )
        }
        if (prepaidPlan.createPurchase) {
            newPurchase = insertPurchase(
                operationKey = request.operationKey,
                expense = request.expense,
                accountId = requireNotNull(request.prepaidAccountId),
                createdAt = request.requestedAt
            )
        }

        warunDao.insertExpenseRecord(
            request.expense.copy(
                createdAt = currentExpense.createdAt,
                updatedAt = request.requestedAt
            )
        )

        if (
            prepaidPlan.linkTransition in setOf(
                ExpensePrepaidLinkTransition.Replace,
                ExpensePrepaidLinkTransition.Remove
            )
        ) {
            if (prepaidLinkDao.deleteByExpenseId(currentExpense.id) != 1) {
                fail(SavedExpenseEditFailure.ExistingPrepaidStateInconsistent)
            }
        }
        if (
            prepaidPlan.linkTransition in setOf(
                ExpensePrepaidLinkTransition.Create,
                ExpensePrepaidLinkTransition.Replace
            )
        ) {
            insertPrepaidLink(
                expenseId = currentExpense.id,
                purchase = requireNotNull(newPurchase),
                accountId = requireNotNull(request.prepaidAccountId),
                changedAt = request.requestedAt
            )
        }

        val evidence = request.newEvidence
        val evidenceLink = request.newEvidenceLink
        if (evidence != null && evidenceLink != null) {
            warunDao.addEvidenceToExpense(
                expenseId = currentExpense.id,
                evidence = evidence,
                link = evidenceLink
            )
        }
    }

    private suspend fun loadCurrentPrepaidState(
        expense: ExpenseRecord
    ): CurrentPrepaidState? {
        val isPrepaid =
            normalizePaymentMethod(expense.paymentMethod) == PaymentMethodPrepaid
        val link = prepaidLinkDao.getByExpenseId(expense.id)
        if (!isPrepaid) {
            if (link != null) fail(SavedExpenseEditFailure.ExistingPrepaidStateInconsistent)
            return null
        }
        link ?: fail(SavedExpenseEditFailure.ExistingPrepaidStateInconsistent)
        val purchase = transactionDao.getById(link.purchaseTransactionId)
            ?: fail(SavedExpenseEditFailure.ExistingPrepaidStateInconsistent)
        val expectedDelta = negateExact(expense.amount)
        if (
            purchase.transactionType != PrepaidTransactionType.Purchase ||
            purchase.expenseId != expense.id ||
            purchase.balanceDelta != expectedDelta ||
            transactionDao.getByReversalOfTransactionId(purchase.id) != null
        ) {
            fail(SavedExpenseEditFailure.ExistingPrepaidStateInconsistent)
        }
        return CurrentPrepaidState(link, purchase)
    }

    private suspend fun insertReversal(
        operationKey: String,
        currentExpense: ExpenseRecord,
        purchase: PrepaidTransactionRecord,
        createdAt: Long
    ) {
        val reversal = PrepaidTransactionRecord(
            id = UUID.randomUUID().toString(),
            accountId = purchase.accountId,
            transactionDate = purchase.transactionDate,
            transactionType = PrepaidTransactionType.Reversal,
            balanceDelta = negateExact(purchase.balanceDelta),
            expenseId = currentExpense.id,
            chargeSource = null,
            reversalOfTransactionId = purchase.id,
            operationKey = derivedOperationKey(operationKey, "reversal"),
            memo = "expense edit reversal",
            createdAt = createdAt
        )
        PrepaidLedgerRules.validateTransaction(
            account = accountDao.getById(purchase.accountId),
            transaction = reversal,
            existingOperation = transactionDao.getByOperationKey(reversal.operationKey),
            reversalTarget = purchase,
            existingReversal = transactionDao.getByReversalOfTransactionId(purchase.id),
            currentBalance = transactionDao.getBalance(purchase.accountId)
        )
        transactionDao.insert(reversal)
    }

    private suspend fun insertPurchase(
        operationKey: String,
        expense: ExpenseRecord,
        accountId: String,
        createdAt: Long
    ): PrepaidTransactionRecord {
        val purchase = PrepaidTransactionRecord(
            id = UUID.randomUUID().toString(),
            accountId = accountId,
            transactionDate = expense.expenseDate,
            transactionType = PrepaidTransactionType.Purchase,
            balanceDelta = negateExact(expense.amount),
            expenseId = expense.id,
            chargeSource = null,
            reversalOfTransactionId = null,
            operationKey = derivedOperationKey(operationKey, "purchase"),
            memo = expense.memo.orEmpty(),
            createdAt = createdAt
        )
        PrepaidLedgerRules.validateTransaction(
            account = accountDao.getById(accountId),
            transaction = purchase,
            existingOperation = transactionDao.getByOperationKey(purchase.operationKey),
            currentBalance = transactionDao.getBalance(accountId)
        )
        transactionDao.insert(purchase)
        return purchase
    }

    private suspend fun insertPrepaidLink(
        expenseId: String,
        purchase: PrepaidTransactionRecord,
        accountId: String,
        changedAt: Long
    ) {
        val link = ExpensePrepaidLinkRecord(
            expenseId = expenseId,
            purchaseTransactionId = purchase.id,
            linkedAt = changedAt,
            updatedAt = changedAt
        )
        PrepaidLedgerRules.validateLink(
            link = link,
            expenseExists = warunDao.getExpenseRecord(expenseId) != null,
            purchaseTransaction = purchase,
            expectedAccountId = accountId,
            existingExpenseLink = prepaidLinkDao.getByExpenseId(expenseId),
            existingPurchaseLink = prepaidLinkDao.getByPurchaseTransactionId(purchase.id)
        )
        prepaidLinkDao.insert(link)
    }

    private fun SavedExpenseEditRequest.normalized(): SavedExpenseEditRequest {
        val paymentMethod = normalizePaymentMethod(expense.paymentMethod)
        if (
            operationKey.isBlank() ||
            operationKey != operationKey.trim() ||
            expense.id.isBlank() ||
            expense.amount <= 0L ||
            !isSupportedPaymentMethod(paymentMethod) ||
            requestedAt < 0L
        ) {
            fail(SavedExpenseEditFailure.InvalidRequest)
        }
        try {
            LocalDate.parse(expense.expenseDate)
        } catch (_: DateTimeParseException) {
            fail(SavedExpenseEditFailure.InvalidRequest)
        }
        val accountId = if (paymentMethod == PaymentMethodPrepaid) {
            prepaidAccountId?.trim()?.takeIf { it.isNotBlank() }
                ?: fail(SavedExpenseEditFailure.InvalidRequest)
        } else {
            null
        }
        if ((newEvidence == null) != (newEvidenceLink == null)) {
            fail(SavedExpenseEditFailure.EvidenceContentMismatch)
        }
        newEvidenceLink?.let { link ->
            if (link.expenseId != expense.id || link.evidenceId != newEvidence?.id) {
                fail(SavedExpenseEditFailure.EvidenceContentMismatch)
            }
        }
        return copy(
            expense = expense.copy(paymentMethod = paymentMethod),
            prepaidAccountId = accountId
        )
    }

    private fun SavedExpenseEditRequest.fingerprintInput() =
        ExpenseEditRequestFingerprintInput(
            expenseId = expense.id,
            paymentMethod = expense.paymentMethod.orEmpty(),
            prepaidAccountId = prepaidAccountId,
            amount = expense.amount,
            date = expense.expenseDate,
            category = expense.category,
            supplier = expense.supplierName,
            memo = expense.memo,
            receiptId = expense.receiptId,
            sourceType = expense.sourceType,
            evidenceTokens = newEvidence?.let { evidence ->
                listOf(
                    ExpenseEditEvidenceToken(
                        evidenceId = evidence.id,
                        captureId = evidence.captureId,
                        contentSha256 = evidence.sha256
                    )
                )
            }.orEmpty()
        )

    private fun derivedOperationKey(operationKey: String, suffix: String): String =
        "expense-edit:$operationKey:$suffix"

    private fun negateExact(value: Long): Long = try {
        Math.negateExact(value)
    } catch (_: ArithmeticException) {
        throw PrepaidValidationException(PrepaidValidationFailure.ArithmeticOverflow)
    }

    private fun fail(failure: SavedExpenseEditFailure): Nothing {
        throw SavedExpenseEditException(failure)
    }

    private data class CurrentPrepaidState(
        val link: ExpensePrepaidLinkRecord,
        val purchase: PrepaidTransactionRecord
    )
}
