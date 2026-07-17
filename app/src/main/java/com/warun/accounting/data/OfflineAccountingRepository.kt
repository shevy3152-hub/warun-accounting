package com.warun.accounting.data

import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
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

    override fun observeReceiptsByDateAndCategory(purchaseDate: String, category: String): Flow<List<ReceiptRecord>> =
        dao.observeReceiptsByDateAndCategory(purchaseDate, category)

    override fun observeReceiptTotalByDateAndCategory(purchaseDate: String, category: String): Flow<Long> =
        dao.observeReceiptTotalByDateAndCategory(purchaseDate, category)

    override fun observeMonthlySubmissions(): Flow<List<MonthlySubmission>> = dao.observeMonthlySubmissions()

    override fun observeAppSettings(): Flow<AppSettings?> = dao.observeAppSettings()

    override suspend fun saveDailyReport(report: DailyReport) = dao.insertDailyReport(report)

    override suspend fun saveReceipt(receipt: ReceiptRecord) = dao.insertReceipt(receipt)

    override suspend fun deleteReceipt(receipt: ReceiptRecord) = dao.deleteReceipt(receipt)

    override suspend fun saveMonthlySubmission(submission: MonthlySubmission) = dao.upsertMonthlySubmission(submission)

    override suspend fun saveAppSettings(settings: AppSettings) = dao.upsertAppSettings(settings)

    override suspend fun deleteDailyReport(report: DailyReport) = dao.deleteDailyReport(report)
}
