package com.warun.accounting.evidence

import androidx.lifecycle.SavedStateHandle
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.camera.ReceiptImageStore
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.ui.receipt.ReceiptOcrApplyResult
import com.warun.accounting.ui.viewmodel.ExpenseInput
import com.warun.accounting.ui.viewmodel.InputStateViewModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest

class EvidenceSaveCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun persistentIndividualSaveProvidesVerifiedMetadataBeforePromotionAndFinalizesLink() = runTest {
        val fixture = fixture()
        fixture.writePending("capture-persistent-individual")
        val expense = expense("expense-persistent-individual")
        var inspected: EvidenceFileReference? = null
        var finalized: EvidenceFileReference? = null

        fixture.coordinator.saveExpenseAndLinkEvidence(
            expense = expense,
            expenseDraftId = expense.id,
            pendingCapture = capture("capture-persistent-individual"),
            findSavedExpense = { null },
            hasPersistedEvidenceLink = { _, _, _ -> true },
            saveAccounting = { reference ->
                inspected = requireNotNull(reference)
                assertFalse(fixture.storedFile(reference.evidenceId).exists())
                assertTrue(fixture.pendingFile(reference.evidenceId).exists())
            },
            onPromoted = { entry, reference ->
                assertEquals(expense.id, entry.expenseRecordId)
                finalized = reference
                assertTrue(fixture.storedFile(reference.evidenceId).exists())
            }
        )

