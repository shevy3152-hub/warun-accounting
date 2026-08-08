package com.warun.accounting.data.cancellation

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.warun.accounting.data.local.ExpenseCancellationDao
import com.warun.accounting.data.local.ExpenseCancellationRecord
import com.warun.accounting.data.local.ExpensePrepaidLinkDao
import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.PrepaidAccountDao
import com.warun.accounting.data.local.PrepaidTransactionDao
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import com.warun.accounting.data.local.WarunDao
import com.warun.accounting.data.local.WarunDatabase
import com.warun.accounting.data.prepaid.PrepaidLedgerRules
import com.warun.accounting.data.prepaid.PrepaidValidationException
import com.warun.accounting.util.isSupportedPaymentMethod
import com.warun.accounting.util.PaymentMethodPrepaid
import com.warun.accounting.util.normalizePaymentMethod
import java.util.UUID
import javax.inject.Inject

data class ExpenseCancellationRequest(
    val operationKey: String,
    val expenseId: String,
    val expectedExpenseUpdatedAt: Long,
    val expectedOriginalPurchaseTransactionId: String?,
    val expectedPrepaidAccountId: String?,
    val expectedAmount: Long,
    val expectedPurchaseDate: String,
    val cancellationDate: String,
    val reason: String?,
    val expectedPaymentMethod: String = PaymentMethodPrepaid
)

data class ExpenseCancellationResult(
    val expenseId: String,
    val originalPurchaseTransactionId: String?,
    val reversalTransactionId: String?,
    val prepaidAccountId: String?,
    val amount: Long,
    val cancellationDate: String,
    val cancelledAt: Long,
    val reason: String?,
    val idempotentReplay: Boolean,
    val paymentMethod: String = PaymentMethodPrepaid
)

data class ExpenseCancellationSnapshot(
    val expenseId: String,
    val expectedExpenseUpdatedAt: Long,
    val originalPurchaseTransactionId: String?,
    val prepaidAccountId: String?,
    val amount: Long,
    val purchaseDate: String,
    val paymentMethod: String = PaymentMethodPrepaid
)

enum class ExpenseCancellationFailure {
    InvalidRequest,
    ExpenseNotFound,
    Conflict,
    AlreadyCancelled,
    StaleState,
    PrepaidStateInconsistent,
    CancellationStateCorrupted,
    DatabaseFailure
}

class ExpenseCancellationException(
    val failure: ExpenseCancellationFailure,
    cause: Throwable? = null
) : IllegalStateException(failure.name, cause)

fun interface ExpenseCancellationClock {
    fun nowMillis(): Long
}

fun interface ExpenseCancellationIdGenerator {
    fun newId(): String
}

class SystemExpenseCancellationClock @Inject constructor() : ExpenseCancellationClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

class UuidExpenseCancellationIdGenerator @Inject constructor() :
    ExpenseCancellationIdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

internal data class ExpenseCancellationPlan(
    val request: ExpenseCancellationRequest,
    val requestFingerprint: String,
    val expense: ExpenseRecord,
    val link: ExpensePrepaidLinkRecord,
    val purchase: PrepaidTransactionRecord
)

