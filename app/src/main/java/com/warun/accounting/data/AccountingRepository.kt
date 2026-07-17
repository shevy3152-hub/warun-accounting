package com.warun.accounting.data

import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.ReceiptRecord
import kotlinx.coroutines.flow.Flow

interface AccountingRepository {
    fun observeDailyReports(): Flow<List<DailyReport>>
    fun observeReceipts(): Flow<List<ReceiptRecord>>
    fun observeExpenseRecords(): Flow<List<ExpenseRecord>>
    fun observeExpenseRecordsByDateAndCategory(expenseDate: String, category: String): Flow<List<ExpenseRecord>>
    fun observeExpenseTotalByDateAndCategory(expenseDate: String, category: String): Flow<Long>
    fun observeMonthlySubmissions(): Flow<List<MonthlySubmission>>
    fun observeAppSettings(): Flow<AppSettings?>
    suspend fun saveDailyReport(report: DailyReport)
    suspend fun saveReceipt(receipt: ReceiptRecord)
    suspend fun saveExpenseRecord(expense: ExpenseRecord)
    suspend fun deleteExpenseRecord(expense: ExpenseRecord)
    suspend fun deleteReceipt(receipt: ReceiptRecord)
    suspend fun saveMonthlySubmission(submission: MonthlySubmission)
    suspend fun saveAppSettings(settings: AppSettings)
    suspend fun deleteDailyReport(report: DailyReport)
}