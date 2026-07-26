package com.warun.accounting.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DailyReport::class,
        ReceiptRecord::class,
        ExpenseRecord::class,
        EvidenceRecord::class,
        ExpenseEvidenceLinkRecord::class,
        SupplierCandidateRecord::class,
        MonthlySubmission::class,
        AppSettings::class,
        PrepaidAccountRecord::class,
        PrepaidTransactionRecord::class,
        ExpensePrepaidLinkRecord::class
    ],
    version = 12,
    exportSchema = true
)
abstract class WarunDatabase : RoomDatabase() {
    abstract fun warunDao(): WarunDao
    abstract fun prepaidAccountDao(): PrepaidAccountDao
    abstract fun prepaidTransactionDao(): PrepaidTransactionDao
    abstract fun expensePrepaidLinkDao(): ExpensePrepaidLinkDao
}
