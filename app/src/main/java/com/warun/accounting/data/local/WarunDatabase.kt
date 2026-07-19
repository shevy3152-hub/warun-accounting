package com.warun.accounting.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DailyReport::class,
        ReceiptRecord::class,
        ExpenseRecord::class,
        SupplierCandidateRecord::class,
        MonthlySubmission::class,
        AppSettings::class
    ],
    version = 10,
    exportSchema = true
)
abstract class WarunDatabase : RoomDatabase() {
    abstract fun warunDao(): WarunDao
}
