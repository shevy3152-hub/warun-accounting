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
    val failures: List<EvidenceRecoveryFailure>,
    val quarantinedCaptureIds: Set<String> = emptySet(),
    val unidentifiedQuarantinedCount: Int = 0
)

@Singleton
class EvidenceSaveCoordinator @Inject constructor(
    private val promoter: EvidenceFilePromoter,
    private val journal: EvidenceFinalizationJournal
) {
    private val operationMutex = Mutex()

    suspend fun saveExpenseAndLinkEvidence(
        expense: ExpenseRecord,
        expenseDraftId: String,
        pendingCapture: ReceiptCaptureResult?,
        findSavedExpense: suspend (String) -> ExpenseRecord?,
        hasPersistedEvidenceLink: suspend (String, String, String) -> Boolean,
        saveAccounting: suspend (EvidenceFileReference?) -> Unit,
        onPromoted: suspend (EvidenceFinalizationEntry, EvidenceFileReference) -> Unit
    ) = saveWithEvidence(
        expense = expense,
        expenseDraftId = expenseDraftId,
        pendingCapture = pendingCapture,
        findSavedExpense = findSavedExpense,
        hasPersistedEvidenceLink = hasPersistedEvidenceLink,
        saveAccounting = saveAccounting,
        onPromoted = onPromoted
    )

    suspend fun saveDailyReportWithExpenseAndLinkEvidence(
        expense: ExpenseRecord?,
        expenseDraftId: String?,
        pendingCapture: ReceiptCaptureResult?,
        findSavedExpense: suspend (String) -> ExpenseRecord?,
        hasPersistedEvidenceLink: suspend (String, String, String) -> Boolean,
        saveAccounting: suspend (EvidenceFileReference?) -> Unit,
        onPromoted: suspend (EvidenceFinalizationEntry, EvidenceFileReference) -> Unit
    ) {
        if (pendingCapture == null) {
            saveAccounting(null)
            return
        }
        val record = expense ?: throw EvidenceJournalException("証憑画像に対応する支出がありません")
        val draftId = expenseDraftId ?: throw EvidenceJournalException("証憑画像の支出下書きIDがありません")
        saveWithEvidence(
            expense = record,
            expenseDraftId = draftId,
            pendingCapture = pendingCapture,
            findSavedExpense = findSavedExpense,
            hasPersistedEvidenceLink = hasPersistedEvidenceLink,
            saveAccounting = saveAccounting,
            onPromoted = onPromoted
        )
    }

    suspend fun recoverPendingFinalizations(
        savedExpenses: List<ExpenseRecord>,
        hasPersistedEvidenceLink: suspend (String, String, String) -> Boolean,
        onPromoted: suspend (EvidenceFinalizationEntry, EvidenceFileReference) -> Unit = { _, _ -> }
    ): EvidenceRecoveryResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val savedById = savedExpenses.associateBy { it.id }
            val completed = mutableListOf<String>()
            val failures = mutableListOf<EvidenceRecoveryFailure>()
            val loadResult = journal.loadAllWithDiagnostics()
            loadResult.entries.forEach { entry ->
                try {
                    if (
                        recoverEntryIfAccountingSaved(
                            entry = entry,
                            savedExpense = savedById[entry.expenseRecordId],
                            hasPersistedEvidenceLink = hasPersistedEvidenceLink,
                            onPromoted = onPromoted
                        )
                    ) {
                        completed += entry.captureId
                    }
                } catch (error: Exception) {
                    failures += EvidenceRecoveryFailure(entry.captureId, error)
                }
            }
            EvidenceRecoveryResult(
                completedCaptureIds = completed,
                failures = failures,
                quarantinedCaptureIds = loadResult.quarantinedCaptureIds,
                unidentifiedQuarantinedCount = loadResult.unidentifiedQuarantinedCount
            )
        }
    }

    private suspend fun saveWithEvidence(
        expense: ExpenseRecord,
        expenseDraftId: String,
        pendingCapture: ReceiptCaptureResult?,
        findSavedExpense: suspend (String) -> ExpenseRecord?,
        hasPersistedEvidenceLink: suspend (String, String, String) -> Boolean,
        saveAccounting: suspend (EvidenceFileReference?) -> Unit,
        onPromoted: suspend (EvidenceFinalizationEntry, EvidenceFileReference) -> Unit
    ) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (pendingCapture == null) {
                saveAccounting(null)
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
                findSavedExpense = findSavedExpense,
                hasPersistedEvidenceLink = hasPersistedEvidenceLink,
                onPromoted = onPromoted
            )
            journal.prepare(
                captureId = pendingCapture.captureId,
                expenseDraftId = expenseDraftId,
                expenseRecordId = expense.id,
                expenseFingerprint = fingerprint
            )

            val inspectedEvidence = promoter.inspectPendingImage(pendingCapture.captureId)
            saveAccounting(inspectedEvidence)

            try {
                check(
                    hasPersistedEvidenceLink(
                        expense.id,
                        inspectedEvidence.evidenceId,
                        pendingCapture.captureId
                    )
                ) { "保存済み支出と証憑リンクを確認できません" }
                journal.markAccountingSaved(
                    captureId = pendingCapture.captureId,
                    expenseRecordId = expense.id,
                    expenseFingerprint = fingerprint
                )
                finalizeEntry(
                    journal.find(pendingCapture.captureId)
                        ?: throw EvidenceJournalException("保存済み正式化ジャーナルが見つかりません"),
                    onPromoted
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
        findSavedExpense: suspend (String) -> ExpenseRecord?,
        hasPersistedEvidenceLink: suspend (String, String, String) -> Boolean,
        onPromoted: suspend (EvidenceFinalizationEntry, EvidenceFileReference) -> Unit
    ) {
        val existing = journal.find(captureId) ?: return
        if (
            existing.expenseDraftId != expenseDraftId ||
            existing.expenseRecordId != expenseRecordId
        ) {
            throw EvidenceJournalConflictException("captureIdを別の支出へ適用できません")
        }
        recoverEntryIfAccountingSaved(
            entry = existing,
            savedExpense = findSavedExpense(existing.expenseRecordId),
            hasPersistedEvidenceLink = hasPersistedEvidenceLink,
            onPromoted = onPromoted
        )
    }

    private suspend fun recoverEntryIfAccountingSaved(
        entry: EvidenceFinalizationEntry,
        savedExpense: ExpenseRecord?,
        hasPersistedEvidenceLink: suspend (String, String, String) -> Boolean,
        onPromoted: suspend (EvidenceFinalizationEntry, EvidenceFileReference) -> Unit
    ): Boolean {
        val linkExists = hasPersistedEvidenceLink(
            entry.expenseRecordId,
            entry.captureId,
            entry.captureId
        )
        if (!linkExists) {
            if (entry.state == EvidenceFinalizationState.AccountingSaved) {
                throw EvidenceJournalConflictException("保存済み支出と証憑リンクが一致しません")
            }
            return false
        }
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
        finalizeEntry(savedEntry, onPromoted)
        return true
    }

    private suspend fun finalizeEntry(
        entry: EvidenceFinalizationEntry,
        onPromoted: suspend (EvidenceFinalizationEntry, EvidenceFileReference) -> Unit
    ) {
        val reference = promoter.promotePendingImage(entry.captureId)
        onPromoted(entry, reference)
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
    "会計データは保存されましたが、証憑画像の正式保存を完了できませんでした。入力と再試行情報は保持されています。もう一度保存してください。",
    cause
)