internal fun planExpenseCancellation(
    request: ExpenseCancellationRequest,
    expense: ExpenseRecord?,
    link: ExpensePrepaidLinkRecord?,
    purchase: PrepaidTransactionRecord?,
    existingReversal: PrepaidTransactionRecord?
): ExpenseCancellationPlan {
    val normalized = normalizeCancellationRequest(request)
    val savedExpense = expense
        ?: throw ExpenseCancellationException(ExpenseCancellationFailure.ExpenseNotFound)
    if (
        savedExpense.updatedAt != normalized.expectedExpenseUpdatedAt ||
        savedExpense.amount != normalized.expectedAmount ||
        normalizePaymentMethod(savedExpense.paymentMethod) != PaymentMethodPrepaid ||
        normalized.expectedPaymentMethod != PaymentMethodPrepaid
    ) {
        fail(ExpenseCancellationFailure.StaleState)
    }
    val savedLink = link ?: fail(ExpenseCancellationFailure.StaleState)
    if (
        savedLink.expenseId != savedExpense.id ||
        savedLink.purchaseTransactionId !=
        normalized.expectedOriginalPurchaseTransactionId
    ) {
        fail(ExpenseCancellationFailure.StaleState)
    }
    val savedPurchase = purchase ?: fail(ExpenseCancellationFailure.StaleState)
    if (
        savedPurchase.id != normalized.expectedOriginalPurchaseTransactionId ||
        savedPurchase.accountId != normalized.expectedPrepaidAccountId ||
        savedPurchase.balanceDelta != negateExact(normalized.expectedAmount) ||
        savedPurchase.transactionDate != normalized.expectedPurchaseDate
    ) {
        fail(ExpenseCancellationFailure.StaleState)
    }
    if (
        savedPurchase.transactionType != PrepaidTransactionType.Purchase ||
        savedPurchase.reversalOfTransactionId != null ||
        savedPurchase.expenseId != savedExpense.id ||
        savedPurchase.balanceDelta != negateExact(savedExpense.amount)
    ) {
        fail(ExpenseCancellationFailure.PrepaidStateInconsistent)
    }
    if (existingReversal != null) {
        fail(ExpenseCancellationFailure.PrepaidStateInconsistent)
    }
    return ExpenseCancellationPlan(
        request = normalized,
        requestFingerprint = fingerprint(normalized),
        expense = savedExpense,
        link = savedLink,
        purchase = savedPurchase
    )
}

