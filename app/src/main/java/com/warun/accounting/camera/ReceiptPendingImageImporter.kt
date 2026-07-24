package com.warun.accounting.camera

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.warun.accounting.evidence.EvidenceFinalizationJournal
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ReceiptImageImportGateway {
    suspend fun importImage(externalUri: String): ReceiptCaptureResult
    suspend fun cleanupOrphanedImports(): Int
}

enum class ReceiptImageImportError(val userMessage: String) {
    UnsupportedFormat("対応していない画像形式です"),
    CorruptImage("画像が壊れているため読み込めません"),
    ImageTooLarge("画像が大きすぎます"),
    UnreadableImage("画像を読み込めません"),
    InsufficientStorage("保存領域が不足しています"),
    ImportFailed("画像の取込処理に失敗しました")
}

class ReceiptImageImportException(
    val error: ReceiptImageImportError,
    cause: Throwable? = null
) : IllegalStateException(error.userMessage, cause)

internal enum class ReceiptSourceImageFormat {
    Jpeg,
    Png
}

internal data class ReceiptImageDimensions(
    val width: Int,
    val height: Int
) {
    val pixels: Long = width.toLong() * height.toLong()
    val longestSide: Int = max(width, height)
}

internal data class ReceiptExifTransform(
    val rotationDegrees: Float = 0f,
    val flipHorizontal: Boolean = false
)

