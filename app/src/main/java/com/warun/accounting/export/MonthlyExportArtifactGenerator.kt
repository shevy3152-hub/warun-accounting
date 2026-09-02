package com.warun.accounting.export

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.warun.accounting.data.export.MonthlyExportSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

data class MonthlyExportArtifacts(
    val generationId: String,
    val dailyReportXlsx: File,
    val expenseDetailXlsx: File,
    val receiptPdf: File?
) {
    val files: List<File>
        get() = listOfNotNull(dailyReportXlsx, expenseDetailXlsx, receiptPdf)

    val totalBytes: Long
        get() = files.sumOf { it.length() }
}

sealed interface ReceiptPdfArtifactResult {
    data class Generated(val file: File, val pageCount: Int) : ReceiptPdfArtifactResult
    data object NoEvidence : ReceiptPdfArtifactResult
}

sealed interface MonthlyExportArtifactResult {
    data class Success(
        val artifacts: MonthlyExportArtifacts,
        val receiptPdf: ReceiptPdfArtifactResult
    ) : MonthlyExportArtifactResult

    data class Failure(
        val exceptionType: String,
        val message: String?,
        val evidenceId: String? = null,
        val receiptFailureReason: ReceiptPdfFailureReason? = null,
        val generationCleanupSucceeded: Boolean
    ) : MonthlyExportArtifactResult
}

/**
 * Creates one isolated, disposable generation in app cache. It never writes accounting records or
 * Evidence and removes only its own UUID directory if any artifact fails.
 */
class MonthlyExportArtifactGenerator @Inject constructor(
    @ApplicationContext context: Context,
    private val receiptPdfWriter: ReceiptEvidencePdfWriter
) {
    private val exportRoot = File(context.cacheDir, ExportCacheContract.RootDirectory)

    fun generate(snapshot: MonthlyExportSnapshot): MonthlyExportArtifactResult {
        val generationId = UUID.randomUUID().toString()
        val generationDirectory = File(exportRoot, generationId)
        return try {
            val safeDirectory = safeGenerationDirectory(generationId)
            check(safeDirectory.mkdirs()) { "提出ファイルの一時領域を作成できません" }

            val daily = writeXlsx(
                safeDirectory,
                snapshot.dailyReportFileName
            ) { output -> MonthlyExportXlsxWriter.writeDailyReport(snapshot, output) }
            val expense = writeXlsx(
                safeDirectory,
                snapshot.expenseDetailFileName
            ) { output -> MonthlyExportXlsxWriter.writeExpenseDetail(snapshot, output) }

            val receiptEvidenceCount = snapshot.storedEvidence.size + snapshot.fixedCostStoredEvidence.size
            val receiptResult = if (receiptEvidenceCount == 0) {
                ReceiptPdfArtifactResult.NoEvidence
            } else {
                val final = File(safeDirectory, receiptFileName(snapshot))
                val temp = File(safeDirectory, final.name + ExportCacheContract.TempSuffix)
                val writtenPages = receiptPdfWriter.write(snapshot, temp)
                moveAtomically(temp, final)
                ReceiptPdfArtifactResult.Generated(final, writtenPages)
            }
            MonthlyExportArtifactResult.Success(
                artifacts = MonthlyExportArtifacts(
                    generationId = generationId,
                    dailyReportXlsx = daily,
                    expenseDetailXlsx = expense,
                    receiptPdf = (receiptResult as? ReceiptPdfArtifactResult.Generated)?.file
                ),
                receiptPdf = receiptResult
            ).also { safeDirectory.setLastModified(System.currentTimeMillis()) }
        } catch (error: CancellationException) {
            runCatching { cleanupOwnGeneration(generationDirectory) }
            throw error
        } catch (error: Throwable) {
            val cleanupSucceeded = runCatching {
                cleanupOwnGeneration(generationDirectory)
            }.getOrDefault(false)
            val receiptError = error as? ReceiptPdfGenerationException
            MonthlyExportArtifactResult.Failure(
                exceptionType = error::class.qualifiedName ?: error::class.simpleName.orEmpty(),
                message = error.message,
                evidenceId = receiptError?.evidenceId,
                receiptFailureReason = receiptError?.reason,
                generationCleanupSucceeded = cleanupSucceeded
            )
        }
    }

    /** Removes only the UUID-scoped cache directory belonging to this generation. */
    fun cleanup(artifacts: MonthlyExportArtifacts): Boolean = runCatching {
        cleanupOwnGeneration(safeGenerationDirectory(artifacts.generationId))
    }.getOrDefault(false)

    /**
     * Deletes only expired UUID generations directly below the canonical export root. Unknown
     * files and directories are left untouched. Completed exports remain for 24 hours so a
     * FileProvider receiver can finish reading after the app UI changes month or is destroyed.
     */
    fun cleanupExpired(nowMillis: Long = System.currentTimeMillis()): ExportCacheCleanupResult {
        val root = exportRoot.canonicalFile
        if (!root.exists()) return ExportCacheCleanupResult()
        if (!root.isDirectory) {
            return ExportCacheCleanupResult(failedEntries = listOf(root.name))
        }
        var deleted = 0
        val failures = mutableListOf<String>()
        root.listFiles().orEmpty().forEach { candidate ->
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile != root ||
                !ExportCacheContract.isGenerationId(canonical.name) ||
                !canonical.isDirectory
            ) return@forEach
            val age = nowMillis - canonical.lastModified()
            if (age < ExportCacheContract.RetentionMillis) return@forEach
            if (cleanupOwnGeneration(canonical)) deleted += 1 else failures += canonical.name
        }
        return ExportCacheCleanupResult(deleted, failures)
    }

    private fun writeXlsx(
        generationDirectory: File,
        fileName: String,
        writer: (FileOutputStream) -> Unit
    ): File {
        val final = File(generationDirectory, fileName)
        val temp = File(generationDirectory, fileName + ExportCacheContract.TempSuffix)
        FileOutputStream(temp).use { output ->
            writer(output)
            output.flush()
            output.fd.sync()
        }
        verifyXlsx(temp)
        moveAtomically(temp, final)
        return final
    }

    private fun verifyXlsx(file: File) {
        check(file.isFile && file.length() > 0L) { "Excelファイルの検証に失敗しました" }
        ZipFile(file).use { zip ->
            RequiredXlsxEntries.forEach { entry ->
                check(zip.getEntry(entry) != null) { "Excelファイルの構造が不正です: $entry" }
            }
        }
    }

    private fun verifyPdf(file: File, expectedPages: Int) {
        check(file.isFile && file.length() > 0L) { "PDFファイルの検証に失敗しました" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                check(renderer.pageCount == expectedPages) {
                    "PDFページ数がEvidence件数と一致しません"
                }
            }
        }
    }

    private fun safeGenerationDirectory(generationId: String): File {
        check(ExportCacheContract.isGenerationId(generationId))
        val root = exportRoot.canonicalFile
        val child = File(root, generationId).canonicalFile
        check(child.parentFile == root && child != root) { "提出ファイル領域が不正です" }
        if (!root.exists()) check(root.mkdirs()) { "提出ファイル領域を作成できません" }
        check(root.isDirectory) { "提出ファイル領域がディレクトリではありません" }
        return child
    }

    private fun cleanupOwnGeneration(directory: File): Boolean {
        val root = exportRoot.canonicalFile
        val child = directory.canonicalFile
        if (child.parentFile != root || child == root) return false
        if (!child.exists()) return true
        return child.deleteRecursively() && !child.exists()
    }

    private fun moveAtomically(source: File, destination: File) {
        check(!destination.exists()) { "同名の提出ファイルが既に存在します" }
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun receiptFileName(snapshot: MonthlyExportSnapshot): String =
        "${snapshot.targetMonth.year}年${snapshot.targetMonth.monthValue}月_レシート.pdf"

    private companion object {
        val RequiredXlsxEntries = listOf(
            "[Content_Types].xml",
            "xl/workbook.xml",
            "xl/worksheets/sheet1.xml"
        )
    }
}

