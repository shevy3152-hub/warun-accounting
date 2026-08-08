package com.warun.accounting.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "expense_cancellations",
    foreignKeys = [
        ForeignKey(
            entity = ExpenseRecord::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PrepaidTransactionRecord::class,
            parentColumns = ["id"],
            childColumns = ["originalPurchaseTransactionId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PrepaidTransactionRecord::class,
            parentColumns = ["id"],
            childColumns = ["reversalTransactionId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["operationKey"], unique = true),
        Index(value = ["originalPurchaseTransactionId"], unique = true),
        Index(value = ["reversalTransactionId"], unique = true)
    ]
)
data class ExpenseCancellationRecord(
    @PrimaryKey val expenseId: String,
    val operationKey: String,
    val requestFingerprint: String,
    val originalPurchaseTransactionId: String?,
    val reversalTransactionId: String?,
    val cancellationDate: String,
    val cancelledAt: Long,
    val reason: String?
)