        assertEquals(inspected?.sha256, finalized?.sha256)
        assertEquals(inspected?.localUri, finalized?.localUri)
        assertFalse(fixture.pendingFile("capture-persistent-individual").exists())
        assertNull(fixture.journal.find("capture-persistent-individual"))
    }

    @Test
    fun persistentDailySaveDoesNotPromoteWhenAccountingTransactionFails() = runTest {
        val fixture = fixture()
        fixture.writePending("capture-persistent-daily-failure")
        val expense = expense("expense-persistent-daily-failure")
        var promoted = false

        val result = runCatching {
            fixture.coordinator.saveDailyReportWithExpenseAndLinkEvidence(
                expense = expense,
                expenseDraftId = expense.id,
                pendingCapture = capture("capture-persistent-daily-failure"),
                findSavedExpense = { null },
                hasPersistedEvidenceLink = { _, _, _ -> true },
                saveAccounting = { error("transaction failure") },
                onPromoted = { _, _ -> promoted = true }
            )
        }

        assertTrue(result.isFailure)
        assertFalse(promoted)
        assertTrue(fixture.pendingFile("capture-persistent-daily-failure").exists())
        assertFalse(fixture.storedFile("capture-persistent-daily-failure").exists())
    }

    @Test
    fun metadataFinalizationFailureCanResumeWithoutDuplicateFile() = runTest {
        val fixture = fixture()
        fixture.writePending("capture-metadata-retry")
        val expense = expense("expense-metadata-retry")
        var finalizationCalls = 0

        val first = runCatching {
            fixture.coordinator.saveExpenseAndLinkEvidence(
                expense = expense,
                expenseDraftId = expense.id,
                pendingCapture = capture("capture-metadata-retry"),
                findSavedExpense = { expense },
                hasPersistedEvidenceLink = { _, _, _ -> true },
                saveAccounting = { _ -> },
                onPromoted = { _, _ ->
                    finalizationCalls++
                    error("metadata transaction failure")
                }
            )
        }
        assertTrue(first.exceptionOrNull() is EvidenceFinalizationAfterAccountingSaveException)
        assertEquals(1, fixture.formalFiles().size)
        assertNotNull(fixture.journal.find("capture-metadata-retry"))

        val recovery = fixture.coordinator.recoverPendingFinalizations(
            savedExpenses = listOf(expense),
            onPromoted = { _, _ -> finalizationCalls++ }
        )

        assertEquals(listOf("capture-metadata-retry"), recovery.completedCaptureIds)
        assertEquals(2, finalizationCalls)
        assertEquals(1, fixture.formalFiles().size)
        assertNull(fixture.journal.find("capture-metadata-retry"))
    }

    @Test
    fun storedEvidenceWithRemainingJournalCompletesIdempotentlyAfterRestart() = runTest {
        val fixture = fixture()
        fixture.writePending("capture-journal-delete-window")
        val expense = expense("expense-journal-delete-window")
        fixture.journal.prepare(
            captureId = "capture-journal-delete-window",
            expenseDraftId = expense.id,
            expenseRecordId = expense.id,
            expenseFingerprint = expenseFingerprint(expense)
        )
        fixture.journal.markAccountingSaved(
            captureId = "capture-journal-delete-window",
            expenseRecordId = expense.id,
            expenseFingerprint = expenseFingerprint(expense)
        )
        val storedReference = fixture.store.promotePendingImage("capture-journal-delete-window")
        var persistedReference: EvidenceFileReference? = null

        val recovery = fixture.coordinator.recoverPendingFinalizations(
            savedExpenses = listOf(expense),
            onPromoted = { entry, reference ->
                assertEquals(expense.id, entry.expenseRecordId)
                persistedReference = reference
            }
        )

        assertEquals(listOf("capture-journal-delete-window"), recovery.completedCaptureIds)
        assertEquals(storedReference.sha256, persistedReference?.sha256)
        assertEquals(1, fixture.formalFiles().size)
        assertNull(fixture.journal.find("capture-journal-delete-window"))
    }

    @Test
    fun repositoryFailureDoesNotPromoteAndKeepsPreparedJournalAndPending() = runTest {
        val fixture = fixture(promoter = RecordingPromoter())
        fixture.writePending("capture-repository-failure")
        val expense = expense("expense-repository-failure")
        var repositoryCalls = 0

        val result = runCatching {
            fixture.coordinator.saveExpense(
                expense = expense,
                expenseDraftId = expense.id,
                pendingCapture = capture("capture-repository-failure"),
                findSavedExpense = { null },
                saveAccounting = {
                    repositoryCalls++
                    error("repository failure")
                }
            )
        }

        assertTrue(result.isFailure)
        assertEquals(1, repositoryCalls)
        assertEquals(0, fixture.recordingPromoter().calls)
        assertEquals(
            EvidenceFinalizationState.Prepared,
            fixture.journal.find("capture-repository-failure")?.state
        )
        val journalText = fixture.journal.journalFileFor("capture-repository-failure").readText()
        assertFalse(journalText.contains("テスト商店"))
        assertFalse(journalText.contains("テストメモ"))
        assertTrue(
            fixture.journal.journalFileFor("capture-repository-failure")
                .parentFile
                ?.listFiles()
                .orEmpty()
                .none { it.name.endsWith(".tmp") }
        )
        assertTrue(fixture.pendingFile("capture-repository-failure").exists())
        assertFalse(fixture.storedFile("capture-repository-failure").exists())
    }

    @Test
    fun individualSavePromotesOnlyAfterRepositorySuccess() = runTest {
        val events = mutableListOf<String>()
        val fixture = fixture(promoter = RecordingPromoter(events = events))
        val expense = expense("expense-individual")

        fixture.coordinator.saveExpense(
            expense = expense,
            expenseDraftId = expense.id,
            pendingCapture = capture("capture-individual"),
            findSavedExpense = { null },
            saveAccounting = { events += "repository" }
        )

        assertEquals(listOf("repository", "promote"), events)
        assertNull(fixture.journal.find("capture-individual"))
    }

    @Test
    fun dailyReportTransactionPromotesOnlyAfterRepositorySuccess() = runTest {
        val events = mutableListOf<String>()
        val fixture = fixture(promoter = RecordingPromoter(events = events))
        val expense = expense("expense-daily-report")

        fixture.coordinator.saveDailyReportWithExpense(
            expense = expense,
            expenseDraftId = expense.id,
            pendingCapture = capture("capture-daily-report"),
            findSavedExpense = { null },
            saveAccounting = { events += "daily-report-transaction" }
        )

        assertEquals(listOf("daily-report-transaction", "promote"), events)
        assertNull(fixture.journal.find("capture-daily-report"))
    }

    @Test
    fun dailyReportTransactionFailureDoesNotPromote() = runTest {
        val promoter = RecordingPromoter()
        val fixture = fixture(promoter)
        fixture.writePending("capture-daily-report-failure")
        val expense = expense("expense-daily-report-failure")

        val result = runCatching {
            fixture.coordinator.saveDailyReportWithExpense(
                expense = expense,
                expenseDraftId = expense.id,
                pendingCapture = capture("capture-daily-report-failure"),
                findSavedExpense = { null },
                saveAccounting = { error("daily report transaction failure") }
            )
        }

        assertTrue(result.isFailure)
        assertEquals(0, promoter.calls)
        assertTrue(fixture.pendingFile("capture-daily-report-failure").exists())
        assertEquals(
            EvidenceFinalizationState.Prepared,
            fixture.journal.find("capture-daily-report-failure")?.state
        )
    }

    @Test
    fun preparedJournalResumesAfterRestartWhenSavedExpenseMatches() = runTest {
        val root = temporaryFolder.newFolder("restart")
        val pendingDirectory = File(root, "pending")
        val storedDirectory = File(root, "stored")
        val journalDirectory = File(root, "journal")
        val expense = expense("expense-restart")
        val receiptStore = ReceiptImageStore(pendingDirectory)
        receiptStore.prepareFile("capture-restart").writeBytes(jpegBytes())
        EvidenceFinalizationJournal(journalDirectory).prepare(
            captureId = "capture-restart",
            expenseDraftId = expense.id,
            expenseRecordId = expense.id,
            expenseFingerprint = expenseFingerprint(expense)
        )

        val restartedJournal = EvidenceFinalizationJournal(journalDirectory)
        val restartedStore = EvidenceFileStore(pendingDirectory, storedDirectory)
        val restartedCoordinator = EvidenceSaveCoordinator(restartedStore, restartedJournal)
        val recovery = restartedCoordinator.recoverPendingFinalizations(listOf(expense))

        assertEquals(listOf("capture-restart"), recovery.completedCaptureIds)
        assertTrue(recovery.failures.isEmpty())
        assertFalse(receiptStore.fileFor("capture-restart").exists())
        assertNotNull(restartedStore.resolve("capture-restart"))
        assertNull(restartedJournal.find("capture-restart"))
    }

    @Test
    fun matchingExpenseFingerprintWithoutEvidenceLinkIsNotTreatedAsSaved() = runTest {
        val fixture = fixture(promoter = RecordingPromoter())
        val expense = expense("expense-fingerprint-only")
        fixture.writePending("capture-fingerprint-only")
        fixture.journal.prepare(
            captureId = "capture-fingerprint-only",
            expenseDraftId = expense.id,
            expenseRecordId = expense.id,
            expenseFingerprint = expenseFingerprint(expense)
        )

        val recovery = fixture.coordinator.recoverPendingFinalizations(
            savedExpenses = listOf(expense),
            hasPersistedEvidenceLink = { _, _, _ -> false }
        )

        assertTrue(recovery.completedCaptureIds.isEmpty())
        assertTrue(recovery.failures.isEmpty())
        assertEquals(0, fixture.recordingPromoter().calls)
        assertEquals(
            EvidenceFinalizationState.Prepared,
            fixture.journal.find("capture-fingerprint-only")?.state
        )
        assertTrue(fixture.pendingFile("capture-fingerprint-only").exists())
    }

    @Test
    fun preparedRecoveryContinuesOnlyForMatchingEvidenceAndExpenseLink() = runTest {
        val fixture = fixture(promoter = RecordingPromoter())
        val expense = expense("existing-expense-with-link")
        fixture.writePending("capture-linked")
        fixture.journal.prepare(
            captureId = "capture-linked",
            expenseDraftId = expense.id,
            expenseRecordId = expense.id,
            expenseFingerprint = expenseFingerprint(expense)
        )
        var checkedArguments: List<String>? = null

        val recovery = fixture.coordinator.recoverPendingFinalizations(
            savedExpenses = listOf(expense),
            hasPersistedEvidenceLink = { expenseId, evidenceId, captureId ->
                checkedArguments = listOf(expenseId, evidenceId, captureId)
                expenseId == expense.id && evidenceId == "capture-linked" && captureId == "capture-linked"
            }
        )

        assertEquals(listOf(expense.id, "capture-linked", "capture-linked"), checkedArguments)
        assertEquals(listOf("capture-linked"), recovery.completedCaptureIds)
        assertEquals(1, fixture.recordingPromoter().calls)
        assertNull(fixture.journal.find("capture-linked"))
    }

    @Test
    fun accountingSavedJournalWithoutMatchingEvidenceLinkIsReportedAndRetained() = runTest {
        val fixture = fixture(promoter = RecordingPromoter())
        val expense = expense("expense-accounting-saved-no-link")
        fixture.writePending("capture-accounting-saved-no-link")
        fixture.journal.prepare(
            captureId = "capture-accounting-saved-no-link",
            expenseDraftId = expense.id,
            expenseRecordId = expense.id,
            expenseFingerprint = expenseFingerprint(expense)
        )
        fixture.journal.markAccountingSaved(
            captureId = "capture-accounting-saved-no-link",
            expenseRecordId = expense.id,
            expenseFingerprint = expenseFingerprint(expense)
        )

        val recovery = fixture.coordinator.recoverPendingFinalizations(
            savedExpenses = listOf(expense),
            hasPersistedEvidenceLink = { _, _, _ -> false }
        )

        assertTrue(recovery.completedCaptureIds.isEmpty())
        assertEquals(1, recovery.failures.size)
        assertTrue(recovery.failures.single().error is EvidenceJournalConflictException)
        assertNotNull(fixture.journal.find("capture-accounting-saved-no-link"))
        assertTrue(fixture.pendingFile("capture-accounting-saved-no-link").exists())
    }

    @Test
    fun finalizationFailureRetainsInputPendingAndJournalThenRecoverySucceeds() = runTest {
        val promoter = RecordingPromoter(failuresRemaining = 1)
        val fixture = fixture(promoter)
        fixture.writePending("capture-retry-after-failure")
        val inputState = InputStateViewModel(SavedStateHandle())
        val input = ExpenseInput(
            id = "expense-retry-after-failure",
            expenseDate = "2026-07-22",
            category = "food_purchase",
            supplierName = "テスト商店",
            amount = "1540",
            paymentMethod = "現金",
            memo = "保持テスト"
        )
        inputState.draftExpenseInputState.value = input
        val capture = capture("capture-retry-after-failure")
        assertTrue(
            inputState.applyReceiptOcr(
                ReceiptOcrApplyResult(capture, input.supplierName, input.expenseDate, input.amount),
                expectedCaptureId = capture.captureId
            )
        )
        val expense = expense(input.id)
        var repositoryCalls = 0

        val first = runCatching {
            fixture.coordinator.saveExpense(
                expense = expense,
                expenseDraftId = expense.id,
                pendingCapture = capture,
                findSavedExpense = { expense },
                saveAccounting = { repositoryCalls++ }
            )
        }

        assertTrue(first.exceptionOrNull() is EvidenceFinalizationAfterAccountingSaveException)
        assertEquals(1, repositoryCalls)
        assertEquals(input.id, inputState.draftExpenseInputState.value?.id)
        assertEquals(capture, inputState.pendingCaptureFor(input.id))
        assertTrue(fixture.pendingFile(capture.captureId).exists())
        assertEquals(
            EvidenceFinalizationState.AccountingSaved,
            fixture.journal.find(capture.captureId)?.state
        )

        val recovery = fixture.coordinator.recoverPendingFinalizations(listOf(expense))

        assertEquals(listOf(capture.captureId), recovery.completedCaptureIds)
        assertEquals(2, promoter.calls)
        assertEquals(1, repositoryCalls)
        assertNull(fixture.journal.find(capture.captureId))
    }

    @Test
    fun ownerUnknownPendingIsNotAppliedToAnotherExpense() = runTest {
        val promoter = RecordingPromoter()
        val fixture = fixture(promoter)
        val owner = expense("expense-owner")
        val another = expense("expense-another")
        fixture.journal.prepare(
            captureId = "capture-owner",
            expenseDraftId = owner.id,
            expenseRecordId = owner.id,
            expenseFingerprint = expenseFingerprint(owner)
        )

        val recovery = fixture.coordinator.recoverPendingFinalizations(listOf(another))

        assertTrue(recovery.completedCaptureIds.isEmpty())
        assertEquals(0, promoter.calls)
        assertNotNull(fixture.journal.find("capture-owner"))

        var repositoryCalls = 0
        val reassignment = runCatching {
            fixture.coordinator.saveExpense(
                expense = another,
                expenseDraftId = another.id,
                pendingCapture = capture("capture-owner"),
                findSavedExpense = { null },
                saveAccounting = { repositoryCalls++ }
            )
        }
        assertTrue(reassignment.exceptionOrNull() is EvidenceJournalConflictException)
        assertEquals(0, repositoryCalls)
        assertEquals(0, promoter.calls)
    }

    @Test
    fun corruptJournalIsQuarantinedWithoutPromotingPending() = runTest {
        val promoter = RecordingPromoter()
        val fixture = fixture(promoter)
        fixture.writePending("capture-corrupt-journal")
        val expense = expense("expense-corrupt-journal")
        fixture.journal.prepare(
            captureId = "capture-corrupt-journal",
            expenseDraftId = expense.id,
            expenseRecordId = expense.id,
            expenseFingerprint = expenseFingerprint(expense)
        )
        fixture.journal.journalFileFor("capture-corrupt-journal").writeText("corrupt")

        val recovery = fixture.coordinator.recoverPendingFinalizations(listOf(expense))

        assertTrue(recovery.completedCaptureIds.isEmpty())
        assertEquals(0, promoter.calls)
        assertTrue(fixture.pendingFile("capture-corrupt-journal").exists())
        assertFalse(fixture.storedFile("capture-corrupt-journal").exists())
        assertFalse(fixture.journal.journalFileFor("capture-corrupt-journal").exists())
        assertEquals(1, fixture.journal.quarantinedFiles().size)
        assertEquals(setOf("capture-corrupt-journal"), recovery.quarantinedCaptureIds)

        var repositoryCalls = 0
        val retry = runCatching {
            fixture.coordinator.saveExpense(
                expense = expense,
                expenseDraftId = expense.id,
                pendingCapture = capture("capture-corrupt-journal"),
                findSavedExpense = { expense },
                saveAccounting = { repositoryCalls++ }
            )
        }
        assertTrue(retry.exceptionOrNull() is EvidenceJournalCorruptException)
        assertEquals(0, repositoryCalls)
        assertEquals(0, promoter.calls)
    }

    @Test
    fun journalFilenameCaptureMismatchIsQuarantinedWithoutPromotion() = runTest {
        val promoter = RecordingPromoter()
        val fixture = fixture(promoter)
        val expense = expense("expense-journal-name-mismatch")
        fixture.journal.prepare(
            captureId = "capture-original-name",
            expenseDraftId = expense.id,
            expenseRecordId = expense.id,
            expenseFingerprint = expenseFingerprint(expense)
        )
        assertTrue(
            fixture.journal.journalFileFor("capture-original-name").renameTo(
                fixture.journal.journalFileFor("capture-renamed")
            )
        )

        val recovery = fixture.coordinator.recoverPendingFinalizations(listOf(expense))

        assertTrue(recovery.completedCaptureIds.isEmpty())
        assertEquals(0, promoter.calls)
        assertFalse(fixture.journal.journalFileFor("capture-renamed").exists())
        assertEquals(1, fixture.journal.quarantinedFiles().size)
    }

    @Test
    fun successfulFormalizationDeletesPendingAndJournalAndDoesNotDuplicate() = runTest {
        val fixture = fixture()
        fixture.writePending("capture-complete")
        val expense = expense("expense-complete")

        fixture.coordinator.saveExpense(
            expense = expense,
            expenseDraftId = expense.id,
            pendingCapture = capture("capture-complete"),
            findSavedExpense = { null },
            saveAccounting = {}
        )

        assertFalse(fixture.pendingFile("capture-complete").exists())
        assertNull(fixture.journal.find("capture-complete"))
        assertNotNull(fixture.store.resolve("capture-complete"))
        assertEquals(1, fixture.formalFiles().size)

        fixture.coordinator.saveExpense(
            expense = expense,
            expenseDraftId = expense.id,
            pendingCapture = capture("capture-complete"),
            findSavedExpense = { expense },
            saveAccounting = {}
        )

        assertEquals(1, fixture.formalFiles().size)
        assertNull(fixture.journal.find("capture-complete"))
    }

    @Test
    fun legacyUnlinkedProductionSaveApisAreNotExposed() {
        val publicMethodNames = EvidenceSaveCoordinator::class.java.methods.map { it.name }.toSet()

        assertFalse("saveExpense" in publicMethodNames)
        assertFalse("saveDailyReportWithExpense" in publicMethodNames)
        assertTrue("saveExpenseAndLinkEvidence" in publicMethodNames)
        assertTrue("saveDailyReportWithExpenseAndLinkEvidence" in publicMethodNames)
    }

    private fun fixture(promoter: EvidenceFilePromoter? = null): Fixture {
        val root = temporaryFolder.newFolder()
        val pendingDirectory = File(root, "pending")
        val storedDirectory = File(root, "stored")
        val receiptStore = ReceiptImageStore(pendingDirectory)
        val store = EvidenceFileStore(pendingDirectory, storedDirectory)
        val journal = EvidenceFinalizationJournal(File(root, "journal"))
        val selectedPromoter = promoter ?: store
        return Fixture(
            pendingDirectory = pendingDirectory,
            storedDirectory = storedDirectory,
            receiptStore = receiptStore,
            store = store,
            journal = journal,
            promoter = selectedPromoter,
            coordinator = EvidenceSaveCoordinator(selectedPromoter, journal)
        )
    }

    private fun expense(id: String): ExpenseRecord = ExpenseRecord(
        id = id,
        expenseDate = "2026-07-22",
        category = "food_purchase",
        supplierName = "テスト商店",
        amount = 1540L,
        paymentMethod = "現金",
        memo = "テストメモ",
        receiptId = null,
        sourceType = "manual",
        createdAt = 1L,
        updatedAt = 2L
    )

    private fun capture(captureId: String): ReceiptCaptureResult = ReceiptCaptureResult(
        captureId = captureId,
        localUri = "file:/pending/receipt_$captureId.jpg",
        capturedAt = 1L
    )

    private fun jpegBytes(): ByteArray = byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        1,
        2,
        3,
        0xFF.toByte(),
        0xD9.toByte()
    )

    private data class Fixture(
        val pendingDirectory: File,
        val storedDirectory: File,
        val receiptStore: ReceiptImageStore,
        val store: EvidenceFileStore,
        val journal: EvidenceFinalizationJournal,
        val promoter: EvidenceFilePromoter,
        val coordinator: EvidenceSaveCoordinator
    ) {
        fun writePending(captureId: String) {
            receiptStore.prepareFile(captureId).writeBytes(
                byteArrayOf(
                    0xFF.toByte(),
                    0xD8.toByte(),
                    1,
                    2,
                    3,
                    0xFF.toByte(),
                    0xD9.toByte()
                )
            )
        }

        fun pendingFile(captureId: String): File = receiptStore.fileFor(captureId)

        fun storedFile(captureId: String): File = store.fileFor(captureId)

        fun formalFiles(): List<File> = storedDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("evidence_") && it.name.endsWith(".jpg") }

        fun recordingPromoter(): RecordingPromoter = promoter as RecordingPromoter
    }

    private class RecordingPromoter(
        var failuresRemaining: Int = 0,
        private val events: MutableList<String> = mutableListOf()
    ) : EvidenceFilePromoter {
        var calls: Int = 0
            private set

        override fun promotePendingImage(evidenceId: String): EvidenceFileReference {
            calls++
            events += "promote"
            if (failuresRemaining > 0) {
                failuresRemaining--
                throw EvidenceFileStoreException("simulated finalization failure")
            }
            return EvidenceFileReference(
                evidenceId = evidenceId,
                localUri = "file:/stored/evidence_$evidenceId.jpg",
                byteSize = 1L,
                sha256 = "0".repeat(64),
                storedAt = 1L
            )
        }

        override fun inspectPendingImage(evidenceId: String): EvidenceFileReference =
            EvidenceFileReference(
                evidenceId = evidenceId,
                localUri = "file:/stored/evidence_$evidenceId.jpg",
                byteSize = 1L,
                sha256 = "0".repeat(64),
                storedAt = 1L
            )
    }
}