data class ExportCacheCleanupResult(
    val deletedGenerationCount: Int = 0,
    val failedEntries: List<String> = emptyList()
) {
    val succeeded: Boolean get() = failedEntries.isEmpty()
}

object ExportCacheContract {
    const val RootDirectory = "submission-exports"
    const val TempSuffix = ".tmp"
    const val FileProviderAuthoritySuffix = ".submission-exports"
    const val MyKomonSoftTotalBytesLimit = 90_000_000L
    const val MyKomonHardTotalBytesLimit = 100_000_000L
    // Share targets may keep reading FileProvider URIs after this process or ViewModel is gone.
    const val RetentionMillis = 24L * 60L * 60L * 1_000L

    fun requireCompletedExport(cacheDirectory: File, file: File) {
        val root = File(cacheDirectory, RootDirectory).canonicalFile
        val candidate = file.canonicalFile
        val generation = candidate.parentFile
        require(generation?.parentFile == root && isGenerationId(generation.name))
        require(candidate.isFile && candidate.length() > 0L)
        require(!candidate.name.endsWith(TempSuffix))
        require(candidate.extension.lowercase() in setOf("xlsx", "pdf"))
    }

    /** Renews the 24-hour lease immediately before SAF or FileProvider starts reading. */
    fun renewAccessLease(
        cacheDirectory: File,
        files: List<File>,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        require(files.isNotEmpty())
        files.forEach { requireCompletedExport(cacheDirectory, it) }
        val generations = files.map { requireNotNull(it.canonicalFile.parentFile) }.distinct()
        require(generations.size == 1)
        require(generations.single().setLastModified(nowMillis)) {
            "提出ファイルの共有保持期限を更新できません"
        }
    }

    internal fun isGenerationId(value: String): Boolean = GenerationIdPattern.matches(value)

    private val GenerationIdPattern = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )
}
