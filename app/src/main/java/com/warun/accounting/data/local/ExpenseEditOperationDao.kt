package com.warun.accounting.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExpenseEditOperationDao {
    @Query("SELECT * FROM expense_edit_operations WHERE operationKey = :operationKey")
    suspend fun getByOperationKey(operationKey: String): ExpenseEditOperationRecord?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM expense_edit_operations WHERE operationKey = :operationKey
        )
        """
    )
    suspend fun exists(operationKey: String): Boolean

    @Query(
        """
        SELECT * FROM expense_edit_operations
        WHERE expenseId = :expenseId
        ORDER BY createdAt DESC, operationKey DESC
        """
    )
    suspend fun getByExpenseId(expenseId: String): List<ExpenseEditOperationRecord>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(operation: ExpenseEditOperationRecord)

    @Query(
        """
        UPDATE expense_edit_operations
        SET status = 'COMPLETED',
            updatedAt = :completedAt,
            completedAt = :completedAt,
            resultPaymentMethod = :resultPaymentMethod,
            resultPrepaidAccountId = :resultPrepaidAccountId,
            resultAmount = :resultAmount
        WHERE operationKey = :operationKey
          AND requestFingerprint = :requestFingerprint
          AND status = 'STARTED'
        """
    )
    suspend fun markCompleted(
        operationKey: String,
        requestFingerprint: String,
        completedAt: Long,
        resultPaymentMethod: String,
        resultPrepaidAccountId: String?,
        resultAmount: Long
    ): Int
}
