package com.warun.accounting.data.local

import androidx.room.ColumnInfo
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
    val accountantFeeExpense: Long,
    val miscellaneousExpense: Long,
    val otherExpense: Long,
    val openingCash: Long,
    val actualClosingCash: Long,
    val customerCount: Int,
    val groupCount: Int,
    val memo: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0")
    val hasActualClosingCash: Boolean = false
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

object EvidenceRecordState {
    const val Pending = "pending"
    const val Stored = "stored"
}

@Entity(
    tableName = "evidence_records",
    indices = [
        Index(value = ["captureId"], unique = true),
        Index(value = ["storedUri"], unique = true)
    ]
)
data class EvidenceRecord(
    @PrimaryKey val id: String,
    val captureId: String,
    val storedUri: String,
    val byteSize: Long,
    val sha256: String,
    val state: String,
    val createdAt: Long,
    val storedAt: Long?,
    val updatedAt: Long,
    val mediaType: String = "image/jpeg"
)

@Entity(
    tableName = "fixed_cost_receipt_applications",
    foreignKeys = [
        ForeignKey(
            entity = ReceiptRecord::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = DailyReport::class,
            parentColumns = ["id"],
            childColumns = ["dailyReportId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["receiptId"], unique = true),
        Index(value = ["dailyReportId", "fixedCostType"], unique = true)
    ]
)
data class FixedCostReceiptApplicationRecord(
    @PrimaryKey val applicationId: String,
    val receiptId: String,
    val dailyReportId: String,
    val fixedCostType: String,
    val paymentMethod: String,
    val appliedAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "fixed_cost_evidence_links",
    primaryKeys = ["applicationId", "evidenceId"],
    foreignKeys = [
        ForeignKey(
            entity = FixedCostReceiptApplicationRecord::class,
            parentColumns = ["applicationId"],
            childColumns = ["applicationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EvidenceRecord::class,
            parentColumns = ["id"],
            childColumns = ["evidenceId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["applicationId", "sortOrder"], unique = true),
        Index(value = ["evidenceId"], unique = true)
    ]
)
data class FixedCostEvidenceLinkRecord(
    val applicationId: String,
    val evidenceId: String,
    val sortOrder: Int,
    val linkedAt: Long
)

@Entity(
    tableName = "expense_evidence_links",
    primaryKeys = ["expenseId", "evidenceId"],
    foreignKeys = [
        ForeignKey(
            entity = ExpenseRecord::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EvidenceRecord::class,
            parentColumns = ["id"],
            childColumns = ["evidenceId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [Index(value = ["evidenceId"], unique = true)]
)
data class ExpenseEvidenceLinkRecord(
    val expenseId: String,
    val evidenceId: String,
    val linkedAt: Long
)

data class ExpenseEvidenceRecord(
    val expenseId: String,
    val evidenceId: String,
    val captureId: String,
    val storedUri: String,
    val byteSize: Long,
    val sha256: String,
    val createdAt: Long,
    val storedAt: Long
)

@Entity(
    tableName = "supplier_candidates",
    indices = [Index(value = ["category", "name"], unique = true)]
)
data class SupplierCandidateRecord(
    @PrimaryKey val id: String,
    val category: String,
    val name: String,
    val paymentMethod: String?,
    val isDefault: Boolean,
    val isHidden: Boolean,
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

@Entity(
    tableName = "electronic_submission_records",
    indices = [
        Index(value = ["targetMonth"]),
        Index(value = ["generatedAt"])
    ]
)
data class ElectronicSubmissionRecord(
    @PrimaryKey val id: String,
    val targetMonth: String,
    val generatedAt: Long,
    val dailyReportFileName: String?,
    val expenseDetailFileName: String?,
    val receiptPdfFileName: String?,
    val status: String,
    val submittedAt: Long?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)

object ElectronicSubmissionStatus {
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
