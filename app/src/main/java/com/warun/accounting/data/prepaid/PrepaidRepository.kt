package com.warun.accounting.data.prepaid

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
    suspend fun getAccount(accountId: String): PrepaidAccountRecord?
    suspend fun getMajicaAccount(): PrepaidAccountRecord?
    suspend fun getAuPayPrepaidAccount(): PrepaidAccountRecord?
    suspend fun getBalance(accountId: String): Long
    suspend fun validateTransactionForInsert(transaction: PrepaidTransactionRecord)
    suspend fun validateLinkForInsert(
        link: ExpensePrepaidLinkRecord,
        expectedAccountId: String
    )
}

class OfflinePrepaidRepository @Inject constructor(
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
}
