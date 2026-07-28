package com.warun.accounting.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT * FROM expense_records WHERE expenseDate = :expenseDate AND category = :category ORDER BY createdAt DESC")
    fun observeExpenseRecordsByDateAndCategory(expenseDate: String, category: String): Flow<List<ExpenseRecord>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM expense_records WHERE expenseDate = :expenseDate AND category = :category")
    fun observeExpenseTotalByDateAndCategory(expenseDate: String, category: String): Flow<Long>

    @Query("SELECT * FROM supplier_candidates WHERE isHidden = 0 ORDER BY category ASC, createdAt ASC")
    fun observeSupplierCandidates(): Flow<List<SupplierCandidateRecord>>

    @Query("SELECT * FROM supplier_candidates WHERE category = :category AND isHidden = 0 ORDER BY createdAt ASC")
    fun observeVisibleSupplierCandidatesByCategory(category: String): Flow<List<SupplierCandidateRecord>>

    @Upsert
    suspend fun upsertSupplierCandidate(candidate: SupplierCandidateRecord)

    @Query("SELECT * FROM monthly_submissions ORDER BY targetMonth DESC")
    fun observeMonthlySubmissions(): Flow<List<MonthlySubmission>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyReport(report: DailyReport)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: ReceiptRecord)

    @Upsert
    suspend fun insertExpenseRecord(expense: ExpenseRecord)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidenceRecord(evidence: EvidenceRecord): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExpenseEvidenceLink(link: ExpenseEvidenceLinkRecord): Long

    @Query("SELECT * FROM evidence_records WHERE id = :evidenceId")
    suspend fun getEvidenceRecord(evidenceId: String): EvidenceRecord?

    @Query("SELECT * FROM expense_records WHERE id = :expenseId")
    suspend fun getExpenseRecord(expenseId: String): ExpenseRecord?

    @Query("SELECT expenseId FROM expense_evidence_links WHERE evidenceId = :evidenceId")
    suspend fun getExpenseIdForEvidence(evidenceId: String): String?

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
        insertDailyReport(report)
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
        insertDailyReport(report)
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

    private suspend fun ensureEvidence(evidence: EvidenceRecord) {
        insertEvidenceRecord(evidence)
        val existing = getEvidenceRecord(evidence.id)
            ?: error("Evidence metadata could not be persisted")
        check(
            existing.captureId == evidence.captureId &&
                existing.storedUri == evidence.storedUri &&
                existing.byteSize == evidence.byteSize &&
                existing.sha256 == evidence.sha256
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

    @Transaction
    suspend fun saveReceiptWithExpense(receipt: ReceiptRecord, expense: ExpenseRecord?) {
        insertReceipt(receipt)
        expense?.let { insertExpenseRecord(it) }
    }

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
