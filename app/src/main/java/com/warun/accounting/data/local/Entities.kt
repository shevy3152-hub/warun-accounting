package com.warun.accounting.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "daily_reports")
data class DailyReport(
    @PrimaryKey val id: String,
    val reportDate: String,
    val status: String,
    val authorName: String?,
    val cashSales: Long,
    val cardSales: Long,
    val qrSales: Long,
    val accountsReceivableSales: Long,
    val otherSales: Long,
    val foodPurchases: Long,
    val alcoholPurchases: Long,
    val consumablesExpense: Long,
    val utilitiesExpense: Long,
    val electricityExpense: Long,
    val gasExpense: Long,
    val waterExpense: Long,
    val communicationExpense: Long,
    val rentExpense: Long,
    val miscellaneousExpense: Long,
    val otherExpense: Long,
    val openingCash: Long,
    val actualClosingCash: Long,
    val customerCount: Int,
    val groupCount: Int,
    val memo: String?,
    val createdAt: Long,
    val updatedAt: Long
)

object DailyReportStatus {
    const val Draft = "draft"
    const val Completed = "completed"
}

object ExpenseCategory {
    const val FoodPurchase = "food_purchase"
    const val AlcoholPurchase = "alcohol_purchase"
    const val Consumables = "consumables"
    const val OtherExpense = "other_expense"
    const val VehicleTransport = "vehicle_transport"
}

object ExpenseSourceType {
    const val Manual = "manual"
    const val Receipt = "receipt"
    const val LegacyMigration = "legacy_migration"
}

@Entity(tableName = "receipts")
data class ReceiptRecord(
    @PrimaryKey val id: String,
    val purchaseDate: String?,
    val capturedDate: String?,
    val registeredAt: Long,
    val storeName: String?,
    val totalAmount: Long,
    val taxAmount: Long,
    val registrationNumber: String?,
    val expenseCategory: String?,
    val isConfirmed: Boolean,
    val memo: String?,
    val updatedAt: Long
)

@Entity(
    tableName = "expense_records",
    foreignKeys = [
        ForeignKey(
            entity = ReceiptRecord::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("expenseDate"),
        Index("category"),
        Index("receiptId")
    ]
)
data class ExpenseRecord(
    @PrimaryKey val id: String,
    val expenseDate: String,
    val category: String,
    val supplierName: String?,
    val amount: Long,
    val paymentMethod: String?,
    val memo: String?,
    val receiptId: String?,
    val sourceType: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "monthly_submissions")
data class MonthlySubmission(
    @PrimaryKey val targetMonth: String,
    val status: String,
    val submittedAt: Long?,
    val updatedAt: Long
)

object MonthlySubmissionStatus {
    const val NotSubmitted = "not_submitted"
    const val Submitted = "submitted"
}

@Entity(tableName = "store_settings")
data class AppSettings(
    @PrimaryKey val id: Long = 1,
    val storeName: String,
    val ownerName: String,
    val address: String,
    val accountantNote: String,
    val useCashPayment: Boolean = true,
    val useCardPayment: Boolean = false,
    val useQrPayment: Boolean = false,
    val useAccountsReceivablePayment: Boolean = false,
    val useOtherPayment: Boolean = false
)