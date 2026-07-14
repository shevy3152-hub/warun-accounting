package com.warun.accounting.data

import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.ReceiptRecord
import kotlinx.coroutines.flow.Flow

interface AccountingRepository {
    fun observeDailyReports(): Flow<List<DailyReport>>
    fun observeReceipts(): Flow<List<ReceiptRecord>>
    fun observeMonthlySubmissions(): Flow<List<MonthlySubmission>>
    fun observeAppSettings(): Flow<AppSettings?>
    suspend fun saveDailyReport(report: DailyReport)
    suspend fun saveReceipt(receipt: ReceiptRecord)
    suspend fun saveMonthlySubmission(submission: MonthlySubmission)
    suspend fun saveAppSettings(settings: AppSettings)
    suspend fun deleteDailyReport(report: DailyReport)
}