class ExpenseCancellationRepository internal constructor(
    private val database: WarunDatabase,
    private val warunDao: WarunDao,
    private val accountDao: PrepaidAccountDao,
    private val transactionDao: PrepaidTransactionDao,
    private val prepaidLinkDao: ExpensePrepaidLinkDao,
    private val cancellationDao: ExpenseCancellationDao,
    private val clock: ExpenseCancellationClock,
    private val idGenerator: ExpenseCancellationIdGenerator
) {
    @Inject
    constructor(
        database: WarunDatabase,
        warunDao: WarunDao,
        accountDao: PrepaidAccountDao,
        transactionDao: PrepaidTransactionDao,
        prepaidLinkDao: ExpensePrepaidLinkDao,
        cancellationDao: ExpenseCancellationDao,
        clock: SystemExpenseCancellationClock,
        idGenerator: UuidExpenseCancellationIdGenerator
    ) : this(
        database = database,
        warunDao = warunDao,
        accountDao = accountDao,
        transactionDao = transactionDao,
        prepaidLinkDao = prepaidLinkDao,
        cancellationDao = cancellationDao,
        clock = clock as ExpenseCancellationClock,
        idGenerator = idGenerator as ExpenseCancellationIdGenerator
    )

    suspend fun loadCancellationSnapshot(
        expenseId: String
    ): ExpenseCancellationSnapshot {
        return try {
            database.withTransaction {
                val canonicalExpenseId = ExpenseCancellationRules.canonicalIdentifier(expenseId)
                if (cancellationDao.getByExpenseId(canonicalExpenseId) != null) {
                    fail(ExpenseCancellationFailure.AlreadyCancelled)
                }
                val expense = warunDao.getActiveExpenseRecord(canonicalExpenseId)
                    ?: if (warunDao.getExpenseRecord(canonicalExpenseId) == null) {
                        fail(ExpenseCancellationFailure.ExpenseNotFound)
                    } else {
                        fail(ExpenseCancellationFailure.StaleState)
                    }
                val paymentMethod = normalizePaymentMethod(expense.paymentMethod)
                if (!isSupportedPaymentMethod(paymentMethod) || expense.amount <= 0L) {
                    fail(ExpenseCancellationFailure.InvalidRequest)
                }
                val link = prepaidLinkDao.getByExpenseId(canonicalExpenseId)
                val purchase = if (paymentMethod == PaymentMethodPrepaid) {
                    val requiredLink = link
                        ?: fail(ExpenseCancellationFailure.PrepaidStateInconsistent)
                    transactionDao.getById(requiredLink.purchaseTransactionId)
                        ?: fail(ExpenseCancellationFailure.PrepaidStateInconsistent)
                } else {
                    if (link != null) {
                        fail(ExpenseCancellationFailure.PrepaidStateInconsistent)
                    }
                    null
                }
                if (purchase != null && (
                    link?.expenseId != expense.id ||
                        purchase.id != link?.purchaseTransactionId ||
                        purchase.transactionType != PrepaidTransactionType.Purchase ||
                        purchase.reversalOfTransactionId != null ||
                        purchase.expenseId != expense.id ||
                        purchase.balanceDelta != negateExact(expense.amount) ||
                        purchase.accountId.isBlank() ||
                        purchase.transactionDate.isBlank() ||
                        transactionDao.getByReversalOfTransactionId(purchase.id) != null ||
                        accountDao.getById(purchase.accountId) == null
                    )
                ) {
                    fail(ExpenseCancellationFailure.PrepaidStateInconsistent)
                }
                ExpenseCancellationSnapshot(
                    expenseId = expense.id,
                    expectedExpenseUpdatedAt = expense.updatedAt,
                    originalPurchaseTransactionId = purchase?.id,
                    prepaidAccountId = purchase?.accountId,
                    amount = expense.amount,
                    purchaseDate = purchase?.transactionDate ?: expense.expenseDate,
                    paymentMethod = paymentMethod
                )
            }
        } catch (error: ExpenseCancellationException) {
            throw error
        } catch (error: ExpenseCancellationValidationException) {
            throw ExpenseCancellationException(
                ExpenseCancellationFailure.InvalidRequest,
                error
            )
        } catch (error: SQLiteException) {
            throw ExpenseCancellationException(
                ExpenseCancellationFailure.DatabaseFailure,
                error
            )
        }
    }

    suspend fun cancelExpense(
        request: ExpenseCancellationRequest
    ): ExpenseCancellationResult {
        return try {
            database.withTransaction {
                val normalized = normalizeCancellationRequest(request)
                val requestFingerprint = fingerprint(normalized)
                cancellationDao.getByOperationKey(normalized.operationKey)?.let { existing ->
                    return@withTransaction replayResult(
                        existing = existing,
                        request = normalized,
                        requestFingerprint = requestFingerprint
                    )
                }
                if (cancellationDao.getByExpenseId(normalized.expenseId) != null) {
                    fail(ExpenseCancellationFailure.AlreadyCancelled)
                }
                val expense = warunDao.getExpenseRecord(normalized.expenseId)
                    ?: fail(ExpenseCancellationFailure.ExpenseNotFound)
                if (normalized.expectedPaymentMethod == PaymentMethodPrepaid) {
                    cancelPrepaid(
                        request = normalized,
                        requestFingerprint = requestFingerprint,
                        expense = expense
                    )
                } else {
                    cancelNonPrepaid(
                        request = normalized,
                        requestFingerprint = requestFingerprint,
                        expense = expense
                    )
                }
            }
        } catch (error: ExpenseCancellationException) {
            throw error
        } catch (error: PrepaidValidationException) {
            throw ExpenseCancellationException(
                ExpenseCancellationFailure.PrepaidStateInconsistent,
                error
            )
        } catch (error: SQLiteException) {
            throw ExpenseCancellationException(
                ExpenseCancellationFailure.DatabaseFailure,
                error
            )
        }
    }

    private suspend fun cancelPrepaid(
        request: ExpenseCancellationRequest,
        requestFingerprint: String,
        expense: ExpenseRecord
    ): ExpenseCancellationResult {
        val link = prepaidLinkDao.getByExpenseId(expense.id)
        val purchase = link?.let { transactionDao.getById(it.purchaseTransactionId) }
        val existingReversal = purchase?.let {
            transactionDao.getByReversalOfTransactionId(it.id)
        }
        val plan = planExpenseCancellation(
            request = request,
            expense = expense,
            link = link,
            purchase = purchase,
            existingReversal = existingReversal
        )
        cancellationDao.getByOriginalPurchaseTransactionId(plan.purchase.id)?.let {
            fail(ExpenseCancellationFailure.AlreadyCancelled)
        }

        val cancelledAt = requireValidCancelledAt()
        val reversal = createExpenseCancellationReversal(
            plan = plan,
            reversalId = idGenerator.newId(),
            cancelledAt = cancelledAt
        )
        validateReversalForInsert(plan, reversal)
        transactionDao.insert(reversal)

        val cancellation = ExpenseCancellationRecord(
            expenseId = plan.expense.id,
            operationKey = plan.request.operationKey,
            requestFingerprint = requestFingerprint,
            originalPurchaseTransactionId = plan.purchase.id,
            reversalTransactionId = reversal.id,
            cancellationDate = plan.request.cancellationDate,
            cancelledAt = cancelledAt,
            reason = plan.request.reason
        )
        validateCancellationRecord(cancellation)
        cancellationDao.insert(cancellation)

        val savedCancellation = cancellationDao.getByExpenseId(plan.expense.id)
            ?: fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        if (
            savedCancellation != cancellation ||
            warunDao.getExpenseRecord(plan.expense.id) != plan.expense ||
            prepaidLinkDao.getByExpenseId(plan.expense.id) != plan.link ||
            transactionDao.getById(plan.purchase.id) != plan.purchase
        ) {
            fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        }
        val savedReversal = requireValidPrepaidReversal(savedCancellation, plan.request)
        return resultOf(savedCancellation, plan.request, savedReversal, false)
    }

    private suspend fun cancelNonPrepaid(
        request: ExpenseCancellationRequest,
        requestFingerprint: String,
        expense: ExpenseRecord
    ): ExpenseCancellationResult {
        if (
            expense.updatedAt != request.expectedExpenseUpdatedAt ||
            expense.amount != request.expectedAmount ||
            expense.expenseDate != request.expectedPurchaseDate ||
            normalizePaymentMethod(expense.paymentMethod) != request.expectedPaymentMethod
        ) {
            fail(ExpenseCancellationFailure.StaleState)
        }
        if (
            request.expectedPaymentMethod == PaymentMethodPrepaid ||
            prepaidLinkDao.getByExpenseId(expense.id) != null
        ) {
            fail(ExpenseCancellationFailure.PrepaidStateInconsistent)
        }
        val cancellation = ExpenseCancellationRecord(
            expenseId = expense.id,
            operationKey = request.operationKey,
            requestFingerprint = requestFingerprint,
            originalPurchaseTransactionId = null,
            reversalTransactionId = null,
            cancellationDate = request.cancellationDate,
            cancelledAt = requireValidCancelledAt(),
            reason = request.reason
        )
        validateCancellationRecord(cancellation)
        cancellationDao.insert(cancellation)

        val savedCancellation = cancellationDao.getByExpenseId(expense.id)
            ?: fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        if (
            savedCancellation != cancellation ||
            warunDao.getExpenseRecord(expense.id) != expense ||
            prepaidLinkDao.getByExpenseId(expense.id) != null
        ) {
            fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        }
        return resultOf(savedCancellation, request, reversal = null, idempotentReplay = false)
    }

    private fun requireValidCancelledAt(): Long = clock.nowMillis().also {
        if (it < 0L) fail(ExpenseCancellationFailure.InvalidRequest)
    }

    private suspend fun replayResult(
        existing: ExpenseCancellationRecord,
        request: ExpenseCancellationRequest,
        requestFingerprint: String
    ): ExpenseCancellationResult {
        if (
            existing.expenseId != request.expenseId ||
            existing.originalPurchaseTransactionId !=
            request.expectedOriginalPurchaseTransactionId ||
            existing.requestFingerprint != requestFingerprint
        ) {
            fail(ExpenseCancellationFailure.Conflict)
        }
        validateCancellationRecord(existing)
        if (
            existing.cancellationDate != request.cancellationDate ||
            existing.reason != request.reason
        ) {
            fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        }
        val expense = warunDao.getExpenseRecord(request.expenseId)
            ?: fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        if (
            expense.updatedAt != request.expectedExpenseUpdatedAt ||
            expense.amount != request.expectedAmount ||
            normalizePaymentMethod(expense.paymentMethod) != request.expectedPaymentMethod ||
            (
                request.expectedPaymentMethod != PaymentMethodPrepaid &&
                    expense.expenseDate != request.expectedPurchaseDate
                )
        ) {
            fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        }
        val reversal = if (request.expectedPaymentMethod == PaymentMethodPrepaid) {
            requireValidPrepaidReversal(existing, request)
        } else {
            if (
                existing.originalPurchaseTransactionId != null ||
                existing.reversalTransactionId != null ||
                prepaidLinkDao.getByExpenseId(request.expenseId) != null
            ) {
                fail(ExpenseCancellationFailure.CancellationStateCorrupted)
            }
            null
        }
        return resultOf(existing, request, reversal, idempotentReplay = true)
    }

    private suspend fun requireValidPrepaidReversal(
        cancellation: ExpenseCancellationRecord,
        request: ExpenseCancellationRequest
    ): PrepaidTransactionRecord {
        val purchaseId = cancellation.originalPurchaseTransactionId
            ?: fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        val reversalId = cancellation.reversalTransactionId
            ?: fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        val accountId = request.expectedPrepaidAccountId
            ?: fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        val reversal = transactionDao.getById(reversalId)
            ?: fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        if (
            reversal.transactionType != PrepaidTransactionType.Reversal ||
            reversal.reversalOfTransactionId != purchaseId ||
            reversal.accountId != accountId ||
            reversal.balanceDelta != request.expectedAmount ||
            reversal.expenseId != request.expenseId ||
            reversal.transactionDate != request.cancellationDate ||
            reversal.createdAt != cancellation.cancelledAt ||
            reversal.chargeSource != null ||
            reversal.operationKey != reversalOperationKey(request.operationKey)
        ) {
            fail(ExpenseCancellationFailure.CancellationStateCorrupted)
        }
        return reversal
    }

    private suspend fun validateReversalForInsert(
        plan: ExpenseCancellationPlan,
        reversal: PrepaidTransactionRecord
    ) {
        PrepaidLedgerRules.validateTransaction(
            account = accountDao.getById(plan.purchase.accountId),
            transaction = reversal,
            existingOperation = transactionDao.getByOperationKey(reversal.operationKey),
            reversalTarget = plan.purchase,
            existingReversal = transactionDao.getByReversalOfTransactionId(plan.purchase.id),
            currentBalance = transactionDao.getBalance(plan.purchase.accountId)
        )
    }

    private fun resultOf(
        cancellation: ExpenseCancellationRecord,
        request: ExpenseCancellationRequest,
        reversal: PrepaidTransactionRecord?,
        idempotentReplay: Boolean
    ) = ExpenseCancellationResult(
        expenseId = cancellation.expenseId,
        originalPurchaseTransactionId = cancellation.originalPurchaseTransactionId,
        reversalTransactionId = cancellation.reversalTransactionId,
        prepaidAccountId = reversal?.accountId,
        amount = request.expectedAmount,
        cancellationDate = cancellation.cancellationDate,
        cancelledAt = cancellation.cancelledAt,
        reason = cancellation.reason,
        idempotentReplay = idempotentReplay,
        paymentMethod = request.expectedPaymentMethod
    )
}

