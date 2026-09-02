package com.warun.accounting.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.export.DailyReportExportTotals
import com.warun.accounting.data.export.ExpenseDetailExportRow
import com.warun.accounting.data.export.MonthlyExportSnapshot
import com.warun.accounting.data.export.StoredEvidenceExportItem
import com.warun.accounting.evidence.EvidenceFileReference
import com.warun.accounting.evidence.EvidenceFileStore
import com.warun.accounting.evidence.FixedCostEvidenceFileStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonthlyExportPhase3InstrumentedTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var pending: File
    private lateinit var stored: File
    private lateinit var store: EvidenceFileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "phase3-export-test-${System.nanoTime()}")
        pending = File(root, "pending").apply { check(mkdirs()) }
        stored = File(root, "stored").apply { check(mkdirs()) }
        store = EvidenceFileStore(pending, stored)
        File(context.cacheDir, ExportCacheContract.RootDirectory).deleteRecursively()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        File(context.cacheDir, ExportCacheContract.RootDirectory).deleteRecursively()
    }

    @Test
    fun pdfPageCountMatchesEvidenceAndRenderedPageContainsHeaderAndFittedImage() {
        val first = saveEvidence("ev-portrait", 400, 800, Color.RED)
        val second = saveEvidence("ev-landscape", 800, 400, Color.BLUE)
        val snapshot = snapshot(listOf(first, second))
        val pdf = File(root, "receipt.pdf")

        val count = ReceiptEvidencePdfWriter(store).write(snapshot, pdf)

        assertEquals(2, count)
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(2, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        rendered.eraseColor(Color.WHITE)
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val layout = ReceiptPdfPageLayoutPlanner.plan(400, 800, 3)
                        val center = rendered.getPixel(
                            ((layout.imageBounds.left + layout.imageBounds.right) / 2f).toInt(),
                            ((layout.imageBounds.top + layout.imageBounds.bottom) / 2f).toInt()
                        )
                        assertTrue(Color.red(center) > 180)
                        assertTrue(Color.green(center) < 100)
                        assertTrue(hasDarkPixel(rendered, 30, 35, 350, 105))
                        assertEquals(Color.WHITE, rendered.getPixel(10, 10))
                    } finally {
                        rendered.recycle()
                    }
                }
                renderer.openPage(1).use { page ->
                    val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val layout = ReceiptPdfPageLayoutPlanner.plan(800, 400, 3)
                        val center = rendered.getPixel(
                            ((layout.imageBounds.left + layout.imageBounds.right) / 2f).toInt(),
                            ((layout.imageBounds.top + layout.imageBounds.bottom) / 2f).toInt()
                        )
                        assertTrue(Color.blue(center) > 180)
                        assertTrue(Color.red(center) < 100)
                    } finally {
                        rendered.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun sameEvidenceIdAcrossExpenseAndFixedCostIsWrittenOnce() {
        val reference = saveEvidence("ev-shared", 400, 800, Color.RED)
        val fixedStore = FixedCostEvidenceFileStore(
            File(root, "fixed-pending").apply { check(mkdirs()) },
            File(root, "fixed-stored").apply { check(mkdirs()) }
        )
        val fixedFile = fixedStore.storedFileFor("ev-shared", "image/jpeg")
        store.fileFor(reference.evidenceId).copyTo(fixedFile)
        val base = snapshot(listOf(reference))
        val fixedEvidence = base.storedEvidence.single().copy(
            fixedCostType = "electricity",
            storedUri = fixedFile.toURI().toString(),
            reportDate = LocalDate.of(2026, 6, 5),
            sortOrder = 0
        )
        val pdf = File(root, "shared-evidence.pdf")

        val count = ReceiptEvidencePdfWriter(store, fixedStore).write(
            base.copy(fixedCostStoredEvidence = listOf(fixedEvidence)),
            pdf
        )

        assertEquals(1, count)
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> assertEquals(1, renderer.pageCount) }
        }
    }

    @Test
    fun multiPageFixedCostPdfKeepsEveryOriginalPageInMonthlyPdf() {
        val fixedStore = FixedCostEvidenceFileStore(
            File(root, "fixed-pending-pages").apply { check(mkdirs()) },
            File(root, "fixed-stored-pages").apply { check(mkdirs()) }
        )
        val source = fixedStore.storedFileFor("ev-pages", "application/pdf")
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawColor(if (index == 0) Color.RED else Color.BLUE)
                document.finishPage(page)
            }
            FileOutputStream(source).use(document::writeTo)
        } finally {
            document.close()
        }
        val fixedEvidence = StoredEvidenceExportItem(
            expenseId = "fixed-expense",
            evidenceId = "ev-pages",
            captureId = "capture-ev-pages",
            storedUri = source.toURI().toString(),
            byteSize = source.length(),
            sha256 = sha256(source.readBytes()),
            createdAt = 1L,
            storedAt = 1L,
            mediaType = "application/pdf",
            fixedCostType = "electricity",
            reportDate = LocalDate.of(2026, 6, 5),
            sortOrder = 0
        )
        val pdf = File(root, "multi-page-fixed-cost.pdf")

        val count = ReceiptEvidencePdfWriter(store, fixedStore).write(
            MonthlyExportSnapshot(
                targetMonth = YearMonth.of(2026, 6),
                dailyReports = emptyList(),
                dailyReportTotals = DailyReportExportTotals(0L, 0L, 0L, 0L, 0L, 0L),
                expenses = emptyList(),
                expenseTotal = 0L,
                storedEvidence = emptyList(),
                fixedCostStoredEvidence = listOf(fixedEvidence)
            ),
            pdf
        )

        assertEquals(2, count)
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> assertEquals(2, renderer.pageCount) }
        }
    }

    @Test
    fun evidenceMetadataMismatchFailsWholePdfWithoutChangingEvidence() {
        val reference = saveEvidence("ev-mismatch", 120, 80, Color.RED)
        val evidenceFile = store.fileFor(reference.evidenceId)
        val before = evidenceFile.readBytes()
        val invalid = snapshot(listOf(reference)).copy(
            storedEvidence = snapshot(listOf(reference)).storedEvidence.map {
                it.copy(sha256 = "00".repeat(32))
            }
        )
        val destination = File(root, "must-not-exist.pdf")

        val error = assertThrows(ReceiptPdfGenerationException::class.java) {
            ReceiptEvidencePdfWriter(store).write(invalid, destination)
        }

        assertEquals(ReceiptPdfFailureReason.EVIDENCE_METADATA_MISMATCH, error.reason)
        assertFalse(destination.exists())
        assertTrue(evidenceFile.readBytes().contentEquals(before))
    }

    @Test
    fun noEvidenceProducesNoPdfAndRepeatedMonthUsesUniqueDirectories() {
        val generator = MonthlyExportArtifactGenerator(context, ReceiptEvidencePdfWriter(store))
        val empty = snapshot(emptyList())

        val first = generator.generate(empty) as MonthlyExportArtifactResult.Success
        val second = generator.generate(empty) as MonthlyExportArtifactResult.Success

        assertTrue(first.receiptPdf is ReceiptPdfArtifactResult.NoEvidence)
        assertNull(first.artifacts.receiptPdf)
        assertEquals(2, first.artifacts.files.size)
        assertTrue(first.artifacts.files.all(File::isFile))
        assertNotEquals(first.artifacts.generationId, second.artifacts.generationId)
        val firstDirectory = requireNotNull(first.artifacts.dailyReportXlsx.parentFile)
        assertNotEquals(
            firstDirectory,
            second.artifacts.dailyReportXlsx.parentFile
        )
        assertTrue(
            firstDirectory.listFiles().orEmpty()
                .none { it.name.endsWith(ExportCacheContract.TempSuffix) }
        )
    }

    @Test
    fun failureCleansOnlyFailedGenerationAndShareUsesScopedContentUris() {
        val reference = saveEvidence("ev-share", 400, 800, Color.GREEN)
        val generator = MonthlyExportArtifactGenerator(context, ReceiptEvidencePdfWriter(store))
        val successful = generator.generate(snapshot(listOf(reference))) as MonthlyExportArtifactResult.Success
        assertEquals("2026年6月_レシート.pdf", successful.artifacts.receiptPdf?.name)
        val existingDirectory = requireNotNull(successful.artifacts.dailyReportXlsx.parentFile)
        existingDirectory.setLastModified(1L)
        val exportRoot = requireNotNull(existingDirectory.parentFile)
        val childCountBeforeFailure = exportRoot.listFiles().orEmpty().size
        val invalidSnapshot = snapshot(listOf(reference)).copy(
            storedEvidence = snapshot(listOf(reference)).storedEvidence.map {
                it.copy(storedUri = "file:/wrong/location.jpg")
            }
        )

        val failed = generator.generate(invalidSnapshot)

        assertTrue(failed is MonthlyExportArtifactResult.Failure)
        assertTrue((failed as MonthlyExportArtifactResult.Failure).generationCleanupSucceeded)
        assertTrue(existingDirectory.isDirectory)
        assertEquals(childCountBeforeFailure, exportRoot.listFiles().orEmpty().size)

        val intent = MonthlyExportShareGateway(context)
            .createShareIntent(successful.artifacts.files)
        assertTrue(existingDirectory.lastModified() > 1L)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(3, intent.clipData?.itemCount)
        @Suppress("DEPRECATION")
        val streams = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertEquals(3, streams?.size)
        assertTrue(streams.orEmpty().all { uri ->
            uri.scheme == "content" &&
                uri.authority == context.packageName + ExportCacheContract.FileProviderAuthoritySuffix
        })

        val temp = File(existingDirectory, "unsafe.tmp").apply { writeText("x") }
        assertThrows(IllegalArgumentException::class.java) {
            MonthlyExportShareGateway(context).createShareIntent(listOf(temp))
        }
    }

    @Test
    fun safGatewayRejectsFilesOutsideCompletedExportScope() {
        val outside = File(root, "outside.pdf").apply { writeText("not an export") }

        val result = MonthlyExportSafGateway(context).copy(
            outside,
            android.net.Uri.parse("content://invalid/destination")
        )

        assertTrue(result is SafExportCopyResult.Failure)
    }

    @Test
    fun shareIntentIncludesTargetMonthStoreAndAllMimeTypes() {
        val reference = saveEvidence("ev-share-metadata", 400, 800, Color.GREEN)
        val snapshot = snapshot(listOf(reference))
        val result = MonthlyExportArtifactGenerator(
            context,
            ReceiptEvidencePdfWriter(store)
        ).generate(snapshot)
        val success = result as MonthlyExportArtifactResult.Success

        val intent = MonthlyExportShareGateway(context).createShareIntent(
            files = success.artifacts.files,
            targetMonth = "2026-06",
            storeName = "テスト店舗"
        )

        assertEquals(
            listOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf"
            ),
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
        )
        assertEquals("わるん会計 提出資料 テスト店舗 2026-06", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("店舗：テスト店舗\n対象月：2026-06", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun retentionKeepsYoungGenerationAndDeletesOnlyExpiredUuidDirectChildren() {
        val exportRoot = File(context.cacheDir, ExportCacheContract.RootDirectory).apply {
            check(mkdirs() || isDirectory)
        }
        val now = 2L * ExportCacheContract.RetentionMillis
        val young = File(exportRoot, "11111111-1111-1111-1111-111111111111").apply {
            check(mkdirs())
            File(this, "young.xlsx").writeText("young")
            setLastModified(now - ExportCacheContract.RetentionMillis + 1L)
        }
        val expired = File(exportRoot, "22222222-2222-2222-2222-222222222222").apply {
            check(mkdirs())
            File(this, "interrupted.xlsx.tmp").writeText("temp")
            setLastModified(now - ExportCacheContract.RetentionMillis)
        }
        val unknown = File(exportRoot, "unknown-directory").apply {
            check(mkdirs())
            File(this, "keep.txt").writeText("unknown")
            setLastModified(0L)
        }
        val unknownFile = File(exportRoot, "33333333-3333-3333-3333-333333333333").apply {
            writeText("not a generation directory")
            setLastModified(0L)
        }

        val result = MonthlyExportArtifactGenerator(
            context,
            ReceiptEvidencePdfWriter(store)
        ).cleanupExpired(now)

        assertTrue(result.succeeded)
        assertEquals(1, result.deletedGenerationCount)
        assertTrue(young.isDirectory)
        assertFalse(expired.exists())
        assertTrue(unknown.isDirectory)
        assertTrue(unknownFile.isFile)
    }

    private fun saveEvidence(id: String, width: Int, height: Int, color: Int): EvidenceFileReference {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val pendingFile = File(pending, "receipt_$id.jpg")
        FileOutputStream(pendingFile).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
        }
        bitmap.recycle()
        return store.promotePendingImage(id)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun snapshot(references: List<EvidenceFileReference>): MonthlyExportSnapshot {
        val expense = ExpenseDetailExportRow(
            expenseId = "expense-1",
            expenseDate = LocalDate.of(2026, 6, 5),
            supplierName = "支出先",
            amount = 1_234L,
            memo = "用途",
            paymentMethod = "現金",
            evidenceCount = references.size
        )
        return MonthlyExportSnapshot(
            targetMonth = YearMonth.of(2026, 6),
            dailyReports = emptyList(),
            dailyReportTotals = DailyReportExportTotals(0L, 0L, 0L, 0L, 0L, 0L),
            expenses = listOf(expense),
            expenseTotal = expense.amount,
            storedEvidence = references.mapIndexed { index, reference ->
                StoredEvidenceExportItem(
                    expenseId = expense.expenseId,
                    evidenceId = reference.evidenceId,
                    captureId = "capture-${reference.evidenceId}",
                    storedUri = reference.localUri,
                    byteSize = reference.byteSize,
                    sha256 = reference.sha256,
                    createdAt = index.toLong(),
                    storedAt = reference.storedAt
                )
            }
        )
    }

    private fun hasDarkPixel(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Boolean {
        for (y in top until bottom) {
            for (x in left until right) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) < 100 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) {
                    return true
                }
            }
        }
        return false
    }
}
