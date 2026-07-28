package com.warun.accounting.data.prepaid

import androidx.room.withTransaction
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.ExpenseEvidenceLinkRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpensePrepaidLinkDao
import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.PrepaidAccountBalance
import com.warun.accounting.data.local.PrepaidAccountDao
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.PrepaidTransactionDao
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import com.warun.accounting.data.local.WarunDao
import com.warun.accounting.data.local.WarunDatabase
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import com.warun.accounting.util.PaymentMethodPrepaid
import com.warun.accounting.util.normalizePaymentMethod

interface PrepaidRepository {
    fun observeActiveAccounts(): Flow<List<PrepaidAccountRecord>>
    fun observeAllAccounts(): Flow<List<PrepaidAccountRecord>>
    fun observeTransactions(accountId: String): Flow<List<PrepaidTransactionRecord>>
    fun observeTransactionsBetween(
        accountId: String,
        from: String,
        to: String
    ): Flow<List<PrepaidTransactionRecord>>
    fun observeTransactionsByExpense(expenseId: String): Flow<List<PrepaidTransactionRecord>>
    fun observeAllAccountBalances(): Flow<List<PrepaidAccountBalance>>
    fun observeAllTransactions(): Flow<List<PrepaidTransactionRecord>>
    fun observeAllExpenseLinks(): Flow<List<ExpensePrepaidLinkRecord>>
    suspend fun getAccount(accountId: String): PrepaidAccountRecord?
    suspend fun getExpenseLink(expenseId: String): ExpensePrepaidLinkRecord?
    suspend fun getMajicaAccount(): PrepaidAccountRecord?
    suspend fun getAuPayPrepaidAccount(): PrepaidAccountRecord?
    suspend fun getBalance(accountId: String): Long
    suspend fun validateTransactionForInsert(transaction: PrepaidTransactionRecord)
    suspend fun validateLinkForInsert(
        link: ExpensePrepaidLinkRecord,
        expectedAccountId: String
    )
    suspend fun createCharge(input: PrepaidChargeInput): PrepaidWriteResult
    suspend fun createAdjustment(input: PrepaidAdjustmentInput): PrepaidWriteResult
    suspend fun reverseTransaction(input: PrepaidReversalInput): PrepaidWriteResult
    suspend fun savePurchaseExpense(input: PrepaidExpensePurchaseInput): PrepaidExpenseWriteResult
}

data class PrepaidChargeInput(
    val accountId: String,
    val transactionDate: String,
    val amount: Long,
    val chargeSource: String,
    val memo: String,
    val operationKey: String
)

data class PrepaidAdjustmentInput(
    val accountId: String,
    val transactionDate: String,
    val balanceDelta: Long,
    val memo: String,
    val operationKey: String
)

data class PrepaidReversalInput(
    val accountId: String,
    val targetTransactionId: String,
    val transactionDate: String,
    val memo: String,
    val operationKey: String
)

data class PrepaidWriteResult(
    val transaction: PrepaidTransactionRecord,
    val balance: Long,
    val wasAlreadyApplied: Boolean
)

data class PrepaidExpensePurchaseInput(
    val expense: ExpenseRecord,
    val accountId: String,
    val operationKey: String,
    val linkedAt: Long,
    val evidence: EvidenceRecord? = null,
    val evidenceLink: ExpenseEvidenceLinkRecord? = null
)

data class PrepaidExpenseWriteResult(
    val expense: ExpenseRecord,
    val transaction: PrepaidTransactionRecord,
    val prepaidLink: ExpensePrepaidLinkRecord,
    val balance: Long,
    val wasAlreadyApplied: Boolean
)

