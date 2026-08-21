package com.warun.accounting.data.submission

import com.warun.accounting.data.export.MonthlyExportSnapshot
import com.warun.accounting.data.export.MonthlyExportSnapshotProvider
import com.warun.accounting.data.export.MonthlyExportSnapshotResult
import com.warun.accounting.data.local.ElectronicSubmissionRecord
import com.warun.accounting.data.local.ElectronicSubmissionStatus
import com.warun.accounting.export.MonthlyExportArtifactGenerator
import com.warun.accounting.export.MonthlyExportArtifactResult
import com.warun.accounting.export.MonthlyExportArtifacts
import com.warun.accounting.export.ExportCacheCleanupResult
import com.warun.accounting.export.ReceiptPdfArtifactResult
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CompletedElectronicSubmissionGeneration(
    val record: ElectronicSubmissionRecord,
    val artifacts: MonthlyExportArtifacts,
    val receiptPdf: ReceiptPdfArtifactResult
)

sealed interface ElectronicSubmissionGenerationResult {
    data class Success(
        val generation: CompletedElectronicSubmissionGeneration
    ) : ElectronicSubmissionGenerationResult

    data class SnapshotFailure(
        val result: MonthlyExportSnapshotResult
    ) : ElectronicSubmissionGenerationResult

    data class ArtifactFailure(
        val failure: MonthlyExportArtifactResult.Failure
    ) : ElectronicSubmissionGenerationResult

    data class HistoryFailure(
        val exceptionType: String,
        val message: String?,
        val cleanupSucceeded: Boolean
    ) : ElectronicSubmissionGenerationResult
}

/**
 * Coordinates read-only monthly export generation and the separate submission-history insert.
 * Accounting source records are never written. A history failure removes only that generation's
 * cache directory, so no files without a matching history record remain active in the UI.
 */
class ElectronicSubmissionGenerationCoordinator internal constructor(
    private val repository: ElectronicSubmissionRepository,
    private val loadSnapshot: suspend (YearMonth) -> MonthlyExportSnapshotResult,
    private val generateArtifacts: (MonthlyExportSnapshot) -> MonthlyExportArtifactResult,
    private val cleanupArtifacts: (MonthlyExportArtifacts) -> Boolean,
    private val cleanupExpired: (Long) -> ExportCacheCleanupResult,
    private val now: () -> Long,
    private val newId: () -> String,
    private val ioDispatcher: CoroutineDispatcher
) {
    @Inject
    constructor(
        snapshotProvider: MonthlyExportSnapshotProvider,
        artifactGenerator: MonthlyExportArtifactGenerator,
        repository: ElectronicSubmissionRepository
    ) : this(
        repository = repository,
        loadSnapshot = snapshotProvider::load,
        generateArtifacts = artifactGenerator::generate,
        cleanupArtifacts = artifactGenerator::cleanup,
        cleanupExpired = artifactGenerator::cleanupExpired,
        now = System::currentTimeMillis,
        newId = { UUID.randomUUID().toString() },
        ioDispatcher = Dispatchers.IO
    )

    private val mutex = Mutex()

    suspend fun prepare(): ExportCacheCleanupResult = withContext(ioDispatcher) {
        mutex.withLock { cleanupExpired(now()) }
    }

    suspend fun generate(targetMonth: YearMonth): ElectronicSubmissionGenerationResult =
        withContext(ioDispatcher) {
            mutex.withLock { generateLocked(targetMonth) }
        }

    private suspend fun generateLocked(
        targetMonth: YearMonth
    ): ElectronicSubmissionGenerationResult {
        val snapshotResult = loadSnapshot(targetMonth)
        val snapshot = (snapshotResult as? MonthlyExportSnapshotResult.Success)?.snapshot
            ?: return ElectronicSubmissionGenerationResult.SnapshotFailure(snapshotResult)
        val artifactResult = generateArtifacts(snapshot)
        val success = artifactResult as? MonthlyExportArtifactResult.Success
            ?: return ElectronicSubmissionGenerationResult.ArtifactFailure(
                artifactResult as MonthlyExportArtifactResult.Failure
            )

        val generatedAt = now()
        val record = ElectronicSubmissionRecord(
            id = newId(),
            targetMonth = targetMonth.toString(),
            generatedAt = generatedAt,
            dailyReportFileName = success.artifacts.dailyReportXlsx.name,
            expenseDetailFileName = success.artifacts.expenseDetailXlsx.name,
            receiptPdfFileName = success.artifacts.receiptPdf?.name,
            status = ElectronicSubmissionStatus.NotSubmitted,
            submittedAt = null,
            note = null,
            createdAt = generatedAt,
            updatedAt = generatedAt
        )
        return try {
            repository.createRecord(record)
            ElectronicSubmissionGenerationResult.Success(
                CompletedElectronicSubmissionGeneration(record, success.artifacts, success.receiptPdf)
            )
        } catch (error: CancellationException) {
            cleanupArtifacts(success.artifacts)
            throw error
        } catch (error: Exception) {
            ElectronicSubmissionGenerationResult.HistoryFailure(
                exceptionType = error::class.qualifiedName ?: error::class.simpleName.orEmpty(),
                message = error.message,
                cleanupSucceeded = cleanupArtifacts(success.artifacts)
            )
        }
    }
}
