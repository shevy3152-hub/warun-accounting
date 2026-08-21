package com.warun.accounting.data.submission

import com.warun.accounting.data.export.MonthlyExportSnapshot
import com.warun.accounting.data.export.MonthlyExportSnapshotResult
import com.warun.accounting.data.export.MonthlyExportTestFixtures
import com.warun.accounting.data.local.ElectronicSubmissionRecord
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.export.MonthlyExportArtifactResult
import com.warun.accounting.export.MonthlyExportArtifacts
import com.warun.accounting.export.ExportCacheCleanupResult
import com.warun.accounting.export.ReceiptPdfArtifactResult
import java.io.File
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElectronicSubmissionGenerationCoordinatorTest {
    private val snapshot = MonthlyExportTestFixtures.snapshot()

    @Test
    fun successCreatesHistoryOnlyAfterBothXlsxAndPdfArtifactsComplete() = runBlocking {
        val repository = FakeSubmissionRepository()
        val artifacts = artifacts(withPdf = true, generationId = "generation-1")
        val coordinator = coordinator(
            repository = repository,
            artifactResult = success(artifacts)
        )

        val result = coordinator.generate(YearMonth.of(2026, 6))

        assertTrue(result is ElectronicSubmissionGenerationResult.Success)
        val record = repository.records.value.single()
        assertEquals("history-1", record.id)
        assertEquals("2026-06", record.targetMonth)
        assertEquals(artifacts.dailyReportXlsx.name, record.dailyReportFileName)
        assertEquals(artifacts.expenseDetailXlsx.name, record.expenseDetailFileName)
        assertEquals(artifacts.receiptPdf?.name, record.receiptPdfFileName)
        assertNull(record.submittedAt)
    }

    @Test
    fun noEvidenceStillCreatesTwoXlsxHistoryWithoutReceiptPdf() = runBlocking {
        val repository = FakeSubmissionRepository()
        val artifacts = artifacts(withPdf = false, generationId = "generation-no-evidence")
        val coordinator = coordinator(
            repository = repository,
            artifactResult = MonthlyExportArtifactResult.Success(
                artifacts,
                ReceiptPdfArtifactResult.NoEvidence
            )
        )

        val result = coordinator.generate(snapshot.targetMonth)

        assertTrue(result is ElectronicSubmissionGenerationResult.Success)
        assertNull(repository.records.value.single().receiptPdfFileName)
        assertEquals(2, artifacts.files.size)
    }

    @Test
    fun snapshotFailureDoesNotGenerateArtifactsOrCreateHistory() = runBlocking {
        val repository = FakeSubmissionRepository()
        var artifactCalled = false
        val coordinator = ElectronicSubmissionGenerationCoordinator(
            repository = repository,
            loadSnapshot = {
                MonthlyExportSnapshotResult.DataAccessFailure("database", "unavailable")
            },
            generateArtifacts = {
                artifactCalled = true
                success(artifacts(true, "unused"))
            },
            cleanupArtifacts = { true },
            cleanupExpired = { ExportCacheCleanupResult() },
            now = { 100L },
            newId = { "unused" },
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = coordinator.generate(snapshot.targetMonth)

        assertTrue(result is ElectronicSubmissionGenerationResult.SnapshotFailure)
        assertFalse(artifactCalled)
        assertTrue(repository.records.value.isEmpty())
    }

    @Test
    fun artifactFailureDoesNotCreateHistory() = runBlocking {
        val repository = FakeSubmissionRepository()
        val failure = MonthlyExportArtifactResult.Failure(
            exceptionType = "writer",
            message = "failed",
            generationCleanupSucceeded = true
        )
        val result = coordinator(repository, failure).generate(snapshot.targetMonth)

        assertTrue(result is ElectronicSubmissionGenerationResult.ArtifactFailure)
        assertTrue(repository.records.value.isEmpty())
    }

    @Test
    fun historyInsertFailureCleansOnlyGeneratedArtifacts() = runBlocking {
        val repository = FakeSubmissionRepository(failCreate = true)
        val artifacts = artifacts(true, "generation-cleanup")
        var cleaned: MonthlyExportArtifacts? = null
        val coordinator = coordinator(repository, success(artifacts)) { value ->
            cleaned = value
            true
        }

        val result = coordinator.generate(snapshot.targetMonth)

        assertTrue(result is ElectronicSubmissionGenerationResult.HistoryFailure)
        assertTrue((result as ElectronicSubmissionGenerationResult.HistoryFailure).cleanupSucceeded)
        assertEquals(artifacts, cleaned)
        assertTrue(repository.records.value.isEmpty())
    }

    @Test
    fun repeatedMonthGenerationCreatesUniqueImmutableHistoryRows() = runBlocking {
        val repository = FakeSubmissionRepository()
        var sequence = 0
        val coordinator = ElectronicSubmissionGenerationCoordinator(
            repository = repository,
            loadSnapshot = { MonthlyExportSnapshotResult.Success(snapshot) },
            generateArtifacts = {
                sequence += 1
                success(artifacts(false, "generation-$sequence"))
            },
            cleanupArtifacts = { true },
            cleanupExpired = { ExportCacheCleanupResult() },
            now = { sequence.toLong() },
            newId = { "history-$sequence" },
            ioDispatcher = Dispatchers.Unconfined
        )

        coordinator.generate(snapshot.targetMonth)
        coordinator.generate(snapshot.targetMonth)

        assertEquals(listOf("history-1", "history-2"), repository.records.value.map { it.id })
        assertEquals(2, repository.records.value.map { it.id }.distinct().size)
    }

    @Test
    fun prepareSnapshotArtifactsAndHistoryRunOnInjectedIoDispatcher() = runTest {
        val io = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "export-io-test")
        }.asCoroutineDispatcher()
        val stages = mutableListOf<String>()
        fun assertIo(stage: String) {
            assertEquals("export-io-test", Thread.currentThread().name)
            stages += stage
        }
        val repository = FakeSubmissionRepository(onCreate = { assertIo("history") })
        val coordinator = ElectronicSubmissionGenerationCoordinator(
            repository = repository,
            loadSnapshot = {
                assertIo("snapshot")
                MonthlyExportSnapshotResult.Success(snapshot)
            },
            generateArtifacts = {
                assertIo("artifacts")
                success(artifacts(false, "generation-io"))
            },
            cleanupArtifacts = { true },
            cleanupExpired = {
                assertIo("prepare")
                ExportCacheCleanupResult()
            },
            now = { 1_000L },
            newId = { "history-io" },
            ioDispatcher = io
        )

        try {
            coordinator.prepare()
            coordinator.generate(snapshot.targetMonth)
            assertEquals(listOf("prepare", "snapshot", "artifacts", "history"), stages)
        } finally {
            io.close()
        }
    }

    private fun coordinator(
        repository: FakeSubmissionRepository,
        artifactResult: MonthlyExportArtifactResult,
        cleanup: (MonthlyExportArtifacts) -> Boolean = { true }
    ) = ElectronicSubmissionGenerationCoordinator(
        repository = repository,
        loadSnapshot = { MonthlyExportSnapshotResult.Success(snapshot) },
        generateArtifacts = { artifactResult },
        cleanupArtifacts = cleanup,
        cleanupExpired = { ExportCacheCleanupResult() },
        now = { 1_000L },
        newId = { "history-1" },
        ioDispatcher = Dispatchers.Unconfined
    )

    private fun success(artifacts: MonthlyExportArtifacts) = MonthlyExportArtifactResult.Success(
        artifacts = artifacts,
        receiptPdf = artifacts.receiptPdf?.let {
            ReceiptPdfArtifactResult.Generated(it, 1)
        } ?: ReceiptPdfArtifactResult.NoEvidence
    )

    private fun artifacts(withPdf: Boolean, generationId: String) = MonthlyExportArtifacts(
        generationId = generationId,
        dailyReportXlsx = File("build/$generationId/2026年6月_日報.xlsx"),
        expenseDetailXlsx = File("build/$generationId/2026年6月_支出明細.xlsx"),
        receiptPdf = File("build/$generationId/2026年6月_レシート.pdf").takeIf { withPdf }
    )
}

