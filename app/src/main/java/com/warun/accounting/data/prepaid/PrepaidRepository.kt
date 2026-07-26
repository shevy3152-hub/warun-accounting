package com.warun.accounting.data.prepaid

import androidx.room.withTransaction
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
    suspend fun getAccount(accountId: String): PrepaidAccountRecord?
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

    override suspend fun getAccount(accountId: String): PrepaidAccountRecord? =
        accountDao.getById(accountId)

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
