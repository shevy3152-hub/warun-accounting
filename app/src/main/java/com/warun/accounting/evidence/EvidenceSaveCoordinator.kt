package com.warun.accounting.evidence

import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.data.local.ExpenseRecord
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EvidenceRecoveryFailure(
    val captureId: String,
    val error: Throwable
)

data class EvidenceRecoveryResult(
    val completedCaptureIds: List<String>,
    val failures: List<EvidenceRecoveryFailure>
)

@Singleton
class EvidenceSaveCoordinator @Inject constructor(
    private val promoter: EvidenceFilePromoter,
    private val journal: EvidenceFinalizationJournal
) {
    private val operationMutex = Mutex()

    suspend fun saveExpense(
        expense: ExpenseRecord,
        expenseDraftId: String,
        pendingCapture: ReceiptCaptureResult?,
        findSavedExpense: suspend (String) -> ExpenseRecord?,
        saveAccounting: suspend () -> Unit
    ) = saveWithEvidence(
        expense = expense,
        expenseDraftId = expenseDraftId,
        pendingCapture = pendingCapture,
        findSavedExpense = findSavedExpense,
        saveAccounting = saveAccounting
    )

    suspend fun saveDailyReportWithExpense(
        expense: ExpenseRecord?,
        expenseDraftId: String?,
        pendingCapture: ReceiptCaptureResult?,
        findSavedExpense: suspend (String) -> ExpenseRecord?,
        saveAccounting: suspend () -> Unit
    ) {
        if (pendingCapture == null) {
            saveAccounting()
            return
        }
        val record = expense ?: throw EvidenceJournalException("証憑画像に対応する支出がありません")
        val draftId = expenseDraftId ?: throw EvidenceJournalException("証憑画像の支出下書きIDがありません")
        saveWithEvidence(record, draftId, pendingCapture, findSavedExpense, saveAccounting)
    }

    suspend fun recoverPendingFinalizations(
        savedExpenses: List<ExpenseRecord>
    ): EvidenceRecoveryResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val savedById = savedExpenses.associateBy { it.id }
            val completed = mutableListOf<String>()
            val failures = mutableListOf<EvidenceRecoveryFailure>()
            journal.loadAll().forEach { entry ->
                try {
                    if (recoverEntryIfAccountingSaved(entry, savedById[entry.expenseRecordId])) {
                        completed += entry.captureId
                    }
                } catch (error: Exception) {
                    failures += EvidenceRecoveryFailure(entry.captureId, error)
                }
            }
            EvidenceRecoveryResult(completed, failures)
        }
    }

    private suspend fun saveWithEvidence(
        expense: ExpenseRecord,
        expenseDraftId: String,
        pendingCapture: ReceiptCaptureResult?,
        findSavedExpense: suspend (String) -> ExpenseRecord?,
        saveAccounting: suspend () -> Unit
    ) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (pendingCapture == null) {
                saveAccounting()
                return@withLock
            }
            if (expenseDraftId.isBlank() || expense.id != expenseDraftId) {
                throw EvidenceJournalConflictException("支出下書きIDと保存対象ExpenseRecord IDが一致しません")
            }
            val fingerprint = expenseFingerprint(expense)
            recoverExistingCapture(
                captureId = pendingCapture.captureId,
                expenseDraftId = expenseDraftId,
                expenseRecordId = expense.id,
                findSavedExpense = findSavedExpense
            )
            journal.prepare(
                captureId = pendingCapture.captureId,
                expenseDraftId = expenseDraftId,
                expenseRecordId = expense.id,
                expenseFingerprint = fingerprint
            )

            saveAccounting()

            try {
                journal.markAccountingSaved(
                    captureId = pendingCapture.captureId,
                    expenseRecordId = expense.id,
                    expenseFingerprint = fingerprint
                )
                finalizeEntry(
                    journal.find(pendingCapture.captureId)
                        ?: throw EvidenceJournalException("保存済み正式化ジャーナルが見つかりません")
                )
            } catch (error: Exception) {
                throw EvidenceFinalizationAfterAccountingSaveException(error)
            }
        }
    }

    private suspend fun recoverExistingCapture(
        captureId: String,
        expenseDraftId: String,
        expenseRecordId: String,
        findSavedExpense: suspend (String) -> ExpenseRecord?
    ) {
        val existing = journal.find(captureId) ?: return
        if (
            existing.expenseDraftId != expenseDraftId ||
            existing.expenseRecordId != expenseRecordId
        ) {
            throw EvidenceJournalConflictException("captureIdを別の支出へ適用できません")
        }
        recoverEntryIfAccountingSaved(existing, findSavedExpense(existing.expenseRecordId))
    }

    private fun recoverEntryIfAccountingSaved(
        entry: EvidenceFinalizationEntry,
        savedExpense: ExpenseRecord?
    ): Boolean {
        val accountingSaved = entry.state == EvidenceFinalizationState.AccountingSaved ||
            savedExpense?.let(::expenseFingerprint) == entry.expenseFingerprint
        if (!accountingSaved) return false
        val savedEntry = if (entry.state == EvidenceFinalizationState.AccountingSaved) {
            entry
        } else {
            journal.markAccountingSaved(
                captureId = entry.captureId,
                expenseRecordId = entry.expenseRecordId,
                expenseFingerprint = entry.expenseFingerprint
            )
        }
        finalizeEntry(savedEntry)
        return true
    }

    private fun finalizeEntry(entry: EvidenceFinalizationEntry) {
        promoter.promotePendingImage(entry.captureId)
        journal.complete(entry.captureId, entry.expenseRecordId)
    }
}

fun expenseFingerprint(expense: ExpenseRecord): String {
    val canonical = buildString {
        appendField("id", expense.id)
        appendField("expenseDate", expense.expenseDate)
        appendField("category", expense.category)
        appendField("supplierName", expense.supplierName.orEmpty())
        appendField("amount", expense.amount.toString())
        appendField("paymentMethod", expense.paymentMethod.orEmpty())
        appendField("memo", expense.memo.orEmpty())
        appendField("receiptId", expense.receiptId.orEmpty())
        appendField("sourceType", expense.sourceType)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun StringBuilder.appendField(name: String, value: String) {
    append(name)
    append(':')
    append(value.length)
    append(':')
    append(value)
    append('\n')
}

class EvidenceFinalizationAfterAccountingSaveException(
    cause: Throwable
) : IllegalStateException(
    "会計データは保存されましたが、証憑画像を正式保存できませんでした。入力とpending画像は保持されています。もう一度保存してください。",
    cause
)