internal fun createExpenseCancellationReversal(
    plan: ExpenseCancellationPlan,
    reversalId: String,
    cancelledAt: Long
): PrepaidTransactionRecord {
    if (reversalId.isBlank() || cancelledAt < 0L) {
        fail(ExpenseCancellationFailure.InvalidRequest)
    }
    return PrepaidTransactionRecord(
        id = reversalId,
        accountId = plan.purchase.accountId,
        transactionDate = plan.request.cancellationDate,
        transactionType = PrepaidTransactionType.Reversal,
        balanceDelta = negateExact(plan.purchase.balanceDelta),
        expenseId = plan.expense.id,
        chargeSource = null,
        reversalOfTransactionId = plan.purchase.id,
        operationKey = reversalOperationKey(plan.request.operationKey),
        memo = plan.request.reason.orEmpty(),
        createdAt = cancelledAt
    )
}

private fun normalizeCancellationRequest(
    request: ExpenseCancellationRequest
): ExpenseCancellationRequest {
    return try {
        ExpenseCancellationRules.validateOperationKey(request.operationKey)
        val paymentMethod = normalizePaymentMethod(request.expectedPaymentMethod)
        if (!isSupportedPaymentMethod(paymentMethod)) {
            fail(ExpenseCancellationFailure.InvalidRequest)
        }
        if (paymentMethod == PaymentMethodPrepaid) {
            val canonical = ExpenseCancellationRequestFingerprint.canonicalize(
                ExpenseCancellationRequestFingerprintInput(
                    expenseId = request.expenseId,
                    expectedExpenseUpdatedAt = request.expectedExpenseUpdatedAt,
                    originalPurchaseTransactionId =
                        request.expectedOriginalPurchaseTransactionId
                            ?: fail(ExpenseCancellationFailure.InvalidRequest),
                    prepaidAccountId = request.expectedPrepaidAccountId
                        ?: fail(ExpenseCancellationFailure.InvalidRequest),
                    amount = request.expectedAmount,
                    purchaseDate = request.expectedPurchaseDate,
                    cancellationDate = request.cancellationDate,
                    reason = request.reason
                )
            )
            request.copy(
                expenseId = canonical.expenseId,
                expectedExpenseUpdatedAt = canonical.expectedExpenseUpdatedAt,
                expectedOriginalPurchaseTransactionId =
                    canonical.originalPurchaseTransactionId,
                expectedPrepaidAccountId = canonical.prepaidAccountId,
                expectedAmount = canonical.amount,
                expectedPurchaseDate = canonical.purchaseDate,
                cancellationDate = canonical.cancellationDate,
                reason = canonical.reason,
                expectedPaymentMethod = paymentMethod
            )
        } else {
            if (
                request.expectedOriginalPurchaseTransactionId != null ||
                request.expectedPrepaidAccountId != null
            ) {
                fail(ExpenseCancellationFailure.InvalidRequest)
            }
            val canonical = ExpenseCancellationRequestFingerprint.canonicalizeNonPrepaid(
                NonPrepaidExpenseCancellationRequestFingerprintInput(
                    expenseId = request.expenseId,
                    expectedExpenseUpdatedAt = request.expectedExpenseUpdatedAt,
                    paymentMethod = paymentMethod,
                    amount = request.expectedAmount,
                    expenseDate = request.expectedPurchaseDate,
                    cancellationDate = request.cancellationDate,
                    reason = request.reason
                )
            )
            request.copy(
                expenseId = canonical.expenseId,
                expectedExpenseUpdatedAt = canonical.expectedExpenseUpdatedAt,
                expectedOriginalPurchaseTransactionId = null,
                expectedPrepaidAccountId = null,
                expectedAmount = canonical.amount,
                expectedPurchaseDate = canonical.expenseDate,
                cancellationDate = canonical.cancellationDate,
                reason = canonical.reason,
                expectedPaymentMethod = canonical.paymentMethod
            )
        }
    } catch (error: ExpenseCancellationValidationException) {
        throw ExpenseCancellationException(
            ExpenseCancellationFailure.InvalidRequest,
            error
        )
    }
}