internal fun exifTransformFor(orientation: Int): ReceiptExifTransform = when (orientation) {
    ExifInterface.ORIENTATION_UNDEFINED,
    ExifInterface.ORIENTATION_NORMAL -> ReceiptExifTransform()
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ReceiptExifTransform(flipHorizontal = true)
    ExifInterface.ORIENTATION_ROTATE_180 -> ReceiptExifTransform(rotationDegrees = 180f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ReceiptExifTransform(
        rotationDegrees = 180f,
        flipHorizontal = true
    )
    ExifInterface.ORIENTATION_TRANSPOSE -> ReceiptExifTransform(
        rotationDegrees = 90f,
        flipHorizontal = true
    )
    ExifInterface.ORIENTATION_ROTATE_90 -> ReceiptExifTransform(rotationDegrees = 90f)
    ExifInterface.ORIENTATION_TRANSVERSE -> ReceiptExifTransform(
        rotationDegrees = -90f,
        flipHorizontal = true
    )
    ExifInterface.ORIENTATION_ROTATE_270 -> ReceiptExifTransform(rotationDegrees = -90f)
    else -> throw ReceiptImageImportException(ReceiptImageImportError.CorruptImage)
}

internal fun normalizedDimensions(
    source: ReceiptImageDimensions,
    maxLongestSide: Int,
    maxPixels: Long
): ReceiptImageDimensions {
    val longestScale = maxLongestSide.toDouble() / source.longestSide.toDouble()
    val pixelScale = sqrt(maxPixels.toDouble() / source.pixels.toDouble())
    val scale = min(1.0, min(longestScale, pixelScale))
    return ReceiptImageDimensions(
        width = max(1, floor(source.width * scale).toInt()),
        height = max(1, floor(source.height * scale).toInt())
    )
}

internal fun validateOriginalDimensions(bounds: ReceiptImageDimensions) {
    if (
        bounds.longestSide > ReceiptPendingImageImporter.MaxOriginalSide ||
        bounds.pixels > ReceiptPendingImageImporter.MaxOriginalPixels
    ) {
        throw ReceiptImageImportException(ReceiptImageImportError.ImageTooLarge)
    }
}

internal fun decodeSampleSize(
    source: ReceiptImageDimensions,
    target: ReceiptImageDimensions
): Int {
    var sampleSize = 1
    while (
        source.width / sampleSize > target.width ||
        source.height / sampleSize > target.height
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

internal interface ReceiptExternalImageSource {
    fun mimeType(uri: String): String?
    fun open(uri: String): InputStream
}

private class ContentResolverReceiptExternalImageSource(
    private val contentResolver: ContentResolver
) : ReceiptExternalImageSource {
    override fun mimeType(uri: String): String? =
        contentResolver.getType(Uri.parse(uri))

    override fun open(uri: String): InputStream =
        contentResolver.openInputStream(Uri.parse(uri))
            ?: throw IOException("Selected image stream is unavailable")
}

internal interface ReceiptImageNormalizer {
    fun normalize(
        sourceFile: File,
        sourceFormat: ReceiptSourceImageFormat,
        destinationFile: File
    )
}

internal class AndroidReceiptImageNormalizer : ReceiptImageNormalizer {
    override fun normalize(
        sourceFile: File,
        sourceFormat: ReceiptSourceImageFormat,
        destinationFile: File
    ) {
        val sourceBounds = decodeBounds(sourceFile, sourceFormat)
        validateOriginalDimensions(sourceBounds)
        val orientation = if (sourceFormat == ReceiptSourceImageFormat.Jpeg) {
            runCatching {
                ExifInterface(sourceFile.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
            }.getOrElse {
                throw ReceiptImageImportException(ReceiptImageImportError.CorruptImage, it)
            }
        } else {
            ExifInterface.ORIENTATION_NORMAL
        }
        val transform = exifTransformFor(orientation)
        val target = normalizedDimensions(
            source = sourceBounds,
            maxLongestSide = ReceiptPendingImageImporter.MaxNormalizedLongestSide,
            maxPixels = ReceiptPendingImageImporter.MaxNormalizedPixels
        )
        val canCopyJpegWithoutRecompression =
            sourceFormat == ReceiptSourceImageFormat.Jpeg &&
                transform == ReceiptExifTransform() &&
                target == sourceBounds

        if (canCopyJpegWithoutRecompression) {
            validateDecodedImage(sourceFile, sampleSize = 1)
            copyAndSync(sourceFile, destinationFile)
        } else {
            decodeTransformAndWrite(
                sourceFile = sourceFile,
                sourceBounds = sourceBounds,
                target = target,
                transform = transform,
                destinationFile = destinationFile
            )
        }
        validateNormalizedJpeg(destinationFile)
    }

    private fun decodeBounds(
        file: File,
        expectedFormat: ReceiptSourceImageFormat
    ): ReceiptImageDimensions {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw ReceiptImageImportException(ReceiptImageImportError.CorruptImage)
        }
        val expectedMime = when (expectedFormat) {
            ReceiptSourceImageFormat.Jpeg -> "image/jpeg"
            ReceiptSourceImageFormat.Png -> "image/png"
        }
        if (!options.outMimeType.equals(expectedMime, ignoreCase = true)) {
            throw ReceiptImageImportException(ReceiptImageImportError.UnsupportedFormat)
        }
        return ReceiptImageDimensions(options.outWidth, options.outHeight)
    }

    private fun validateDecodedImage(file: File, sampleSize: Int) {
        val bitmap = runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
        }.getOrNull() ?: throw ReceiptImageImportException(ReceiptImageImportError.CorruptImage)
        bitmap.recycle()
    }

    private fun decodeTransformAndWrite(
        sourceFile: File,
        sourceBounds: ReceiptImageDimensions,
        target: ReceiptImageDimensions,
        transform: ReceiptExifTransform,
        destinationFile: File
    ) {
        val sampleSize = decodeSampleSize(sourceBounds, target)
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var scaled: Bitmap? = null
        var whiteBackground: Bitmap? = null
        try {
            decoded = BitmapFactory.decodeFile(
                sourceFile.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: throw ReceiptImageImportException(ReceiptImageImportError.CorruptImage)

            val matrix = Matrix().apply {
                if (transform.rotationDegrees != 0f) {
                    setRotate(transform.rotationDegrees)
                }
                if (transform.flipHorizontal) {
                    postScale(-1f, 1f)
                }
            }
            oriented = if (transform == ReceiptExifTransform()) {
                decoded
            } else {
                Bitmap.createBitmap(
                    decoded,
                    0,
                    0,
                    decoded.width,
                    decoded.height,
                    matrix,
                    true
                )
            }

            val orientedTarget = normalizedDimensions(
                source = ReceiptImageDimensions(oriented.width, oriented.height),
                maxLongestSide = ReceiptPendingImageImporter.MaxNormalizedLongestSide,
                maxPixels = ReceiptPendingImageImporter.MaxNormalizedPixels
            )
            scaled = if (
                oriented.width > orientedTarget.width ||
                oriented.height > orientedTarget.height
            ) {
                Bitmap.createScaledBitmap(
                    oriented,
                    orientedTarget.width,
                    orientedTarget.height,
                    true
                )
            } else {
                oriented
            }

            whiteBackground = Bitmap.createBitmap(
                scaled.width,
                scaled.height,
                Bitmap.Config.ARGB_8888
            )
            Canvas(whiteBackground).apply {
                drawColor(Color.WHITE)
                drawBitmap(scaled, 0f, 0f, null)
            }
            FileOutputStream(destinationFile).use { output ->
                if (!whiteBackground.compress(
                        Bitmap.CompressFormat.JPEG,
                        ReceiptPendingImageImporter.JpegQuality,
                        output
                    )
                ) {
                    throw IOException("JPEG encoder rejected the image")
                }
                output.flush()
                output.fd.sync()
            }
        } catch (error: OutOfMemoryError) {
            throw ReceiptImageImportException(ReceiptImageImportError.ImageTooLarge, error)
        } catch (error: ReceiptImageImportException) {
            throw error
        } catch (error: IOException) {
            throw error
        } catch (error: RuntimeException) {
            throw ReceiptImageImportException(ReceiptImageImportError.CorruptImage, error)
        } finally {
            listOfNotNull(decoded, oriented, scaled, whiteBackground)
                .distinctBy(System::identityHashCode)
                .forEach { bitmap ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
        }
    }

    private fun validateNormalizedJpeg(file: File) {
        if (!file.isFile || file.length() < 4L || detectSourceFormat(file) != ReceiptSourceImageFormat.Jpeg) {
            throw ReceiptImageImportException(ReceiptImageImportError.CorruptImage)
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (
            options.outWidth <= 0 ||
            options.outHeight <= 0 ||
            !options.outMimeType.equals("image/jpeg", ignoreCase = true)
        ) {
            throw ReceiptImageImportException(ReceiptImageImportError.CorruptImage)
        }
        val bounds = ReceiptImageDimensions(options.outWidth, options.outHeight)
        if (
            bounds.longestSide > ReceiptPendingImageImporter.MaxNormalizedLongestSide ||
            bounds.pixels > ReceiptPendingImageImporter.MaxNormalizedPixels
        ) {
            throw ReceiptImageImportException(ReceiptImageImportError.ImageTooLarge)
        }
        validateDecodedImage(
            file,
            decodeSampleSize(
                bounds,
                normalizedDimensions(bounds, 2_048, 4_000_000L)
            )
        )
    }

    private fun copyAndSync(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }
}

@Singleton
class ReceiptPendingImageImporter internal constructor(
    private val source: ReceiptExternalImageSource,
    private val imageStore: ReceiptImageStore,
    private val normalizer: ReceiptImageNormalizer,
    private val now: () -> Long,
    private val newCaptureId: () -> String,
    private val outputFactory: (File) -> FileOutputStream
) : ReceiptImageImportGateway {
    companion object {
        const val MaxInputBytes: Long = 20L * 1024L * 1024L
        const val MaxOriginalPixels: Long = 50_000_000L
        const val MaxOriginalSide: Int = 20_000
        const val MaxNormalizedLongestSide: Int = 4_096
        const val MaxNormalizedPixels: Long = 12_000_000L
        const val JpegQuality: Int = 92
        private const val MaxCaptureIdAttempts = 8
    }

    private val operationMutex = Mutex()

    @Inject
    constructor(
        @ApplicationContext context: Context,
        journal: EvidenceFinalizationJournal
    ) : this(
        source = ContentResolverReceiptExternalImageSource(context.contentResolver),
        imageStore = ReceiptImageStore(
            pendingDirectory = File(context.filesDir, "receipt-images/pending"),
            deletionPolicyProvider = PendingImageDeletionPolicyProvider {
                journal.pendingImageDeletionPolicy()
            }
        ),
        normalizer = AndroidReceiptImageNormalizer(),
        now = System::currentTimeMillis,
        newCaptureId = { UUID.randomUUID().toString() },
        outputFactory = ::FileOutputStream
    )

    override suspend fun cleanupOrphanedImports(): Int = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            imageStore.cleanupOrphanedImportFiles()
        }
    }

    override suspend fun importImage(externalUri: String): ReceiptCaptureResult =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                imageStore.cleanupOrphanedImportFiles()
                importLocked(externalUri)
            }
        }

    private fun importLocked(externalUri: String): ReceiptCaptureResult {
        if (externalUri.isBlank()) {
            throw ReceiptImageImportException(ReceiptImageImportError.UnreadableImage)
        }
        val mimeType = try {
            normalizeMimeType(source.mimeType(externalUri))
        } catch (error: SecurityException) {
            throw ReceiptImageImportException(ReceiptImageImportError.UnreadableImage, error)
        } catch (error: RuntimeException) {
            throw ReceiptImageImportException(ReceiptImageImportError.UnreadableImage, error)
        }
        if (mimeType != null && mimeType !in AllowedMimeTypes) {
            throw ReceiptImageImportException(ReceiptImageImportError.UnsupportedFormat)
        }

        val (captureId, files) = prepareUniqueImportFiles()
        var stage = ImportStage.Reading
        try {
            copyBounded(externalUri, files.sourceFile)
            val format = detectSourceFormat(files.sourceFile)
                ?: throw ReceiptImageImportException(
                    classifyUnrecognizedImage(files.sourceFile)
                )
            validateMimeMatchesFormat(mimeType, format)

            stage = ImportStage.Normalizing
            normalizer.normalize(files.sourceFile, format, files.normalizedFile)

            stage = ImportStage.Publishing
            val pendingFile = imageStore.publishImportedJpeg(captureId, files.normalizedFile)
            return ReceiptCaptureResult(
                captureId = captureId,
                localUri = pendingFile.toURI().toString(),
                capturedAt = now()
            )
        } catch (error: ReceiptImageImportException) {
            throw error
        } catch (error: SecurityException) {
            throw ReceiptImageImportException(ReceiptImageImportError.UnreadableImage, error)
        } catch (error: IOException) {
            val mapped = when {
                error.isInsufficientStorage() -> ReceiptImageImportError.InsufficientStorage
                stage == ImportStage.Reading -> ReceiptImageImportError.UnreadableImage
                else -> ReceiptImageImportError.ImportFailed
            }
            throw ReceiptImageImportException(mapped, error)
        } catch (error: IllegalStateException) {
            val mapped = if (error.isInsufficientStorage()) {
                ReceiptImageImportError.InsufficientStorage
            } else {
                ReceiptImageImportError.ImportFailed
            }
            throw ReceiptImageImportException(mapped, error)
        } finally {
            files.sourceFile.delete()
            files.normalizedFile.delete()
        }
    }

    private fun prepareUniqueImportFiles(): Pair<String, PendingImportFiles> {
        repeat(MaxCaptureIdAttempts) {
            val captureId = newCaptureId()
            if (captureId.isBlank()) return@repeat
            try {
                return captureId to imageStore.prepareImportFiles(captureId)
            } catch (error: IllegalStateException) {
                val destinationExists = runCatching { imageStore.fileFor(captureId).exists() }
                    .getOrDefault(false)
                if (!destinationExists) throw error
            }
        }
        throw ReceiptImageImportException(ReceiptImageImportError.ImportFailed)
    }

    private fun copyBounded(externalUri: String, destination: File) {
        val input = try {
            source.open(externalUri)
        } catch (error: SecurityException) {
            throw ReceiptImageImportException(ReceiptImageImportError.UnreadableImage, error)
        } catch (error: IOException) {
            throw ReceiptImageImportException(ReceiptImageImportError.UnreadableImage, error)
        } catch (error: RuntimeException) {
            throw ReceiptImageImportException(ReceiptImageImportError.UnreadableImage, error)
        }
        input.use {
            val destinationOutput = try {
                outputFactory(destination)
            } catch (error: IOException) {
                val mapped = if (error.isInsufficientStorage()) {
                    ReceiptImageImportError.InsufficientStorage
                } else {
                    ReceiptImageImportError.ImportFailed
                }
                throw ReceiptImageImportException(mapped, error)
            }
            destinationOutput.use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = try {
                        input.read(buffer)
                    } catch (error: IOException) {
                        throw ReceiptImageImportException(
                            ReceiptImageImportError.UnreadableImage,
                            error
                        )
                    }
                    if (count < 0) break
                    total += count
                    if (total > MaxInputBytes) {
                        throw ReceiptImageImportException(ReceiptImageImportError.ImageTooLarge)
                    }
                    try {
                        output.write(buffer, 0, count)
                    } catch (error: IOException) {
                        val mapped = if (error.isInsufficientStorage()) {
                            ReceiptImageImportError.InsufficientStorage
                        } else {
                            ReceiptImageImportError.ImportFailed
                        }
                        throw ReceiptImageImportException(mapped, error)
                    }
                }
                try {
                    output.flush()
                    output.fd.sync()
                } catch (error: IOException) {
                    val mapped = if (error.isInsufficientStorage()) {
                        ReceiptImageImportError.InsufficientStorage
                    } else {
                        ReceiptImageImportError.ImportFailed
                    }
                    throw ReceiptImageImportException(mapped, error)
                }
            }
        }
    }

    private enum class ImportStage {
        Reading,
        Normalizing,
        Publishing
    }
}

