package com.warun.accounting.data.local

import androidx.room.Entity
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