private suspend fun EvidenceSaveCoordinator.saveExpense(
    expense: ExpenseRecord,
    expenseDraftId: String,
    pendingCapture: ReceiptCaptureResult?,
    findSavedExpense: suspend (String) -> ExpenseRecord?,
    saveAccounting: suspend () -> Unit
) = saveExpenseAndLinkEvidence(
    expense = expense,
    expenseDraftId = expenseDraftId,
    pendingCapture = pendingCapture,
    findSavedExpense = findSavedExpense,
    hasPersistedEvidenceLink = { _, _, _ -> true },
    saveAccounting = { saveAccounting() },
    onPromoted = { _, _ -> }
)

private suspend fun EvidenceSaveCoordinator.saveDailyReportWithExpense(
    expense: ExpenseRecord?,
    expenseDraftId: String?,
    pendingCapture: ReceiptCaptureResult?,
    findSavedExpense: suspend (String) -> ExpenseRecord?,
    saveAccounting: suspend () -> Unit
) = saveDailyReportWithExpenseAndLinkEvidence(
    expense = expense,
    expenseDraftId = expenseDraftId,
    pendingCapture = pendingCapture,
    findSavedExpense = findSavedExpense,
    hasPersistedEvidenceLink = { _, _, _ -> true },
    saveAccounting = { saveAccounting() },
    onPromoted = { _, _ -> }
)

private suspend fun EvidenceSaveCoordinator.recoverPendingFinalizations(
    savedExpenses: List<ExpenseRecord>,
    onPromoted: suspend (EvidenceFinalizationEntry, EvidenceFileReference) -> Unit = { _, _ -> }
): EvidenceRecoveryResult = recoverPendingFinalizations(
    savedExpenses = savedExpenses,
    hasPersistedEvidenceLink = { _, _, _ -> true },
    onPromoted = onPromoted
)
