package com.warun.accounting.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WarunDao {
    @Query("SELECT * FROM daily_reports ORDER BY reportDate DESC")
    fun observeDailyReports(): Flow<List<DailyReport>>

    @Query("SELECT * FROM daily_reports WHERE reportDate BETWEEN :from AND :to ORDER BY reportDate DESC")
    fun observeDailyReportsBetween(from: String, to: String): Flow<List<DailyReport>>

    @Query("SELECT * FROM store_settings WHERE id = 1")
    fun observeAppSettings(): Flow<AppSettings?>

    @Query("SELECT * FROM receipts ORDER BY COALESCE(purchaseDate, capturedDate, '') DESC, registeredAt DESC")
    fun observeReceipts(): Flow<List<ReceiptRecord>>

    @Query("SELECT * FROM expense_records ORDER BY expenseDate DESC, createdAt DESC")
    fun observeExpenseRecords(): Flow<List<ExpenseRecord>>

    @Query("SELECT * FROM expense_records WHERE expenseDate = :expenseDate AND category = :category ORDER BY createdAt DESC")
    fun observeExpenseRecordsByDateAndCategory(expenseDate: String, category: String): Flow<List<ExpenseRecord>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM expense_records WHERE expenseDate = :expenseDate AND category = :category")
    fun observeExpenseTotalByDateAndCategory(expenseDate: String, category: String): Flow<Long>

    @Query("SELECT * FROM monthly_submissions ORDER BY targetMonth DESC")
    fun observeMonthlySubmissions(): Flow<List<MonthlySubmission>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyReport(report: DailyReport)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: ReceiptRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpenseRecord(expense: ExpenseRecord)

    @Upsert
    suspend fun upsertMonthlySubmission(submission: MonthlySubmission)

    @Upsert
    suspend fun upsertAppSettings(settings: AppSettings)

    @Delete
    suspend fun deleteDailyReport(report: DailyReport)

    @Delete
    suspend fun deleteReceipt(receipt: ReceiptRecord)

    @Delete
    suspend fun deleteExpenseRecord(expense: ExpenseRecord)
}