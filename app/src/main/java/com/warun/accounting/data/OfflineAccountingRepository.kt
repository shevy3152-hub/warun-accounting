package com.warun.accounting.data

import com.warun.accounting.data.local.AppSettings
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.ExpenseEvidenceLinkRecord
import com.warun.accounting.data.local.ExpenseEvidenceRecord
import com.warun.accounting.data.local.ExpenseVisibilityRecord
import com.warun.accounting.data.local.ExpensePrepaidLinkDao
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.SupplierCandidateRecord
import com.warun.accounting.data.local.WarunDao
import com.warun.accounting.data.fixedcost.ExistingAmountState
import com.warun.accounting.data.fixedcost.FixedCostDetailSnapshot
import com.warun.accounting.data.fixedcost.FixedCostEvidenceStatus
import com.warun.accounting.util.PaymentMethodPrepaid
import com.warun.accounting.util.normalizePaymentMethod
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException

class OfflineAccountingRepository @Inject constructor(
    private val dao: WarunDao,
    private val expensePrepaidLinkDao: ExpensePrepaidLinkDao
) : AccountingRepository {
    override fun observeDailyReports(): Flow<List<DailyReport>> = dao.observeDailyReports()

    override fun observeReceipts(): Flow<List<ReceiptRecord>> = dao.observeReceipts()

    override fun observeExpenseRecords(): Flow<List<ExpenseRecord>> = dao.observeExpenseRecords()

    override fun observeExpenseVisibilityRecords(): Flow<List<ExpenseVisibilityRecord>> =
        dao.observeExpenseVisibilityRecords()

    override fun observeCancelledExpenseRecordsForAuditByDate(
        expenseDate: String
    ): Flow<List<ExpenseRecord>> =
        dao.observeCancelledExpenseRecordsForAuditByDate(expenseDate)

    override fun observeStoredExpenseEvidence(): Flow<List<ExpenseEvidenceRecord>> =
        dao.observeStoredExpenseEvidence()

    override fun observeFixedCostEvidenceStatuses(): Flow<List<FixedCostEvidenceStatus>> =
        dao.observeFixedCostEvidenceStatuses().map { rows ->
            rows.groupBy { it.dailyReportId to it.fixedCostType }.map { (key, items) ->
                FixedCostEvidenceStatus(
                    dailyReportId = key.first,
                    fixedCostType = key.second,
                    applicationId = items.first().applicationId,
                    evidence = items.mapNotNull { row ->
                        val evidenceId = row.evidenceId ?: return@mapNotNull null
                        val captureId = row.captureId ?: return@mapNotNull null
                        val storedUri = row.storedUri ?: return@mapNotNull null
                        val byteSize = row.byteSize ?: return@mapNotNull null
                        val sha256 = row.sha256 ?: return@mapNotNull null
                        val createdAt = row.createdAt ?: return@mapNotNull null
                        val storedAt = row.storedAt ?: return@mapNotNull null
                        EvidenceRecord(
                            id = evidenceId,
                            captureId = captureId,
                            storedUri = storedUri,
                            byteSize = byteSize,
                            sha256 = sha256,
                            state = com.warun.accounting.data.local.EvidenceRecordState.Stored,
                            createdAt = createdAt,
                            storedAt = storedAt,
                            updatedAt = storedAt,
                            mediaType = row.mediaType ?: "image/jpeg"
                        )
                    }
                )
            }
        }

    override fun observeExpenseRecordsByDateAndCategory(expenseDate: String, category: String): Flow<List<ExpenseRecord>> =
        dao.observeExpenseRecordsByDateAndCategory(expenseDate, category)

    override fun observeExpenseTotalByDateAndCategory(expenseDate: String, category: String): Flow<Long> =
        dao.observeExpenseTotalByDateAndCategory(expenseDate, category)

    override fun observeSupplierCandidates(): Flow<List<SupplierCandidateRecord>> = dao.observeSupplierCandidates()

    override fun observeVisibleSupplierCandidatesByCategory(category: String): Flow<List<SupplierCandidateRecord>> =
        dao.observeVisibleSupplierCandidatesByCategory(category)

    override fun observeMonthlySubmissions(): Flow<List<MonthlySubmission>> = dao.observeMonthlySubmissions()

    override fun observeAppSettings(): Flow<AppSettings?> = dao.observeAppSettings()

    override suspend fun getActiveExpenseRecord(expenseId: String): ExpenseRecord? =
        dao.getActiveExpenseRecord(expenseId)

    override suspend fun getExpenseRecordForAudit(expenseId: String): ExpenseRecord? =
        dao.getExpenseRecord(expenseId)

    override suspend fun getAllExpenseRecordsForEvidenceRecovery(): List<ExpenseRecord> =
        dao.getAllExpenseRecordsForEvidenceRecovery()

    override suspend fun saveDailyReport(report: DailyReport) = dao.saveDailyReportSafely(report)

    override suspend fun saveDailyReportWithExpense(report: DailyReport, expense: ExpenseRecord?) {
        requireNonPrepaidExpense(expense)
        dao.saveDailyReportWithExpense(report, expense)
    }

    override suspend fun saveReceipt(receipt: ReceiptRecord) = dao.insertReceipt(receipt)

    override suspend fun saveReceiptWithExpense(receipt: ReceiptRecord, expense: ExpenseRecord?) {
        requireNonPrepaidExpense(expense)
        dao.saveReceiptWithExpense(receipt, expense)
    }

    override suspend fun saveExpenseRecord(expense: ExpenseRecord) {
        requireNonPrepaidExpense(expense)
        dao.insertExpenseRecord(expense)
    }

    override suspend fun saveExpenseWithEvidence(
        expense: ExpenseRecord,
        evidence: EvidenceRecord,
        link: ExpenseEvidenceLinkRecord
    ) {
        requireNonPrepaidExpense(expense)
        dao.saveExpenseWithEvidence(expense, evidence, link)
    }

    override suspend fun saveDailyReportWithExpenseAndEvidence(
        report: DailyReport,
        expense: ExpenseRecord,
        evidence: EvidenceRecord,
        link: ExpenseEvidenceLinkRecord
    ) {
        requireNonPrepaidExpense(expense)
        dao.saveDailyReportWithExpenseAndEvidence(report, expense, evidence, link)
    }

    override suspend fun finalizeExpenseEvidence(
        expenseId: String,
        evidence: EvidenceRecord,
        link: ExpenseEvidenceLinkRecord
    ) = dao.finalizeExpenseEvidence(expenseId, evidence, link)

    override suspend fun hasExpenseEvidenceLink(
        expenseId: String,
        evidenceId: String,
        captureId: String
    ): Boolean = dao.hasExpenseEvidenceLink(expenseId, evidenceId, captureId)

    override suspend fun deleteExpenseRecord(expense: ExpenseRecord) {
        check(
            normalizePaymentMethod(expense.paymentMethod) != PaymentMethodPrepaid &&
                expensePrepaidLinkDao.getByExpenseId(expense.id) == null
        ) {
            "Prepaid expense deletion is not supported"
        }
        dao.deleteExpenseRecord(expense)
    }

    override suspend fun deleteReceipt(receipt: ReceiptRecord) = dao.deleteReceipt(receipt)

    override suspend fun deleteUnconfirmedReceipt(receiptId: String): ReceiptDeletionResult = try {
        when (dao.deleteUnconfirmedReceipt(receiptId)) {
            1 -> ReceiptDeletionResult.Deleted
            2 -> ReceiptDeletionResult.AlreadyConfirmed
            3 -> ReceiptDeletionResult.Protected
            0 -> ReceiptDeletionResult.NotFound
            else -> ReceiptDeletionResult.Failed
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ReceiptDeletionResult.Failed
    }

    override suspend fun saveSupplierCandidate(candidate: SupplierCandidateRecord) = dao.upsertSupplierCandidate(candidate)

    override suspend fun saveMonthlySubmission(submission: MonthlySubmission) = dao.upsertMonthlySubmission(submission)

    override suspend fun saveAppSettings(settings: AppSettings) = dao.upsertAppSettings(settings)

    override suspend fun deleteDailyReport(report: DailyReport) = dao.deleteDailyReport(report)

    override suspend fun getFixedCostDetail(
        receiptId: String,
        dailyReportId: String?,
        fixedCostType: String
    ): FixedCostDetailSnapshot {
        val receipt = dao.getReceipt(receiptId)
        val report = dailyReportId?.let { dao.getDailyReport(it) }
        val application = dao.getFixedCostReceiptApplicationByReceipt(receiptId)
        val links = application?.let { dao.getFixedCostEvidenceLinks(it.applicationId) }.orEmpty()
        val evidence = links.mapNotNull { dao.getEvidenceRecord(it.evidenceId) }
        val currentAmount = report?.fixedCostAmountOrNull(fixedCostType)
        val state = if (receipt == null || currentAmount == null) null else when {
            currentAmount == 0L -> ExistingAmountState.EMPTY
            currentAmount == receipt.totalAmount -> ExistingAmountState.SAME
            else -> ExistingAmountState.CONFLICT
        }
        return FixedCostDetailSnapshot(
            receipt = receipt,
            dailyReport = report,
            fixedCostType = fixedCostType,
            currentAmount = currentAmount,
            existingAmountState = state,
            application = application,
            evidenceLinks = links,
            evidence = evidence,
            alreadyApplied = application != null,
            dailyReportMissing = report == null
        )
    }

    private fun requireNonPrepaidExpense(expense: ExpenseRecord?) {
        check(
            expense == null ||
                normalizePaymentMethod(expense.paymentMethod) != PaymentMethodPrepaid
        ) {
            "Prepaid expenses must use the prepaid purchase transaction"
        }
    }
}

private fun DailyReport.fixedCostAmountOrNull(type: String): Long? = when (type) {
    "electricity" -> electricityExpense
    "water" -> waterExpense
    "communication" -> communicationExpense
    "gas" -> gasExpense
    else -> null
}