class OfflinePrepaidRepository @Inject constructor(
    private val database: WarunDatabase,
    private val accountDao: PrepaidAccountDao,
    private val transactionDao: PrepaidTransactionDao,
    private val linkDao: ExpensePrepaidLinkDao,
    private val warunDao: WarunDao
) : PrepaidRepository {
    override fun observeActiveAccounts(): Flow<List<PrepaidAccountRecord>> =
        accountDao.observeActiveAccounts()

    override fun observeAllAccounts(): Flow<List<PrepaidAccountRecord>> =
        accountDao.observeAllAccounts()

    override fun observeTransactions(accountId: String): Flow<List<PrepaidTransactionRecord>> =
        transactionDao.observeByAccount(accountId)

    override fun observeTransactionsBetween(
        accountId: String,
        from: String,
        to: String
    ): Flow<List<PrepaidTransactionRecord>> =
        transactionDao.observeByAccountBetween(accountId, from, to)

    override fun observeTransactionsByExpense(
        expenseId: String
    ): Flow<List<PrepaidTransactionRecord>> = transactionDao.observeByExpense(expenseId)

    override fun observeAllAccountBalances(): Flow<List<PrepaidAccountBalance>> =
        transactionDao.observeAllAccountBalances()

    override fun observeAllTransactions(): Flow<List<PrepaidTransactionRecord>> =
        transactionDao.observeAll()

    override fun observeAllExpenseLinks(): Flow<List<ExpensePrepaidLinkRecord>> =
        linkDao.observeAll()

    override suspend fun getAccount(accountId: String): PrepaidAccountRecord? =
        accountDao.getById(accountId)

    override suspend fun getExpenseLink(expenseId: String): ExpensePrepaidLinkRecord? =
        linkDao.getByExpenseId(expenseId)

    override suspend fun getMajicaAccount(): PrepaidAccountRecord? =
        accountDao.getById(PrepaidAccountId.Majica)

    override suspend fun getAuPayPrepaidAccount(): PrepaidAccountRecord? =
        accountDao.getById(PrepaidAccountId.AuPayPrepaid)

    override suspend fun getBalance(accountId: String): Long {
        if (accountDao.getById(accountId) == null) {
            throw PrepaidValidationException(PrepaidValidationFailure.AccountNotFound)
        }
        return transactionDao.getBalance(accountId)
    }

    override suspend fun validateTransactionForInsert(
        transaction: PrepaidTransactionRecord
    ) {
        val account = accountDao.getById(transaction.accountId)
        val targetId = transaction.reversalOfTransactionId
        val reversalTarget = if (targetId == null) {
            null
        } else {
            transactionDao.getById(targetId)
        }
        val existingReversal = if (targetId == null) {
            null
        } else {
            transactionDao.getByReversalOfTransactionId(targetId)
        }
        PrepaidLedgerRules.validateTransaction(
            account = account,
            transaction = transaction,
            existingOperation = transactionDao.getByOperationKey(transaction.operationKey),
            reversalTarget = reversalTarget,
            existingReversal = existingReversal,
            currentBalance = transactionDao.getBalance(transaction.accountId)
        )
        val expenseId = transaction.expenseId
        if (
            transaction.transactionType == PrepaidTransactionType.Purchase &&
            expenseId != null &&
            warunDao.getExpenseRecord(expenseId) == null
        ) {
            throw PrepaidValidationException(PrepaidValidationFailure.ExpenseNotFound)
        }
    }

    override suspend fun validateLinkForInsert(
        link: ExpensePrepaidLinkRecord,
        expectedAccountId: String
    ) {
        PrepaidLedgerRules.validateLink(
            link = link,
            expenseExists = warunDao.getExpenseRecord(link.expenseId) != null,
            purchaseTransaction = transactionDao.getById(link.purchaseTransactionId),
            expectedAccountId = expectedAccountId,
            existingExpenseLink = linkDao.getByExpenseId(link.expenseId),
            existingPurchaseLink = linkDao.getByPurchaseTransactionId(
                link.purchaseTransactionId
            )
        )
    }

    override suspend fun createCharge(input: PrepaidChargeInput): PrepaidWriteResult =
        database.withTransaction {
            validateDate(input.transactionDate)
            val candidate = PrepaidTransactionRecord(
                id = UUID.randomUUID().toString(),
                accountId = input.accountId,
                transactionDate = input.transactionDate,
                transactionType = PrepaidTransactionType.Charge,
                balanceDelta = input.amount,
                expenseId = null,
                chargeSource = input.chargeSource,
                reversalOfTransactionId = null,
                operationKey = input.operationKey,
                memo = input.memo.trim(),
                createdAt = System.currentTimeMillis()
            )
            insertIdempotently(candidate, ::samePrepaidBusinessOperation)
        }

    override suspend fun createAdjustment(
        input: PrepaidAdjustmentInput
    ): PrepaidWriteResult = database.withTransaction {
        validateDate(input.transactionDate)
        if (input.memo.isBlank()) {
            throw PrepaidValidationException(PrepaidValidationFailure.BlankIdentifier)
        }
        val candidate = PrepaidTransactionRecord(
            id = UUID.randomUUID().toString(),
            accountId = input.accountId,
            transactionDate = input.transactionDate,
            transactionType = PrepaidTransactionType.Adjustment,
            balanceDelta = input.balanceDelta,
            expenseId = null,
            chargeSource = null,
            reversalOfTransactionId = null,
            operationKey = input.operationKey,
            memo = input.memo.trim(),
            createdAt = System.currentTimeMillis()
        )
        insertIdempotently(candidate, ::samePrepaidBusinessOperation)
    }

    override suspend fun reverseTransaction(
        input: PrepaidReversalInput
    ): PrepaidWriteResult = database.withTransaction {
        validateDate(input.transactionDate)
        val target = transactionDao.getById(input.targetTransactionId)
            ?: throw PrepaidValidationException(
                PrepaidValidationFailure.ReversalTargetNotFound
            )
        if (
            target.transactionType !in setOf(
                PrepaidTransactionType.Charge,
                PrepaidTransactionType.Adjustment
            )
        ) {
            throw PrepaidValidationException(
                if (target.transactionType == PrepaidTransactionType.Reversal) {
                    PrepaidValidationFailure.ReversalOfReversal
                } else {
                    PrepaidValidationFailure.UnknownTransactionType
                }
            )
        }
        val reversalDelta = try {
            Math.negateExact(target.balanceDelta)
        } catch (_: ArithmeticException) {
            throw PrepaidValidationException(PrepaidValidationFailure.ArithmeticOverflow)
        }
        val candidate = PrepaidTransactionRecord(
            id = UUID.randomUUID().toString(),
            accountId = input.accountId,
            transactionDate = input.transactionDate,
            transactionType = PrepaidTransactionType.Reversal,
            balanceDelta = reversalDelta,
            expenseId = target.expenseId,
            chargeSource = null,
            reversalOfTransactionId = target.id,
            operationKey = input.operationKey,
            memo = input.memo.trim(),
            createdAt = System.currentTimeMillis()
        )
        insertIdempotently(candidate, ::samePrepaidBusinessOperation)
    }

    override suspend fun savePurchaseExpense(
        input: PrepaidExpensePurchaseInput
    ): PrepaidExpenseWriteResult = database.withTransaction {
        validateDate(input.expense.expenseDate)
        if (normalizePaymentMethod(input.expense.paymentMethod) != PaymentMethodPrepaid) {
            throw PrepaidValidationException(PrepaidValidationFailure.PaymentMethodMismatch)
        }
        if (
            input.expense.id.isBlank() ||
            input.accountId.isBlank() ||
            input.operationKey.isBlank()
        ) {
            throw PrepaidValidationException(PrepaidValidationFailure.BlankIdentifier)
        }
        if (input.expense.amount <= 0L) {
            throw PrepaidValidationException(PrepaidValidationFailure.InvalidBalanceDelta)
        }
        if ((input.evidence == null) != (input.evidenceLink == null)) {
            throw PrepaidValidationException(PrepaidValidationFailure.EvidenceContentMismatch)
        }
        input.evidenceLink?.let { link ->
            if (link.expenseId != input.expense.id || link.evidenceId != input.evidence?.id) {
                throw PrepaidValidationException(PrepaidValidationFailure.EvidenceContentMismatch)
            }
        }

        val candidateTransaction = PrepaidTransactionRecord(
            id = UUID.randomUUID().toString(),
            accountId = input.accountId,
            transactionDate = input.expense.expenseDate,
            transactionType = PrepaidTransactionType.Purchase,
            balanceDelta = negateExact(input.expense.amount),
            expenseId = input.expense.id,
            chargeSource = null,
            reversalOfTransactionId = null,
            operationKey = input.operationKey,
            memo = input.expense.memo.orEmpty(),
            createdAt = System.currentTimeMillis()
        )
        val existingOperation = transactionDao.getByOperationKey(input.operationKey)
        if (existingOperation != null) {
            return@withTransaction validateExistingPurchase(input, existingOperation)
        }

        val account = accountDao.getById(input.accountId)
        PrepaidLedgerRules.validateTransaction(
            account = account,
            transaction = candidateTransaction,
            currentBalance = transactionDao.getBalance(input.accountId)
        )
        if (warunDao.getExpenseRecord(input.expense.id) != null) {
            throw PrepaidValidationException(PrepaidValidationFailure.ExpenseAlreadyExists)
        }

        if (input.evidence == null) {
            warunDao.insertExpenseRecord(input.expense)
        } else {
            warunDao.saveExpenseWithEvidence(
                expense = input.expense,
                evidence = input.evidence,
                link = requireNotNull(input.evidenceLink)
            )
        }
        transactionDao.insert(candidateTransaction)
        val prepaidLink = ExpensePrepaidLinkRecord(
            expenseId = input.expense.id,
            purchaseTransactionId = candidateTransaction.id,
            linkedAt = input.linkedAt,
            updatedAt = input.linkedAt
        )
        validateLinkForInsert(prepaidLink, input.accountId)
        linkDao.insert(prepaidLink)
        PrepaidExpenseWriteResult(
            expense = input.expense,
            transaction = candidateTransaction,
            prepaidLink = prepaidLink,
            balance = transactionDao.getBalance(input.accountId),
            wasAlreadyApplied = false
        )
    }

    private suspend fun validateExistingPurchase(
        input: PrepaidExpensePurchaseInput,
        existingOperation: PrepaidTransactionRecord
    ): PrepaidExpenseWriteResult {
        val candidate = PrepaidTransactionRecord(
            id = existingOperation.id,
            accountId = input.accountId,
            transactionDate = input.expense.expenseDate,
            transactionType = PrepaidTransactionType.Purchase,
            balanceDelta = negateExact(input.expense.amount),
            expenseId = input.expense.id,
            chargeSource = null,
            reversalOfTransactionId = null,
            operationKey = input.operationKey,
            memo = input.expense.memo.orEmpty(),
            createdAt = existingOperation.createdAt
        )
        if (!samePrepaidBusinessOperation(existingOperation, candidate)) {
            throw PrepaidValidationException(PrepaidValidationFailure.DuplicateOperationKey)
        }
        val savedExpense = warunDao.getExpenseRecord(input.expense.id)
            ?: throw PrepaidValidationException(PrepaidValidationFailure.ExpenseNotFound)
        if (!sameExpenseBusinessOperation(savedExpense, input.expense)) {
            throw PrepaidValidationException(PrepaidValidationFailure.ExpenseContentMismatch)
        }
        val prepaidLink = linkDao.getByExpenseId(input.expense.id)
            ?: throw PrepaidValidationException(PrepaidValidationFailure.LinkTransactionNotFound)
        if (
            prepaidLink.purchaseTransactionId != existingOperation.id ||
            linkDao.getByPurchaseTransactionId(existingOperation.id)?.expenseId != input.expense.id
        ) {
            throw PrepaidValidationException(PrepaidValidationFailure.DuplicatePurchaseLink)
        }
        validateExistingEvidence(input)
        return PrepaidExpenseWriteResult(
            expense = savedExpense,
            transaction = existingOperation,
            prepaidLink = prepaidLink,
            balance = transactionDao.getBalance(existingOperation.accountId),
            wasAlreadyApplied = true
        )
    }

    private suspend fun validateExistingEvidence(input: PrepaidExpensePurchaseInput) {
        val savedLinks = warunDao.getEvidenceLinksForExpense(input.expense.id)
        val candidateEvidence = input.evidence
        val candidateLink = input.evidenceLink
        if (candidateEvidence == null || candidateLink == null) {
            if (savedLinks.isNotEmpty()) {
                throw PrepaidValidationException(PrepaidValidationFailure.EvidenceContentMismatch)
            }
            return
        }
        if (
            savedLinks.size != 1 ||
            savedLinks.single().evidenceId != candidateEvidence.id
        ) {
            throw PrepaidValidationException(PrepaidValidationFailure.EvidenceContentMismatch)
        }
        val savedEvidence = warunDao.getEvidenceRecord(candidateEvidence.id)
            ?: throw PrepaidValidationException(PrepaidValidationFailure.EvidenceContentMismatch)
        if (
            savedEvidence.captureId != candidateEvidence.captureId ||
            savedEvidence.storedUri != candidateEvidence.storedUri ||
            savedEvidence.byteSize != candidateEvidence.byteSize ||
            savedEvidence.sha256 != candidateEvidence.sha256 ||
            candidateLink.expenseId != input.expense.id ||
            candidateLink.evidenceId != candidateEvidence.id
        ) {
            throw PrepaidValidationException(PrepaidValidationFailure.EvidenceContentMismatch)
        }
    }

    private fun negateExact(value: Long): Long = try {
        Math.negateExact(value)
    } catch (_: ArithmeticException) {
        throw PrepaidValidationException(PrepaidValidationFailure.ArithmeticOverflow)
    }

    private suspend fun insertIdempotently(
        candidate: PrepaidTransactionRecord,
        matches: (PrepaidTransactionRecord, PrepaidTransactionRecord) -> Boolean
    ): PrepaidWriteResult {
        val existing = transactionDao.getByOperationKey(candidate.operationKey)
        if (existing != null) {
            if (!matches(existing, candidate)) {
                throw PrepaidValidationException(
                    PrepaidValidationFailure.DuplicateOperationKey
                )
            }
            return PrepaidWriteResult(
                transaction = existing,
                balance = transactionDao.getBalance(existing.accountId),
                wasAlreadyApplied = true
            )
        }
        validateTransactionForInsert(candidate)
        transactionDao.insert(candidate)
        return PrepaidWriteResult(
            transaction = candidate,
            balance = transactionDao.getBalance(candidate.accountId),
            wasAlreadyApplied = false
        )
    }

    private fun validateDate(value: String) {
        try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw PrepaidValidationException(PrepaidValidationFailure.InvalidDate)
        }
    }
}

internal fun sameExpenseBusinessOperation(
    existing: ExpenseRecord,
    candidate: ExpenseRecord
): Boolean =
    existing.id == candidate.id &&
        existing.expenseDate == candidate.expenseDate &&
        existing.category == candidate.category &&
        existing.supplierName == candidate.supplierName &&
        existing.amount == candidate.amount &&
        existing.paymentMethod == candidate.paymentMethod &&
        existing.memo == candidate.memo &&
        existing.receiptId == candidate.receiptId &&
        existing.sourceType == candidate.sourceType

internal fun samePrepaidBusinessOperation(
    existing: PrepaidTransactionRecord,
    candidate: PrepaidTransactionRecord
): Boolean =
    existing.operationKey == candidate.operationKey &&
        existing.accountId == candidate.accountId &&
        existing.transactionDate == candidate.transactionDate &&
        existing.transactionType == candidate.transactionType &&
        existing.balanceDelta == candidate.balanceDelta &&
        existing.expenseId == candidate.expenseId &&
        existing.chargeSource == candidate.chargeSource &&
        existing.reversalOfTransactionId == candidate.reversalOfTransactionId &&
        existing.memo == candidate.memo
