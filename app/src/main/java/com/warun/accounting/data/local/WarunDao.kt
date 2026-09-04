package com.warun.accounting.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.warun.accounting.data.export.MonthlyExportSourceSnapshot
import com.warun.accounting.data.export.FixedCostEvidenceExportRecord
import kotlinx.coroutines.flow.Flow

data class FixedCostEvidenceStatusRow(
    val dailyReportId: String,
    val fixedCostType: String,
    val applicationId: String,
    val evidenceId: String?,
    val captureId: String?,
    val storedUri: String?,
    val byteSize: Long?,
    val sha256: String?,
    val createdAt: Long?,
    val storedAt: Long?,
    val mediaType: String?
)

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

    @Query(
        """
        SELECT receipt.id FROM receipts AS receipt
        WHERE NOT EXISTS (
            SELECT 1 FROM fixed_cost_receipt_applications AS application
            WHERE application.receiptId = receipt.id
        )
        AND NOT EXISTS (
            SELECT 1 FROM expense_records AS expense
            WHERE expense.receiptId = receipt.id
        )
        ORDER BY COALESCE(receipt.purchaseDate, receipt.capturedDate, '') DESC, receipt.registeredAt DESC
        """
    )
    fun observeUnrelatedReceiptIds(): Flow<List<String>>

    @Query(
        """
        SELECT expense.*
        FROM expense_records AS expense
        WHERE NOT EXISTS (
            SELECT 1
            FROM expense_cancellations AS cancellation
            WHERE cancellation.expenseId = expense.id
        )
        ORDER BY expense.expenseDate DESC, expense.createdAt DESC
        """
    )
    fun observeExpenseRecords(): Flow<List<ExpenseRecord>>

    @Query(
        """
        SELECT expense.*,
               EXISTS(
                   SELECT 1
                   FROM expense_cancellations AS cancellation
                   WHERE cancellation.expenseId = expense.id
               ) AS isCancelled
        FROM expense_records AS expense
        ORDER BY expense.expenseDate DESC, expense.createdAt DESC
        """
    )
    fun observeExpenseVisibilityRecords(): Flow<List<ExpenseVisibilityRecord>>

    @Query(
        """
        SELECT link.expenseId AS expenseId,
               evidence.id AS evidenceId,
               evidence.captureId AS captureId,
               evidence.storedUri AS storedUri,
               evidence.byteSize AS byteSize,
               evidence.sha256 AS sha256,
               evidence.createdAt AS createdAt,
               evidence.storedAt AS storedAt
        FROM expense_evidence_links AS link
        INNER JOIN evidence_records AS evidence ON evidence.id = link.evidenceId
        WHERE evidence.state = 'stored' AND evidence.storedAt IS NOT NULL
        ORDER BY link.linkedAt ASC
        """
    )
    fun observeStoredExpenseEvidence(): Flow<List<ExpenseEvidenceRecord>>

    @Query(
        """
        SELECT application.dailyReportId AS dailyReportId,
               application.fixedCostType AS fixedCostType,
               application.applicationId AS applicationId,
               evidence.id AS evidenceId,
               evidence.captureId AS captureId,
               evidence.storedUri AS storedUri,
               evidence.byteSize AS byteSize,
               evidence.sha256 AS sha256,
               evidence.createdAt AS createdAt,
               evidence.storedAt AS storedAt,
               evidence.mediaType AS mediaType
        FROM fixed_cost_receipt_applications AS application
        LEFT JOIN fixed_cost_evidence_links AS link
          ON link.applicationId = application.applicationId
        LEFT JOIN evidence_records AS evidence
          ON evidence.id = link.evidenceId
         AND evidence.state = 'stored'
         AND evidence.storedAt IS NOT NULL
        ORDER BY application.dailyReportId ASC,
                 application.fixedCostType ASC,
                 link.sortOrder ASC
        """
    )
    fun observeFixedCostEvidenceStatuses(): Flow<List<FixedCostEvidenceStatusRow>>

    @Query(
        "SELECT * FROM daily_reports " +
            "WHERE reportDate BETWEEN :from AND :to OR substr(reportDate, 1, 7) = :targetMonth " +
            "ORDER BY reportDate ASC, id ASC"
    )
    suspend fun getDailyReportsForMonthlyExport(
        targetMonth: String,
        from: String,
        to: String
    ): List<DailyReport>

    @Query(
        """
        SELECT expense.*,
               EXISTS(
                   SELECT 1
                   FROM expense_cancellations AS cancellation
                   WHERE cancellation.expenseId = expense.id
               ) AS isCancelled
        FROM expense_records AS expense
        WHERE expense.expenseDate BETWEEN :from AND :to
           OR substr(expense.expenseDate, 1, 7) = :targetMonth
        ORDER BY expense.expenseDate ASC, expense.createdAt ASC, expense.id ASC
        """
    )
    suspend fun getExpenseVisibilityForMonthlyExport(
        targetMonth: String,
        from: String,
        to: String
    ): List<ExpenseVisibilityRecord>

    @Query(
        """
        SELECT link.expenseId AS expenseId,
               evidence.id AS evidenceId,
               evidence.captureId AS captureId,
               evidence.storedUri AS storedUri,
               evidence.byteSize AS byteSize,
               evidence.sha256 AS sha256,
               evidence.createdAt AS createdAt,
               evidence.storedAt AS storedAt
        FROM expense_evidence_links AS link
        INNER JOIN evidence_records AS evidence ON evidence.id = link.evidenceId
        INNER JOIN expense_records AS expense ON expense.id = link.expenseId
        WHERE (expense.expenseDate BETWEEN :from AND :to
           OR substr(expense.expenseDate, 1, 7) = :targetMonth)
          AND evidence.state = 'stored'
          AND evidence.storedAt IS NOT NULL
        ORDER BY expense.expenseDate ASC, expense.createdAt ASC, expense.id ASC,
                 link.linkedAt ASC, evidence.id ASC
        """
    )
    suspend fun getStoredExpenseEvidenceForMonthlyExport(
        targetMonth: String,
        from: String,
        to: String
    ): List<ExpenseEvidenceRecord>

    @Query(
        """
        SELECT application.dailyReportId AS dailyReportId,
               report.reportDate AS reportDate,
               application.fixedCostType AS fixedCostType,
               evidence.id AS evidenceId,
               evidence.captureId AS captureId,
               evidence.storedUri AS storedUri,
               evidence.byteSize AS byteSize,
               evidence.sha256 AS sha256,
               evidence.createdAt AS createdAt,
               evidence.storedAt AS storedAt,
               evidence.mediaType AS mediaType,
               link.sortOrder AS sortOrder
        FROM fixed_cost_receipt_applications AS application
        INNER JOIN daily_reports AS report ON report.id = application.dailyReportId
        INNER JOIN fixed_cost_evidence_links AS link ON link.applicationId = application.applicationId
        INNER JOIN evidence_records AS evidence ON evidence.id = link.evidenceId
        WHERE (report.reportDate BETWEEN :from AND :to
           OR substr(report.reportDate, 1, 7) = :targetMonth)
          AND evidence.state = 'stored'
          AND evidence.storedAt IS NOT NULL
        ORDER BY report.reportDate ASC, application.fixedCostType ASC, link.sortOrder ASC, evidence.id ASC
        """
    )
    suspend fun getStoredFixedCostEvidenceForMonthlyExport(
        targetMonth: String,
        from: String,
        to: String
    ): List<FixedCostEvidenceExportRecord>

    /**
     * Reads every monthly export input under one Room read transaction. The returned records are
     * immutable source data; export generation never writes back to accounting tables.
     */
    @Transaction
    suspend fun getMonthlyExportSourceSnapshot(
        targetMonth: String,
        from: String,
        to: String
    ): MonthlyExportSourceSnapshot = MonthlyExportSourceSnapshot(
        dailyReports = getDailyReportsForMonthlyExport(targetMonth, from, to),
        expenseVisibility = getExpenseVisibilityForMonthlyExport(targetMonth, from, to),
        storedEvidence = getStoredExpenseEvidenceForMonthlyExport(targetMonth, from, to),
        storedFixedCostEvidence = getStoredFixedCostEvidenceForMonthlyExport(targetMonth, from, to)
    )

    @Query(
        """
        SELECT expense.*
        FROM expense_records AS expense
        WHERE expense.expenseDate = :expenseDate
          AND expense.category = :category
          AND NOT EXISTS (
              SELECT 1
              FROM expense_cancellations AS cancellation
              WHERE cancellation.expenseId = expense.id
          )
        ORDER BY expense.createdAt DESC
        """
    )
    fun observeExpenseRecordsByDateAndCategory(expenseDate: String, category: String): Flow<List<ExpenseRecord>>

    @Query(
        """
        SELECT COALESCE(SUM(expense.amount), 0)
        FROM expense_records AS expense
        WHERE expense.expenseDate = :expenseDate
          AND expense.category = :category
          AND NOT EXISTS (
              SELECT 1
              FROM expense_cancellations AS cancellation
              WHERE cancellation.expenseId = expense.id
          )
        """
    )
    fun observeExpenseTotalByDateAndCategory(expenseDate: String, category: String): Flow<Long>

    @Query("SELECT * FROM supplier_candidates WHERE isHidden = 0 ORDER BY category ASC, createdAt ASC")
    fun observeSupplierCandidates(): Flow<List<SupplierCandidateRecord>>

    @Query("SELECT * FROM supplier_candidates WHERE category = :category AND isHidden = 0 ORDER BY createdAt ASC")
    fun observeVisibleSupplierCandidatesByCategory(category: String): Flow<List<SupplierCandidateRecord>>

    @Upsert
    suspend fun upsertSupplierCandidate(candidate: SupplierCandidateRecord)

    @Query("SELECT * FROM monthly_submissions ORDER BY targetMonth DESC")
    fun observeMonthlySubmissions(): Flow<List<MonthlySubmission>>

    @Query(
        "SELECT * FROM electronic_submission_records " +
            "ORDER BY generatedAt DESC, id DESC"
    )
    fun observeElectronicSubmissionRecords(): Flow<List<ElectronicSubmissionRecord>>

    @Query(
        "SELECT * FROM electronic_submission_records WHERE targetMonth = :targetMonth " +
            "ORDER BY generatedAt DESC, id DESC"
    )
    fun observeElectronicSubmissionRecordsByMonth(
        targetMonth: String
    ): Flow<List<ElectronicSubmissionRecord>>

    @Query("SELECT * FROM electronic_submission_records WHERE id = :id")
    suspend fun getElectronicSubmissionRecord(id: String): ElectronicSubmissionRecord?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDailyReport(report: DailyReport)

    @Update
    suspend fun updateDailyReport(report: DailyReport): Int

    @Transaction
    suspend fun saveDailyReportSafely(report: DailyReport) {
        if (updateDailyReport(report) == 0) {
            insertDailyReport(report)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: ReceiptRecord)

    @Query("SELECT * FROM daily_reports WHERE id = :dailyReportId")
    suspend fun getDailyReport(dailyReportId: String): DailyReport?

    @Query("SELECT * FROM receipts WHERE id = :receiptId")
    suspend fun getReceipt(receiptId: String): ReceiptRecord?

    @Query("SELECT EXISTS(SELECT 1 FROM expense_records WHERE receiptId = :receiptId)")
    suspend fun hasExpenseReference(receiptId: String): Boolean

    @Query(
        """
        DELETE FROM receipts
        WHERE id = :receiptId
          AND isConfirmed = 0
          AND NOT EXISTS (
              SELECT 1 FROM fixed_cost_receipt_applications
              WHERE receiptId = :receiptId
          )
          AND NOT EXISTS (
              SELECT 1 FROM expense_records
              WHERE receiptId = :receiptId
          )
        """
    )
    suspend fun deleteUnconfirmedReceiptIfUnprotected(receiptId: String): Int

    @Transaction
    suspend fun deleteUnconfirmedReceipt(receiptId: String): Int {
        val receipt = getReceipt(receiptId) ?: return 0
        if (receipt.isConfirmed) return 2
        if (getFixedCostReceiptApplicationByReceipt(receiptId) != null || hasExpenseReference(receiptId)) {
            return 3
        }
        val deleted = deleteUnconfirmedReceiptIfUnprotected(receiptId)
        if (deleted == 1) return 1
        return when {
            getReceipt(receiptId) == null -> 0
            getReceipt(receiptId)?.isConfirmed == true -> 2
            getFixedCostReceiptApplicationByReceipt(receiptId) != null || hasExpenseReference(receiptId) -> 3
            else -> 4
        }
    }

    @Query(
        """
        DELETE FROM receipts
        WHERE id = :receiptId
          AND isConfirmed = 1
          AND NOT EXISTS (SELECT 1 FROM fixed_cost_receipt_applications WHERE receiptId = :receiptId)
          AND NOT EXISTS (SELECT 1 FROM expense_records WHERE receiptId = :receiptId)
        """
    )
    suspend fun deleteConfirmedReceiptIfUnprotected(receiptId: String): Int

    @Transaction
    suspend fun deleteConfirmedReceipt(receiptId: String): Int {
        val receipt = getReceipt(receiptId) ?: return 0
        if (!receipt.isConfirmed) return 2
        if (getFixedCostReceiptApplicationByReceipt(receiptId) != null || hasExpenseReference(receiptId)) return 3
        val deleted = deleteConfirmedReceiptIfUnprotected(receiptId)
        if (deleted == 1) return 1
        return when {
            getReceipt(receiptId) == null -> 0
            getReceipt(receiptId)?.isConfirmed != true -> 2
            getFixedCostReceiptApplicationByReceipt(receiptId) != null || hasExpenseReference(receiptId) -> 3
            else -> 4
        }
    }

    @Query("UPDATE receipts SET isConfirmed = 1, updatedAt = :updatedAt WHERE id = :receiptId AND isConfirmed = 0")
    suspend fun markReceiptConfirmed(receiptId: String, updatedAt: Long): Int

    @Query(
        """UPDATE daily_reports SET
            electricityExpense = CASE WHEN :fixedCostType = 'electricity' THEN :amount ELSE electricityExpense END,
            waterExpense = CASE WHEN :fixedCostType = 'water' THEN :amount ELSE waterExpense END,
            communicationExpense = CASE WHEN :fixedCostType = 'communication' THEN :amount ELSE communicationExpense END,
            gasExpense = CASE WHEN :fixedCostType = 'gas' THEN :amount ELSE gasExpense END,
            updatedAt = :updatedAt
            WHERE id = :dailyReportId
              AND CASE :fixedCostType
                WHEN 'electricity' THEN electricityExpense
                WHEN 'water' THEN waterExpense
                WHEN 'communication' THEN communicationExpense
                WHEN 'gas' THEN gasExpense
                ELSE -1
              END = 0"""
    )
    suspend fun updateFixedCostAmountIfEmpty(
        dailyReportId: String,
        fixedCostType: String,
        amount: Long,
        updatedAt: Long
    ): Int

    @Upsert
    suspend fun insertExpenseRecord(expense: ExpenseRecord)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidenceRecord(evidence: EvidenceRecord): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExpenseEvidenceLink(link: ExpenseEvidenceLinkRecord): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFixedCostReceiptApplication(
        application: FixedCostReceiptApplicationRecord
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFixedCostEvidenceLink(link: FixedCostEvidenceLinkRecord): Long

    @Query("SELECT * FROM evidence_records WHERE id = :evidenceId")
    suspend fun getEvidenceRecord(evidenceId: String): EvidenceRecord?

    @Query("SELECT * FROM fixed_cost_receipt_applications WHERE applicationId = :applicationId")
    suspend fun getFixedCostReceiptApplication(applicationId: String): FixedCostReceiptApplicationRecord?

    @Query("SELECT * FROM fixed_cost_receipt_applications WHERE receiptId = :receiptId")
    suspend fun getFixedCostReceiptApplicationByReceipt(
        receiptId: String
    ): FixedCostReceiptApplicationRecord?

    @Query(
        "SELECT * FROM fixed_cost_receipt_applications " +
            "WHERE dailyReportId = :dailyReportId AND fixedCostType = :fixedCostType"
    )
    suspend fun getFixedCostReceiptApplicationByReportAndType(
        dailyReportId: String,
        fixedCostType: String
    ): FixedCostReceiptApplicationRecord?

    @Query(
        "SELECT * FROM fixed_cost_evidence_links " +
            "WHERE applicationId = :applicationId ORDER BY sortOrder ASC"
    )
    suspend fun getFixedCostEvidenceLinks(
        applicationId: String
    ): List<FixedCostEvidenceLinkRecord>

    @Query(
        "SELECT COUNT(*) FROM fixed_cost_evidence_links AS link " +
            "INNER JOIN evidence_records AS evidence ON evidence.id = link.evidenceId " +
            "WHERE link.applicationId = :applicationId " +
            "AND evidence.state = 'stored' AND evidence.storedAt IS NOT NULL"
    )
    suspend fun countStoredFixedCostEvidence(applicationId: String): Int

    @Query("SELECT * FROM expense_records WHERE id = :expenseId")
    suspend fun getExpenseRecord(expenseId: String): ExpenseRecord?

    @Query(
        """
        SELECT expense.*
        FROM expense_records AS expense
        WHERE expense.id = :expenseId
          AND NOT EXISTS (
              SELECT 1
              FROM expense_cancellations AS cancellation
              WHERE cancellation.expenseId = expense.id
          )
        """
    )
    suspend fun getActiveExpenseRecord(expenseId: String): ExpenseRecord?

    @Query(
        """
        SELECT expense.*
        FROM expense_records AS expense
        INNER JOIN expense_cancellations AS cancellation
          ON cancellation.expenseId = expense.id
        WHERE expense.expenseDate = :expenseDate
        ORDER BY cancellation.cancelledAt DESC, expense.createdAt DESC
        """
    )
    fun observeCancelledExpenseRecordsForAuditByDate(
        expenseDate: String
    ): Flow<List<ExpenseRecord>>

    @Query(
        """
        SELECT * FROM expense_records
        ORDER BY expenseDate DESC, createdAt DESC
        """
    )
    suspend fun getAllExpenseRecordsForEvidenceRecovery(): List<ExpenseRecord>

    @Query("SELECT expenseId FROM expense_evidence_links WHERE evidenceId = :evidenceId")
    suspend fun getExpenseIdForEvidence(evidenceId: String): String?

    @Query("SELECT applicationId FROM fixed_cost_evidence_links WHERE evidenceId = :evidenceId")
    suspend fun getFixedCostApplicationIdForEvidence(evidenceId: String): String?

    @Query("SELECT * FROM expense_evidence_links WHERE expenseId = :expenseId ORDER BY linkedAt ASC")
    suspend fun getEvidenceLinksForExpense(expenseId: String): List<ExpenseEvidenceLinkRecord>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM evidence_records AS evidence
            INNER JOIN expense_evidence_links AS link ON link.evidenceId = evidence.id
            WHERE evidence.id = :evidenceId
              AND evidence.captureId = :captureId
              AND link.expenseId = :expenseId
        )
        """
    )
    suspend fun hasExpenseEvidenceLink(
        expenseId: String,
        evidenceId: String,
        captureId: String
    ): Boolean

    @Query(
        """
        UPDATE evidence_records
        SET state = 'stored', storedAt = :storedAt, updatedAt = :updatedAt
        WHERE id = :evidenceId
          AND captureId = :captureId
          AND storedUri = :storedUri
          AND byteSize = :byteSize
          AND sha256 = :sha256
          AND state IN ('pending', 'stored')
        """
    )
    suspend fun markEvidenceStored(
        evidenceId: String,
        captureId: String,
        storedUri: String,
        byteSize: Long,
        sha256: String,
        storedAt: Long,
        updatedAt: Long
    ): Int

    @Transaction
    suspend fun saveDailyReportWithExpense(report: DailyReport, expense: ExpenseRecord?) {
        saveDailyReportSafely(report)
        expense?.let { insertExpenseRecord(it) }
    }

    @Transaction
    suspend fun saveExpenseWithEvidence(
        expense: ExpenseRecord,
        evidence: EvidenceRecord,
        link: ExpenseEvidenceLinkRecord
    ) {
        require(link.expenseId == expense.id && link.evidenceId == evidence.id)
        insertExpenseRecord(expense)
        ensureEvidence(evidence)
        ensureEvidenceLink(link)
    }

    @Transaction
    suspend fun saveDailyReportWithExpenseAndEvidence(
        report: DailyReport,
        expense: ExpenseRecord,
        evidence: EvidenceRecord,
        link: ExpenseEvidenceLinkRecord
    ) {
        saveDailyReportSafely(report)
        saveExpenseWithEvidence(expense, evidence, link)
    }

    @Transaction
    suspend fun finalizeExpenseEvidence(
        expenseId: String,
        storedEvidence: EvidenceRecord,
        link: ExpenseEvidenceLinkRecord
    ) {
        check(getExpenseRecord(expenseId) != null) { "Evidence owner expense does not exist" }
        require(link.expenseId == expenseId && link.evidenceId == storedEvidence.id)
        ensureEvidence(storedEvidence)
        ensureEvidenceLink(link)
        val updated = markEvidenceStored(
            evidenceId = storedEvidence.id,
            captureId = storedEvidence.captureId,
            storedUri = storedEvidence.storedUri,
            byteSize = storedEvidence.byteSize,
            sha256 = storedEvidence.sha256,
            storedAt = requireNotNull(storedEvidence.storedAt),
            updatedAt = storedEvidence.updatedAt
        )
        check(updated == 1) { "Evidence metadata does not match the stored file" }
    }

    @Transaction
    suspend fun addEvidenceToExpense(
        expenseId: String,
        evidence: EvidenceRecord,
        link: ExpenseEvidenceLinkRecord
    ) {
        check(getExpenseRecord(expenseId) != null) { "Evidence owner expense does not exist" }
        require(link.expenseId == expenseId && link.evidenceId == evidence.id)
        ensureEvidence(evidence)
        ensureEvidenceLink(link)
    }

    private suspend fun ensureEvidence(evidence: EvidenceRecord) {
        insertEvidenceRecord(evidence)
        val existing = getEvidenceRecord(evidence.id)
            ?: error("Evidence metadata could not be persisted")
        check(
            existing.captureId == evidence.captureId &&
                existing.storedUri == evidence.storedUri &&
                existing.byteSize == evidence.byteSize &&
                existing.sha256 == evidence.sha256 &&
                existing.mediaType == evidence.mediaType
        ) { "Evidence ID is already used by different content" }
    }

    private suspend fun ensureEvidenceLink(link: ExpenseEvidenceLinkRecord) {
        val owner = getExpenseIdForEvidence(link.evidenceId)
        check(owner == null || owner == link.expenseId) {
            "Evidence is already linked to another expense"
        }
        insertExpenseEvidenceLink(link)
        check(getExpenseIdForEvidence(link.evidenceId) == link.expenseId) {
            "Evidence link could not be persisted"
        }
    }

    /** Idempotent contract for the future fixed-cost apply flow; it does not create ExpenseRecord. */
    @Transaction
    suspend fun ensureFixedCostReceiptApplication(
        application: FixedCostReceiptApplicationRecord
    ) {
        insertFixedCostReceiptApplication(application)
        check(getFixedCostReceiptApplication(application.applicationId) == application) {
            "Fixed-cost application ID is already used by different content"
        }
        check(getFixedCostReceiptApplicationByReceipt(application.receiptId) == application) {
            "Receipt is already assigned to another fixed-cost application"
        }
        check(
            getFixedCostReceiptApplicationByReportAndType(
                application.dailyReportId,
                application.fixedCostType
            ) == application
        ) { "Daily report fixed-cost type is already assigned" }
    }

    /** Prevents an Evidence record from being owned by both Expense and fixed-cost flows. */
    @Transaction
    suspend fun ensureFixedCostEvidenceLink(link: FixedCostEvidenceLinkRecord) {
        val evidence = getEvidenceRecord(link.evidenceId)
            ?: error("Fixed-cost Evidence does not exist")
        check(evidence.state == EvidenceRecordState.Stored && evidence.storedAt != null) {
            "Fixed-cost Evidence must be stored before linking"
        }
        val expenseOwner = getExpenseIdForEvidence(link.evidenceId)
        check(expenseOwner == null) { "Evidence is already linked to an expense" }
        val fixedCostOwner = getFixedCostApplicationIdForEvidence(link.evidenceId)
        check(fixedCostOwner == null || fixedCostOwner == link.applicationId) {
            "Evidence is already linked to another fixed-cost application"
        }
        require(link.sortOrder >= 0) { "Evidence sortOrder must be non-negative" }
        insertFixedCostEvidenceLink(link)
        check(
            getFixedCostEvidenceLinks(link.applicationId).any {
                it.evidenceId == link.evidenceId && it.sortOrder == link.sortOrder
            }
        ) { "Fixed-cost Evidence link could not be persisted" }
    }

    @Transaction
    suspend fun applyFixedCostEvidence(
        report: DailyReport,
        receipt: ReceiptRecord,
        application: FixedCostReceiptApplicationRecord,
        evidence: List<EvidenceRecord>,
        links: List<FixedCostEvidenceLinkRecord>
    ) {
        check(getDailyReport(report.id) != null) { "Target DailyReport does not exist" }
        check(getReceipt(receipt.id) != null) { "Target Receipt does not exist" }
        val currentReport = requireNotNull(getDailyReport(report.id))
        val currentAmount = currentReport.fixedCostAmount(application.fixedCostType)
        when {
            currentAmount == 0L -> {
                val updated = updateFixedCostAmountIfEmpty(
                    report.id,
                    application.fixedCostType,
                    receipt.totalAmount,
                    application.updatedAt
                )
                check(updated == 1) { "Fixed-cost amount changed during finalization" }
            }
            currentAmount == receipt.totalAmount -> Unit
            else -> error("Existing fixed-cost amount conflicts with Receipt")
        }
        ensureFixedCostReceiptApplication(application)
        for (item in evidence) ensureEvidence(item)
        for (link in links) ensureFixedCostEvidenceLink(link)
        check(markReceiptConfirmed(receipt.id, receipt.updatedAt) == 1 || receipt.isConfirmed) {
            "Receipt confirmation could not be persisted"
        }
    }

    /** Applies Evidence selected from a saved DailyReport without creating an ExpenseRecord. */
    @Transaction
    suspend fun applyDirectFixedCostEvidence(
        report: DailyReport,
        receipt: ReceiptRecord,
        application: FixedCostReceiptApplicationRecord,
        evidence: List<EvidenceRecord>,
        links: List<FixedCostEvidenceLinkRecord>
    ) {
        check(getDailyReport(report.id) != null) { "Target DailyReport does not exist" }
        check(receipt.isConfirmed) { "Direct fixed-cost Receipt must be confirmed" }
        val currentReport = requireNotNull(getDailyReport(report.id))
        check(currentReport.fixedCostAmount(application.fixedCostType) == receipt.totalAmount) {
            "DailyReport fixed-cost amount changed during finalization"
        }
        check(getFixedCostReceiptApplicationByReportAndType(report.id, application.fixedCostType) == null) {
            "Daily report fixed-cost type is already assigned"
        }
        check(getReceipt(receipt.id) == null) { "Direct fixed-cost Receipt already exists" }
        insertReceipt(receipt)
        ensureFixedCostReceiptApplication(application)
        for (item in evidence) ensureEvidence(item)
        for (link in links) ensureFixedCostEvidenceLink(link)
    }

    private fun DailyReport.fixedCostAmount(type: String): Long = when (type) {
        "electricity" -> electricityExpense
        "water" -> waterExpense
        "communication" -> communicationExpense
        "gas" -> gasExpense
        else -> error("Unsupported fixed-cost type")
    }

    @Transaction
    suspend fun saveReceiptWithExpense(receipt: ReceiptRecord, expense: ExpenseRecord?) {
        insertReceipt(receipt)
        expense?.let { insertExpenseRecord(it) }
    }

    @Upsert
    suspend fun upsertMonthlySubmission(submission: MonthlySubmission)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertElectronicSubmissionRecord(record: ElectronicSubmissionRecord)

    @Query(
        "UPDATE electronic_submission_records " +
            "SET note = :note, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateElectronicSubmissionNote(
        id: String,
        note: String?,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE electronic_submission_records " +
            "SET status = 'submitted', submittedAt = :submittedAt, updatedAt = :updatedAt " +
            "WHERE id = :id AND status = 'not_submitted' AND submittedAt IS NULL"
    )
    suspend fun markElectronicSubmissionSubmitted(
        id: String,
        submittedAt: Long,
        updatedAt: Long
    ): Int

    @Upsert
    suspend fun upsertAppSettings(settings: AppSettings)

    @Delete
    suspend fun deleteDailyReport(report: DailyReport)

    @Delete
    suspend fun deleteReceipt(receipt: ReceiptRecord)

    @Delete
    suspend fun deleteExpenseRecord(expense: ExpenseRecord)
}
