package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.warun.accounting.data.local.ElectronicSubmissionRecord
import com.warun.accounting.data.local.ElectronicSubmissionStatus
import com.warun.accounting.data.submission.CompletedElectronicSubmissionGeneration
import com.warun.accounting.data.submission.ElectronicSubmissionGenerationResult
import com.warun.accounting.data.submission.FakeSubmissionRepository
import com.warun.accounting.export.MonthlyExportArtifactResult
import com.warun.accounting.export.MonthlyExportArtifacts
import com.warun.accounting.export.ExportCacheCleanupResult
import com.warun.accounting.export.ReceiptPdfArtifactResult
import com.warun.accounting.export.SafExportCopyResult
import java.io.File
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class ElectronicSubmissionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun successPublishesOnlyCurrentInMemoryFilesAndNoEvidenceWarning() = runTest(dispatcher) {
        val repository = FakeSubmissionRepository()
        val artifacts = artifacts(withPdf = false)
        val viewModel = viewModel(
            repository,
            ElectronicSubmissionGenerationResult.Success(
                CompletedElectronicSubmissionGeneration(
                    record(),
                    artifacts,
                    ReceiptPdfArtifactResult.NoEvidence
                )
            )
        )

        advanceUntilIdle()
        viewModel.generate()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isGenerating)
        assertEquals(2, state.activeGeneration?.files?.size)
        assertFalse(requireNotNull(state.activeGeneration).hasReceiptPdf)
        assertTrue(state.message.orEmpty().contains("レシートPDFは作成していません"))
    }

    @Test
    fun snapshotArtifactAndHistoryFailuresNeverPublishFiles() = runTest(dispatcher) {
        val failures = listOf(
            ElectronicSubmissionGenerationResult.SnapshotFailure(
                com.warun.accounting.data.export.MonthlyExportSnapshotResult.DataAccessFailure(
                    "database",
                    "failed"
                )
            ),
            ElectronicSubmissionGenerationResult.ArtifactFailure(
                MonthlyExportArtifactResult.Failure(
                    exceptionType = "writer",
                    message = "failed",
                    generationCleanupSucceeded = true
                )
            ),
            ElectronicSubmissionGenerationResult.HistoryFailure(
                exceptionType = "sqlite",
                message = "failed",
                cleanupSucceeded = true
            )
        )

        failures.forEach { failure ->
            val viewModel = viewModel(FakeSubmissionRepository(), failure)
            advanceUntilIdle()
            viewModel.generate()
            advanceUntilIdle()
            assertNull(viewModel.state.value.activeGeneration)
            assertTrue(viewModel.state.value.isError)
        }
    }

    @Test
    fun saveAndShareRequestsEmitOnlyActiveGenerationFiles() = runTest(dispatcher) {
        val artifacts = artifacts(withPdf = true)
        val viewModel = viewModel(
            FakeSubmissionRepository(),
            ElectronicSubmissionGenerationResult.Success(
                CompletedElectronicSubmissionGeneration(
                    record(),
                    artifacts,
                    ReceiptPdfArtifactResult.Generated(requireNotNull(artifacts.receiptPdf), 1)
                )
            )
        )
        advanceUntilIdle()
        viewModel.generate()
        advanceUntilIdle()

        viewModel.requestSave(artifacts.dailyReportXlsx.name)
        val save = viewModel.effects.first() as ElectronicSubmissionUiEffect.ChooseSaveDestination
        assertEquals(artifacts.dailyReportXlsx, save.artifact.file)

        viewModel.requestShare()
        val share = viewModel.effects.first() as ElectronicSubmissionUiEffect.ShareFiles
        assertEquals(artifacts.files, share.files)
    }

    @Test
    fun markSubmittedIsOneWayAndRepeatedTapCallsRepositoryOnce() = runTest(dispatcher) {
        val repository = FakeSubmissionRepository()
        repository.records.value = listOf(record())
        val viewModel = viewModel(repository, generationFailure())
        advanceUntilIdle()

        viewModel.markSubmitted("history-1")
        viewModel.markSubmitted("history-1")
        advanceUntilIdle()

        assertEquals(1, repository.markSubmittedCalls)
        assertEquals(ElectronicSubmissionStatus.Submitted, repository.records.value.single().status)
        viewModel.markSubmitted("history-1")
        advanceUntilIdle()
        assertEquals(1, repository.markSubmittedCalls)
    }

    @Test
    fun noteIsBoundedNormalizedAndLimitedUpdatePreservesGenerationFields() = runTest(dispatcher) {
        val repository = FakeSubmissionRepository()
        val original = record()
        repository.records.value = listOf(original)
        val viewModel = viewModel(repository, generationFailure())
        advanceUntilIdle()

        viewModel.updateNoteDraft(original.id, "  MyKomonへ\n  手動提出  ")
        viewModel.saveNote(original.id)
        advanceUntilIdle()

        val updated = repository.records.value.single()
        assertEquals("MyKomonへ 手動提出", updated.note)
        assertEquals(original.targetMonth, updated.targetMonth)
        assertEquals(original.generatedAt, updated.generatedAt)
        assertEquals(original.dailyReportFileName, updated.dailyReportFileName)
        assertEquals(1, repository.noteUpdateCalls)
    }

    @Test
    fun recreatedViewModelRestoresMonthButNeverRestoresCacheFiles() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("electronic_submission_selected_month" to "2026-05"))
        val result = ElectronicSubmissionGenerationResult.Success(
            CompletedElectronicSubmissionGeneration(
                record(targetMonth = "2026-05"),
                artifacts(withPdf = false),
                ReceiptPdfArtifactResult.NoEvidence
            )
        )
        val first = viewModel(FakeSubmissionRepository(), result, handle)
        advanceUntilIdle()
        first.generate()
        advanceUntilIdle()
        assertTrue(first.state.value.activeGeneration != null)

        val recreated = viewModel(FakeSubmissionRepository(), generationFailure(), handle)
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 5), recreated.state.value.selectedMonth)
        assertNull(recreated.state.value.activeGeneration)
    }

    @Test
    fun completedSharedFilesRemainAfterMonthChangeAndViewModelRecreation() = runTest(dispatcher) {
        val directory = Files.createTempDirectory("submission-retention").toFile()
        try {
            val daily = File(directory, "2026年6月_日報.xlsx").apply { writeText("daily") }
            val expense = File(directory, "2026年6月_支出明細.xlsx").apply { writeText("expense") }
            val retained = MonthlyExportArtifacts(
                generationId = "11111111-1111-1111-1111-111111111111",
                dailyReportXlsx = daily,
                expenseDetailXlsx = expense,
                receiptPdf = null
            )
            val result = ElectronicSubmissionGenerationResult.Success(
                CompletedElectronicSubmissionGeneration(
                    record(),
                    retained,
                    ReceiptPdfArtifactResult.NoEvidence
                )
            )
            val first = viewModel(FakeSubmissionRepository(), result)
            advanceUntilIdle()
            first.generate()
            advanceUntilIdle()
            first.selectMonth(YearMonth.of(2026, 7))

            val recreated = viewModel(FakeSubmissionRepository(), generationFailure())
            advanceUntilIdle()
            assertNull(recreated.state.value.activeGeneration)
            assertTrue(daily.isFile)
            assertTrue(expense.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun processRestartRunsPreparationAndCleanupFailureIsVisible() = runTest(dispatcher) {
        var prepares = 0
        val prepare = suspend {
            prepares += 1
            ExportCacheCleanupResult(failedEntries = listOf("expired-generation"))
        }
        val first = viewModel(FakeSubmissionRepository(), generationFailure(), prepare = prepare)
        val recreated = viewModel(FakeSubmissionRepository(), generationFailure(), prepare = prepare)
        advanceUntilIdle()

        assertEquals(2, prepares)
        assertFalse(first.state.value.isPreparing)
        assertTrue(recreated.state.value.message.orEmpty().contains("清掃できませんでした"))
        assertTrue(recreated.state.value.isError)
    }

    @Test
    fun safCopyIoBoundaryRunsOnInjectedDispatcher() = runTest(dispatcher) {
        val io = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "saf-io-test")
        }.asCoroutineDispatcher()
        try {
            val copyThread = runElectronicSubmissionIo(io) { Thread.currentThread().name }
            assertEquals("saf-io-test", copyThread)
        } finally {
            io.close()
        }
    }

    private fun viewModel(
        repository: FakeSubmissionRepository,
        result: ElectronicSubmissionGenerationResult,
        handle: SavedStateHandle = SavedStateHandle(),
        prepare: suspend () -> ExportCacheCleanupResult = { ExportCacheCleanupResult() },
        copy: (File, android.net.Uri) -> SafExportCopyResult = { _, _ ->
            SafExportCopyResult.Success(1L, "hash")
        },
        ioDispatcher: CoroutineDispatcher = dispatcher
    ) = ElectronicSubmissionViewModel(
        savedStateHandle = handle,
        repository = repository,
        prepareExports = prepare,
        generateSubmission = { result },
        copyToSaf = copy,
        now = { 2_000L },
        ioDispatcher = ioDispatcher
    )

    private fun record(targetMonth: String = "2026-06") = ElectronicSubmissionRecord(
        id = "history-1",
        targetMonth = targetMonth,
        generatedAt = 1_000L,
        dailyReportFileName = "2026年6月_日報.xlsx",
        expenseDetailFileName = "2026年6月_支出明細.xlsx",
        receiptPdfFileName = null,
        status = ElectronicSubmissionStatus.NotSubmitted,
        submittedAt = null,
        note = null,
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    private fun artifacts(withPdf: Boolean) = MonthlyExportArtifacts(
        generationId = "11111111-1111-1111-1111-111111111111",
        dailyReportXlsx = File("build/generation/2026年6月_日報.xlsx"),
        expenseDetailXlsx = File("build/generation/2026年6月_支出明細.xlsx"),
        receiptPdf = File("build/generation/2026年6月_レシート.pdf").takeIf { withPdf }
    )

    private fun generationFailure() = ElectronicSubmissionGenerationResult.HistoryFailure(
        exceptionType = "unused",
        message = null,
        cleanupSucceeded = true
    )
}
