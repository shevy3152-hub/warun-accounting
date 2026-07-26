package com.warun.accounting.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object PrepaidAccountType {
    const val Majica = "MAJICA"
    const val AuPayPrepaid = "AU_PAY_PREPAID"

    val Supported = setOf(Majica, AuPayPrepaid)
}

object PrepaidAccountId {
    const val Majica = "prepaid-majica"
    const val AuPayPrepaid = "prepaid-au-pay"
}

object PrepaidTransactionType {
    const val Charge = "CHARGE"
    const val Purchase = "PURCHASE"
    const val Adjustment = "ADJUSTMENT"
    const val Refund = "REFUND"
    const val Reversal = "REVERSAL"

    val Supported = setOf(Charge, Purchase, Adjustment, Refund, Reversal)
}

object PrepaidChargeSource {
    const val Cash = "CASH"
    const val BankAccount = "BANK_ACCOUNT"
    const val CreditCard = "CREDIT_CARD"
    const val OtherNonCash = "OTHER_NON_CASH"

    val Supported = setOf(Cash, BankAccount, CreditCard, OtherNonCash)
}

@Entity(
    tableName = "prepaid_accounts",
    indices = [Index(value = ["type"], unique = true)]
)
data class PrepaidAccountRecord(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "prepaid_transactions",
    foreignKeys = [
        ForeignKey(
            entity = PrepaidAccountRecord::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ExpenseRecord::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = PrepaidTransactionRecord::class,
            parentColumns = ["id"],
            childColumns = ["reversalOfTransactionId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["operationKey"], unique = true),
        Index(value = ["accountId", "transactionDate"]),
        Index(value = ["expenseId"]),
        Index(value = ["reversalOfTransactionId"], unique = true)
    ]
)
data class PrepaidTransactionRecord(
    @PrimaryKey val id: String,
    val accountId: String,
    val transactionDate: String,
    val transactionType: String,
    val balanceDelta: Long,
    val expenseId: String?,
    val chargeSource: String?,
    val reversalOfTransactionId: String?,
    val operationKey: String,
    @ColumnInfo(defaultValue = "''")
    val memo: String = "",
    val createdAt: Long
)

@Entity(
    tableName = "expense_prepaid_links",
    foreignKeys = [
        ForeignKey(
            entity = ExpenseRecord::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PrepaidTransactionRecord::class,
            parentColumns = ["id"],
            childColumns = ["purchaseTransactionId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index(value = ["purchaseTransactionId"], unique = true)]
)
data class ExpensePrepaidLinkRecord(
    @PrimaryKey val expenseId: String,
    val purchaseTransactionId: String,
    val linkedAt: Long,
    val updatedAt: Long
)

data class PrepaidAccountBalance(
    val accountId: String,
    val balance: Long
)

object InitialPrepaidAccounts {
    val Records = listOf(
        PrepaidAccountRecord(
            id = PrepaidAccountId.Majica,
            type = PrepaidAccountType.Majica,
            name = "majica",
            isActive = true,
            createdAt = 0L,
            updatedAt = 0L
        ),
        PrepaidAccountRecord(
            id = PrepaidAccountId.AuPayPrepaid,
            type = PrepaidAccountType.AuPayPrepaid,
            name = "au PAY プリペイド",
            isActive = true,
            createdAt = 0L,
            updatedAt = 0L
        )
    )
}