private fun fingerprint(request: ExpenseCancellationRequest): String =
    if (request.expectedPaymentMethod == PaymentMethodPrepaid) {
        ExpenseCancellationRequestFingerprint.create(
            ExpenseCancellationRequestFingerprintInput(
                expenseId = request.expenseId,
                expectedExpenseUpdatedAt = request.expectedExpenseUpdatedAt,
                originalPurchaseTransactionId =
                    requireNotNull(request.expectedOriginalPurchaseTransactionId),
                prepaidAccountId = requireNotNull(request.expectedPrepaidAccountId),
                amount = request.expectedAmount,
                purchaseDate = request.expectedPurchaseDate,
                cancellationDate = request.cancellationDate,
                reason = request.reason
            )
        )
    } else {
        ExpenseCancellationRequestFingerprint.createNonPrepaid(
            NonPrepaidExpenseCancellationRequestFingerprintInput(
                expenseId = request.expenseId,
                expectedExpenseUpdatedAt = request.expectedExpenseUpdatedAt,
                paymentMethod = request.expectedPaymentMethod,
                amount = request.expectedAmount,
                expenseDate = request.expectedPurchaseDate,
                cancellationDate = request.cancellationDate,
                reason = request.reason
            )
        )
    }

private fun validateCancellationRecord(record: ExpenseCancellationRecord) {
    try {
        ExpenseCancellationRules.validateRecord(record)
    } catch (error: ExpenseCancellationValidationException) {
        throw ExpenseCancellationException(
            ExpenseCancellationFailure.CancellationStateCorrupted,
            error
        )
    }
}

private fun reversalOperationKey(operationKey: String): String =
    "$operationKey:reversal"

private fun negateExact(value: Long): Long = try {
    Math.negateExact(value)
} catch (error: ArithmeticException) {
    throw ExpenseCancellationException(
        ExpenseCancellationFailure.PrepaidStateInconsistent,
        error
    )
}

private fun fail(failure: ExpenseCancellationFailure): Nothing {
    throw ExpenseCancellationException(failure)
}
