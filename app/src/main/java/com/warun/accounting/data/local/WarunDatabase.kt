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
        AppSettings::class
    ],
    version = 11,
    exportSchema = true
)
abstract class WarunDatabase : RoomDatabase() {
    abstract fun warunDao(): WarunDao
}