private val AllowedMimeTypes = setOf("image/jpeg", "image/png", "image/*")

private fun normalizeMimeType(mimeType: String?): String? =
    mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf(String::isNotBlank)

private fun validateMimeMatchesFormat(
    mimeType: String?,
    format: ReceiptSourceImageFormat
) {
    val matches = when (mimeType) {
        null, "image/*" -> true
        "image/jpeg" -> format == ReceiptSourceImageFormat.Jpeg
        "image/png" -> format == ReceiptSourceImageFormat.Png
        else -> false
    }
    if (!matches) {
        throw ReceiptImageImportException(ReceiptImageImportError.UnsupportedFormat)
    }
}

internal fun detectSourceFormat(file: File): ReceiptSourceImageFormat? {
    if (!file.isFile || file.length() <= 0L) return null
    val header = ByteArray(12)
    val count = FileInputStream(file).use { it.read(header) }
    if (
        count >= 4 &&
        header[0].toInt() and 0xFF == 0xFF &&
        header[1].toInt() and 0xFF == 0xD8
    ) {
        if (file.length() < 4L) return null
        val hasEndMarker = RandomAccessFile(file, "r").use { input ->
            input.seek(input.length() - 2L)
            input.read() == 0xFF && input.read() == 0xD9
        }
        return if (hasEndMarker) ReceiptSourceImageFormat.Jpeg else null
    }
    val pngSignature = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (
        count >= pngSignature.size &&
        pngSignature.indices.all { index ->
            header[index].toInt() and 0xFF == pngSignature[index]
        }
    ) {
        return ReceiptSourceImageFormat.Png
    }
    return null
}

private fun classifyUnrecognizedImage(file: File): ReceiptImageImportError {
    if (!file.isFile || file.length() == 0L) return ReceiptImageImportError.CorruptImage
    val header = ByteArray(8)
    val count = FileInputStream(file).use { it.read(header) }
    val startsLikeJpeg =
        count >= 2 &&
            header[0].toInt() and 0xFF == 0xFF &&
            header[1].toInt() and 0xFF == 0xD8
    val pngPrefix = intArrayOf(0x89, 0x50, 0x4E, 0x47)
    val startsLikePng =
        count >= pngPrefix.size &&
            pngPrefix.indices.all { index ->
                header[index].toInt() and 0xFF == pngPrefix[index]
            }
    return if (
        startsLikeJpeg ||
        startsLikePng
    ) {
        ReceiptImageImportError.CorruptImage
    } else {
        ReceiptImageImportError.UnsupportedFormat
    }
}

private fun Throwable.isInsufficientStorage(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message.orEmpty().lowercase()
        if (
            "enospc" in message ||
            "no space left" in message ||
            "not enough space" in message
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
