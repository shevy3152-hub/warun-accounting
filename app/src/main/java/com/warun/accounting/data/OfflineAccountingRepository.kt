package com.warun.accounting.data

import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.WarunDao
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class OfflineAccountingRepository @Inject constructor(
    private val dao: WarunDao
) : AccountingRepository {
    override fun observeDailyReports(): Flow<List<DailyReport>> = dao.observeDailyReports()

    override fun observeReceipts(): Flow<List<ReceiptRecord>> = dao.observeReceipts()

    override fun observeExpenseRecords(): Flow<List<ExpenseRecord>> = dao.observeExpenseRecords()

    override fun observeExpenseRecordsByDateAndCategory(expenseDate: String, category: String): Flow<List<ExpenseRecord>> =
        dao.observeExpenseRecordsByDateAndCategory(expenseDate, category)

    override fun observeExpenseTotalByDateAndCategory(expenseDate: String, category: String): Flow<Long> =
        dao.observeExpenseTotalByDateAndCategory(expenseDate, category)

    override fun observeMonthlySubmissions(): Flow<List<MonthlySubmission>> = dao.observeMonthlySubmissions()

    override fun observeAppSettings(): Flow<AppSettings?> = dao.observeAppSettings()

    override suspend fun saveDailyReport(report: DailyReport) = dao.insertDailyReport(report)

    override suspend fun saveReceipt(receipt: ReceiptRecord) = dao.insertReceipt(receipt)

    override suspend fun saveExpenseRecord(expense: ExpenseRecord) = dao.insertExpenseRecord(expense)

    override suspend fun deleteExpenseRecord(expense: ExpenseRecord) = dao.deleteExpenseRecord(expense)

    override suspend fun deleteReceipt(receipt: ReceiptRecord) = dao.deleteReceipt(receipt)

    override suspend fun saveMonthlySubmission(submission: MonthlySubmission) = dao.upsertMonthlySubmission(submission)

    override suspend fun saveAppSettings(settings: AppSettings) = dao.upsertAppSettings(settings)

    override suspend fun deleteDailyReport(report: DailyReport) = dao.deleteDailyReport(report)
}