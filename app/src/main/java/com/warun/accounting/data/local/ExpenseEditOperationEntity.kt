package com.warun.accounting.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object ExpenseEditOperationStatus {
    const val Started = "STARTED"
    const val Completed = "COMPLETED"

    val Supported = setOf(Started, Completed)
}

@Entity(
    tableName = "expense_edit_operations",
    indices = [Index(value = ["expenseId"])]
)
data class ExpenseEditOperationRecord(
    @PrimaryKey val operationKey: String,
    val expenseId: String,
    val requestFingerprint: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val resultPaymentMethod: String?,
    val resultPrepaidAccountId: String?,
    val resultAmount: Long?
) {
    init {
        require(operationKey.isNotBlank() && operationKey == operationKey.trim())
        require(expenseId.isNotBlank() && expenseId == expenseId.trim())
        require(requestFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(status in ExpenseEditOperationStatus.Supported)
        require(createdAt >= 0L && updatedAt >= createdAt)
        when (status) {
            ExpenseEditOperationStatus.Started -> require(
                completedAt == null &&
                    resultPaymentMethod == null &&
                    resultPrepaidAccountId == null &&
                    resultAmount == null
            )
            ExpenseEditOperationStatus.Completed -> require(
                completedAt != null &&
                    completedAt >= createdAt &&
                    updatedAt == completedAt &&
                    !resultPaymentMethod.isNullOrBlank() &&
                    resultAmount != null &&
                    resultAmount > 0L
            )
        }
    }
}