internal class FakeSubmissionRepository(
    private val failCreate: Boolean = false,
    private val onCreate: suspend () -> Unit = {}
) : ElectronicSubmissionRepository {
    val records = MutableStateFlow<List<ElectronicSubmissionRecord>>(emptyList())
    val papers = MutableStateFlow<List<MonthlySubmission>>(emptyList())
    var noteUpdateCalls = 0
    var markSubmittedCalls = 0

    override fun observeRecords(): Flow<List<ElectronicSubmissionRecord>> = records

    override fun observeRecordsByMonth(targetMonth: String): Flow<List<ElectronicSubmissionRecord>> =
        MutableStateFlow(records.value.filter { it.targetMonth == targetMonth })

    override fun observePaperRecords(): Flow<List<MonthlySubmission>> = papers

    override suspend fun getRecord(id: String): ElectronicSubmissionRecord? =
        records.value.singleOrNull { it.id == id }

    override suspend fun createRecord(record: ElectronicSubmissionRecord) {
        onCreate()
        if (failCreate) error("history insert failed")
        check(records.value.none { it.id == record.id })
        records.value = records.value + record
    }

    override suspend fun updateNote(id: String, note: String?, updatedAt: Long): Boolean {
        noteUpdateCalls += 1
        val current = records.value.singleOrNull { it.id == id } ?: return false
        records.value = records.value.map {
            if (it.id == id) current.copy(note = note, updatedAt = updatedAt) else it
        }
        return true
    }

    override suspend fun markSubmitted(id: String, submittedAt: Long, updatedAt: Long): Boolean {
        markSubmittedCalls += 1
        val current = records.value.singleOrNull { it.id == id } ?: return false
        if (current.status != "not_submitted" || current.submittedAt != null) return false
        records.value = records.value.map {
            if (it.id == id) current.copy(
                status = "submitted",
                submittedAt = submittedAt,
                updatedAt = updatedAt
            ) else it
        }
        return true
    }
}
