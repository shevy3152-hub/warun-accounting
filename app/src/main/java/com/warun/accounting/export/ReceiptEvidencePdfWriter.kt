package com.warun.accounting.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import android.media.ExifInterface
import com.warun.accounting.data.export.ExpenseDetailExportRow
import com.warun.accounting.data.export.MonthlyExportSnapshot
import com.warun.accounting.data.export.StoredEvidenceExportItem
import com.warun.accounting.evidence.EvidenceFileReference
import com.warun.accounting.evidence.EvidenceFileStore
import com.warun.accounting.evidence.FixedCostEvidenceFileStore
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.sqrt
import javax.inject.Inject

enum class ReceiptPdfFailureReason {
    EVIDENCE_MISSING,
    EVIDENCE_METADATA_MISMATCH,
    IMAGE_DECODE_FAILED,
    PDF_WRITE_FAILED
}

class ReceiptPdfGenerationException(
    val reason: ReceiptPdfFailureReason,
    val evidenceId: String?,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class ReceiptEvidencePdfWriter @Inject constructor(
    private val evidenceFileStore: EvidenceFileStore,
    private val fixedCostEvidenceFileStore: FixedCostEvidenceFileStore
) {
    /** Keeps the existing JVM/instrumented writer fixtures source-compatible. */
    constructor(evidenceFileStore: EvidenceFileStore) : this(
        evidenceFileStore,
        FixedCostEvidenceFileStore(File("fixed-cost-evidence/pending"), File("fixed-cost-evidence/stored"))
    )
    fun write(snapshot: MonthlyExportSnapshot, destination: File): Int {
        val allEvidence = (snapshot.storedEvidence + snapshot.fixedCostStoredEvidence)
            .distinctBy { it.evidenceId }
        require(allEvidence.isNotEmpty())
        val expenses = snapshot.expenses.associateBy(ExpenseDetailExportRow::expenseId)
        val validated = allEvidence.map { evidence ->
            val expense = expenses[evidence.expenseId]
            if (expense == null && evidence.fixedCostType == null) {
                throw ReceiptPdfGenerationException(
                    ReceiptPdfFailureReason.EVIDENCE_METADATA_MISMATCH,
                    evidence.evidenceId,
                    "Evidenceに対応する有効な支出がありません"
                )
            }
            ValidatedEvidence(evidence, expense, validateEvidence(evidence))
        }

        val document = PdfDocument()
        try {
            var pageNumber = 0
            validated.forEach { item ->
                fun writeBitmap(bitmap: Bitmap) {
                    try {
                        val header = buildHeader(item.evidence, item.expense)
                        val layout = ReceiptPdfPageLayoutPlanner.plan(bitmap.width, bitmap.height, header.size)
                        val page = document.startPage(PdfDocument.PageInfo.Builder(layout.pageWidth, layout.pageHeight, ++pageNumber).create())
                        try { drawPage(page.canvas, header, bitmap, layout) } finally { document.finishPage(page) }
                    } finally { bitmap.recycle() }
                }
                if (item.evidence.mediaType == "application/pdf") {
                    ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                            check(renderer.pageCount > 0) { "PDFにページがありません" }
                            repeat(renderer.pageCount) { pageIndex ->
                                renderer.openPage(pageIndex).use { page ->
                                    val scale = minOf(1f, 2048f / maxOf(page.width, page.height).toFloat())
                                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    writeBitmap(bitmap)
                                }
                            }
                        }
                    }
                } else {
                    writeBitmap(decodeSampledAndOriented(item.file, item.evidence.evidenceId))
                }
            }
            destination.parentFile?.let { parent ->
                check(parent.isDirectory) { "PDF出力先が存在しません" }
            }
            FileOutputStream(destination).use { output ->
                document.writeTo(output)
                output.flush()
                output.fd.sync()
            }
            return pageNumber
        } catch (error: ReceiptPdfGenerationException) {
            throw error
        } catch (error: Throwable) {
            throw ReceiptPdfGenerationException(
                ReceiptPdfFailureReason.PDF_WRITE_FAILED,
                null,
                "レシートPDFを作成できませんでした",
                error
            )
        } finally {
            document.close()
        }
    }

    private fun validateEvidence(item: StoredEvidenceExportItem): File {
        if (item.fixedCostType != null) {
            val file = fixedCostEvidenceFileStore.storedFileFor(item.evidenceId, item.mediaType)
            if (!file.isFile || file.length() != item.byteSize || sha256(file) != item.sha256) {
                throw ReceiptPdfGenerationException(
                    ReceiptPdfFailureReason.EVIDENCE_METADATA_MISMATCH,
                    item.evidenceId,
                    "固定費EvidenceとDBメタデータが一致しません"
                )
            }
            return file
        }
        val reference = try {
            evidenceFileStore.resolve(item.evidenceId)
        } catch (error: Throwable) {
            throw ReceiptPdfGenerationException(
                ReceiptPdfFailureReason.EVIDENCE_MISSING,
                item.evidenceId,
                "正式Evidenceを読み取れません",
                error
            )
        } ?: throw ReceiptPdfGenerationException(
            ReceiptPdfFailureReason.EVIDENCE_MISSING,
            item.evidenceId,
            "正式Evidenceが見つかりません"
        )
        if (!reference.matches(item)) {
            throw ReceiptPdfGenerationException(
                ReceiptPdfFailureReason.EVIDENCE_METADATA_MISMATCH,
                item.evidenceId,
                "正式EvidenceとDBメタデータが一致しません"
            )
        }
        return evidenceFileStore.fileFor(item.evidenceId)
    }

    private fun EvidenceFileReference.matches(item: StoredEvidenceExportItem): Boolean =
        evidenceId == item.evidenceId &&
            localUri == item.storedUri &&
            byteSize == item.byteSize &&
            sha256.equals(item.sha256, ignoreCase = true)

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildHeader(evidence: StoredEvidenceExportItem, expense: ExpenseDetailExportRow?): List<String> = buildList {
        if (evidence.fixedCostType != null) {
            add("日付: ${evidence.reportDate}")
            add("固定費: ${fixedCostLabel(evidence.fixedCostType)}")
        } else {
            val row = requireNotNull(expense)
            add("日付: ${row.expenseDate}")
            if (row.supplierName.isNotBlank()) add("支出先: ${row.supplierName}")
            add("金額: ${NumberFormat.getIntegerInstance(Locale.JAPAN).format(row.amount)}円")
        }
    }

    private fun drawPage(
        canvas: Canvas,
        header: List<String>,
        bitmap: Bitmap,
        layout: ReceiptPdfPageLayout
    ) {
        canvas.drawColor(Color.WHITE)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        header.forEachIndexed { index, text ->
            canvas.drawText(text, 36f, layout.headerBaselines[index], textPaint)
        }
        val bounds = layout.imageBounds
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(bounds.left, bounds.top, bounds.right, bounds.bottom),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
    }

    private fun decodeSampledAndOriented(file: File, evidenceId: String): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            decodeFailure(evidenceId)
        }
        var sample = 1
        while (
            bounds.outWidth / sample > MaxDecodedDimension ||
            bounds.outHeight / sample > MaxDecodedDimension ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() / sample / sample > MaxDecodedPixels
        ) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: decodeFailure(evidenceId)
        return scaleForSubmission(orient(decoded, file, evidenceId), evidenceId)
    }

    /**
     * PdfDocument stores Canvas bitmaps as raster image objects. Keep the original Evidence
     * untouched, but cap the submission-only copy so a camera-resolution receipt does not turn
     * into a hundreds-of-megabytes lossless PDF.
     */
    private fun scaleForSubmission(bitmap: Bitmap, evidenceId: String): Bitmap {
        val scale = minOf(
            1f,
            MaxSubmissionDimension.toFloat() / bitmap.width,
            MaxSubmissionDimension.toFloat() / bitmap.height,
            sqrt(MaxSubmissionPixels.toDouble() / (bitmap.width.toLong() * bitmap.height))
                .toFloat()
        )
        if (scale >= 1f) return bitmap
        val targetWidth = maxOf(1, floor(bitmap.width * scale).toInt())
        val targetHeight = maxOf(1, floor(bitmap.height * scale).toInt())
        return try {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true).also { scaled ->
                if (scaled !== bitmap) bitmap.recycle()
            }
        } catch (error: Throwable) {
            bitmap.recycle()
            throw ReceiptPdfGenerationException(
                ReceiptPdfFailureReason.IMAGE_DECODE_FAILED,
                evidenceId,
                "提出用Evidence画像を縮小できません",
                error
            )
        }
    }

    private fun orient(bitmap: Bitmap, file: File, evidenceId: String): Bitmap {
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (error: Throwable) {
            bitmap.recycle()
            throw ReceiptPdfGenerationException(
                ReceiptPdfFailureReason.IMAGE_DECODE_FAILED,
                evidenceId,
                "Evidence画像の向きを確認できません",
                error
            )
        }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) return bitmap
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { oriented -> if (oriented !== bitmap) bitmap.recycle() }
        } catch (error: Throwable) {
            bitmap.recycle()
            throw ReceiptPdfGenerationException(
                ReceiptPdfFailureReason.IMAGE_DECODE_FAILED,
                evidenceId,
                "Evidence画像の向きを反映できません",
                error
            )
        }
    }

    private fun decodeFailure(evidenceId: String): Nothing =
        throw ReceiptPdfGenerationException(
            ReceiptPdfFailureReason.IMAGE_DECODE_FAILED,
            evidenceId,
            "Evidence画像をデコードできません"
        )

    private data class ValidatedEvidence(
        val evidence: StoredEvidenceExportItem,
        val expense: ExpenseDetailExportRow?,
        val file: File
    )

    private fun fixedCostLabel(type: String): String = when (type) {
        "electricity" -> "電気代"
        "water" -> "水道代"
        "communication" -> "通信費"
        "gas" -> "ガス代"
        else -> "固定費"
    }

    private companion object {
        // Decode one image at a time and keep the temporary working bitmap bounded.
        const val MaxDecodedDimension = 2048
        const val MaxDecodedPixels = 4_000_000L
        // A4 output remains readable at roughly 2x the PDF point grid while keeping MyKomon
        // submissions comfortably below its 100 MB / 10-file limit for ordinary camera images.
        const val MaxSubmissionDimension = 1600
        const val MaxSubmissionPixels = 1_600_000L
    }
}
