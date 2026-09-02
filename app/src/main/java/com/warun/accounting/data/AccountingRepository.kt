package com.warun.accounting.data

import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.ExpenseEvidenceLinkRecord
import com.warun.accounting.data.local.ExpenseEvidenceRecord
import com.warun.accounting.data.local.ExpenseVisibilityRecord
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.SupplierCandidateRecord
import com.warun.accounting.data.fixedcost.FixedCostDetailSnapshot
import com.warun.accounting.data.fixedcost.FixedCostEvidenceStatus
import kotlinx.coroutines.flow.Flow

interface AccountingRepository {
    fun observeDailyReports(): Flow<List<DailyReport>>
    fun observeReceipts(): Flow<List<ReceiptRecord>>
    fun observeExpenseRecords(): Flow<List<ExpenseRecord>>
    fun observeExpenseVisibilityRecords(): Flow<List<ExpenseVisibilityRecord>>
    fun observeCancelledExpenseRecordsForAuditByDate(expenseDate: String): Flow<List<ExpenseRecord>>
    fun observeStoredExpenseEvidence(): Flow<List<ExpenseEvidenceRecord>>
    fun observeFixedCostEvidenceStatuses(): Flow<List<FixedCostEvidenceStatus>>
    fun observeExpenseRecordsByDateAndCategory(expenseDate: String, category: String): Flow<List<ExpenseRecord>>
    fun observeExpenseTotalByDateAndCategory(expenseDate: String, category: String): Flow<Long>
    fun observeSupplierCandidates(): Flow<List<SupplierCandidateRecord>>
    fun observeVisibleSupplierCandidatesByCategory(category: String): Flow<List<SupplierCandidateRecord>>
    fun observeMonthlySubmissions(): Flow<List<MonthlySubmission>>
    fun observeAppSettings(): Flow<AppSettings?>
    suspend fun getActiveExpenseRecord(expenseId: String): ExpenseRecord?
    suspend fun getExpenseRecordForAudit(expenseId: String): ExpenseRecord?
    suspend fun getAllExpenseRecordsForEvidenceRecovery(): List<ExpenseRecord>
    suspend fun saveDailyReport(report: DailyReport)
    suspend fun saveDailyReportWithExpense(report: DailyReport, expense: ExpenseRecord?)
    suspend fun saveReceipt(receipt: ReceiptRecord)
    suspend fun saveReceiptWithExpense(receipt: ReceiptRecord, expense: ExpenseRecord?)
    suspend fun saveExpenseRecord(expense: ExpenseRecord)
    suspend fun saveExpenseWithEvidence(
        expense: ExpenseRecord,
        evidence: EvidenceRecord,
        link: ExpenseEvidenceLinkRecord
    )
    suspend fun saveDailyReportWithExpenseAndEvidence(
        report: DailyReport,
        expense: ExpenseRecord,
        evidence: EvidenceRecord,
        link: ExpenseEvidenceLinkRecord
    )
    suspend fun finalizeExpenseEvidence(
        expenseId: String,
        evidence: EvidenceRecord,
        link: ExpenseEvidenceLinkRecord
    )
    suspend fun hasExpenseEvidenceLink(
        expenseId: String,
        evidenceId: String,
        captureId: String
    ): Boolean
    suspend fun deleteExpenseRecord(expense: ExpenseRecord)
    suspend fun deleteReceipt(receipt: ReceiptRecord)
    suspend fun deleteUnconfirmedReceipt(receiptId: String): ReceiptDeletionResult
    suspend fun saveSupplierCandidate(candidate: SupplierCandidateRecord)
    suspend fun saveMonthlySubmission(submission: MonthlySubmission)
    suspend fun saveAppSettings(settings: AppSettings)
    suspend fun deleteDailyReport(report: DailyReport)
    suspend fun getFixedCostDetail(
        receiptId: String,
        dailyReportId: String?,
        fixedCostType: String
    ): FixedCostDetailSnapshot
}
