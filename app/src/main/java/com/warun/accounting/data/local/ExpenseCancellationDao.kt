package com.warun.accounting.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseCancellationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(cancellation: ExpenseCancellationRecord)

    @Query("SELECT * FROM expense_cancellations WHERE expenseId = :expenseId")
    suspend fun getByExpenseId(expenseId: String): ExpenseCancellationRecord?

    @Query("SELECT * FROM expense_cancellations WHERE operationKey = :operationKey")
    suspend fun getByOperationKey(operationKey: String): ExpenseCancellationRecord?

    @Query(
        """
        SELECT * FROM expense_cancellations
        WHERE originalPurchaseTransactionId = :transactionId
        """
    )
    suspend fun getByOriginalPurchaseTransactionId(
        transactionId: String
    ): ExpenseCancellationRecord?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM expense_cancellations WHERE expenseId = :expenseId
        )
        """
    )
    suspend fun existsByExpenseId(expenseId: String): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM expense_cancellations WHERE operationKey = :operationKey
        )
        """
    )
    suspend fun existsByOperationKey(operationKey: String): Boolean

    @Query(
        """
        SELECT cancellation.*
        FROM expense_cancellations AS cancellation
        INNER JOIN expense_records AS expense
          ON expense.id = cancellation.expenseId
        WHERE expense.expenseDate = :expenseDate
        ORDER BY cancellation.cancelledAt DESC, cancellation.expenseId ASC
        """
    )
    fun observeByExpenseDate(expenseDate: String): Flow<List<ExpenseCancellationRecord>>
}
