package com.warun.accounting.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PrepaidAccountDao {
    @Query("SELECT * FROM prepaid_accounts WHERE isActive = 1 ORDER BY createdAt ASC, id ASC")
    fun observeActiveAccounts(): Flow<List<PrepaidAccountRecord>>

    @Query("SELECT * FROM prepaid_accounts ORDER BY createdAt ASC, id ASC")
    fun observeAllAccounts(): Flow<List<PrepaidAccountRecord>>

    @Query("SELECT * FROM prepaid_accounts WHERE id = :accountId")
    suspend fun getById(accountId: String): PrepaidAccountRecord?

    @Query("SELECT * FROM prepaid_accounts WHERE type = :type")
    suspend fun getByType(type: String): PrepaidAccountRecord?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: PrepaidAccountRecord)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(account: PrepaidAccountRecord): Long
}

@Dao
interface PrepaidTransactionDao {
    @Query(
        """
        SELECT * FROM prepaid_transactions
        ORDER BY transactionDate DESC, createdAt DESC, id DESC
        """
    )
    fun observeAll(): Flow<List<PrepaidTransactionRecord>>

    @Query(
        """
        SELECT * FROM prepaid_transactions
        WHERE accountId = :accountId
        ORDER BY transactionDate DESC, createdAt DESC, id DESC
        """
    )
    fun observeByAccount(accountId: String): Flow<List<PrepaidTransactionRecord>>

    @Query(
        """
        SELECT * FROM prepaid_transactions
        WHERE accountId = :accountId
          AND transactionDate BETWEEN :from AND :to
        ORDER BY transactionDate DESC, createdAt DESC, id DESC
        """
    )
    fun observeByAccountBetween(
        accountId: String,
        from: String,
        to: String
    ): Flow<List<PrepaidTransactionRecord>>

    @Query(
        """
        SELECT * FROM prepaid_transactions
        WHERE expenseId = :expenseId
        ORDER BY transactionDate DESC, createdAt DESC, id DESC
        """
    )
    fun observeByExpense(expenseId: String): Flow<List<PrepaidTransactionRecord>>

    @Query("SELECT * FROM prepaid_transactions WHERE id = :transactionId")
    suspend fun getById(transactionId: String): PrepaidTransactionRecord?

    @Query("SELECT * FROM prepaid_transactions WHERE operationKey = :operationKey")
    suspend fun getByOperationKey(operationKey: String): PrepaidTransactionRecord?

    @Query(
        """
        SELECT * FROM prepaid_transactions
        WHERE reversalOfTransactionId = :transactionId
        """
    )
    suspend fun getByReversalOfTransactionId(
        transactionId: String
    ): PrepaidTransactionRecord?

    @Query(
        """
        SELECT COALESCE(SUM(balanceDelta), 0)
        FROM prepaid_transactions
        WHERE accountId = :accountId
        """
    )
    suspend fun getBalance(accountId: String): Long

    @Query(
        """
        SELECT account.id AS accountId,
               COALESCE(SUM(transaction_record.balanceDelta), 0) AS balance
        FROM prepaid_accounts AS account
        LEFT JOIN prepaid_transactions AS transaction_record
          ON transaction_record.accountId = account.id
        GROUP BY account.id
        ORDER BY account.createdAt ASC, account.id ASC
        """
    )
    fun observeAllAccountBalances(): Flow<List<PrepaidAccountBalance>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: PrepaidTransactionRecord)
}

@Dao
interface ExpensePrepaidLinkDao {
    @Query("SELECT * FROM expense_prepaid_links ORDER BY linkedAt ASC, expenseId ASC")
    fun observeAll(): Flow<List<ExpensePrepaidLinkRecord>>

    @Query("SELECT * FROM expense_prepaid_links WHERE expenseId = :expenseId")
    suspend fun getByExpenseId(expenseId: String): ExpensePrepaidLinkRecord?

    @Query(
        """
        SELECT * FROM expense_prepaid_links
        WHERE purchaseTransactionId = :purchaseTransactionId
        """
    )
    suspend fun getByPurchaseTransactionId(
        purchaseTransactionId: String
    ): ExpensePrepaidLinkRecord?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(link: ExpensePrepaidLinkRecord)

    @Query("DELETE FROM expense_prepaid_links WHERE expenseId = :expenseId")
    suspend fun deleteByExpenseId(expenseId: String): Int
}
