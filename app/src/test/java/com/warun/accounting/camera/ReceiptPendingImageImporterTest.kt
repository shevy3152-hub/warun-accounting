package com.warun.accounting.camera

import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptPendingImageImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun uprightJpegWithinLimitsIsPublishedWithoutChangingBytes() = runBlocking {
        val sourceBytes = jpegBytes(1, 2, 3)
        val fixture = fixture(bytes = sourceBytes, mimeType = "image/jpeg")

        val result = fixture.importer.importImage(TestUri)

        assertArrayEquals(sourceBytes, fixture.store.fileFor(result.captureId).readBytes())
        assertEquals(ReceiptSourceImageFormat.Jpeg, fixture.normalizer.lastFormat)
        assertEquals(1, fixture.normalizer.callCount)
        fixture.assertNoImportTemps()
    }

    @Test
    fun pngIsNormalizedToJpegBeforePendingPublication() = runBlocking {
        val fixture = fixture(bytes = pngBytes(), mimeType = "image/png")

        val result = fixture.importer.importImage(TestUri)

        assertArrayEquals(jpegBytes(9), fixture.store.fileFor(result.captureId).readBytes())
        assertEquals(ReceiptSourceImageFormat.Png, fixture.normalizer.lastFormat)
        fixture.assertNoImportTemps()
    }

    @Test
    fun emptyInputIsRejectedAsCorruptAndTempsAreRemoved() = runBlocking {
        val fixture = fixture(bytes = byteArrayOf(), mimeType = "image/jpeg")

        assertImportError(ReceiptImageImportError.CorruptImage) {
            fixture.importer.importImage(TestUri)
        }

        fixture.assertNoPending()
        fixture.assertNoImportTemps()
    }

    @Test
    fun inputOverTwentyMibIsStoppedAndRemoved() = runBlocking {
        val oversized = ByteArray(ReceiptPendingImageImporter.MaxInputBytes.toInt() + 1)
        val fixture = fixture(bytes = oversized, mimeType = null)

        assertImportError(ReceiptImageImportError.ImageTooLarge) {
            fixture.importer.importImage(TestUri)
        }

        fixture.assertNoPending()
        fixture.assertNoImportTemps()
    }

    @Test
    fun originalPixelAndSideLimitsAreRejectedBeforeFullDecode() {
        assertImportError(ReceiptImageImportError.ImageTooLarge) {
            validateOriginalDimensions(ReceiptImageDimensions(10_001, 5_000))
        }
        assertImportError(ReceiptImageImportError.ImageTooLarge) {
            validateOriginalDimensions(ReceiptImageDimensions(20_001, 1))
        }
        validateOriginalDimensions(ReceiptImageDimensions(10_000, 5_000))
        validateOriginalDimensions(ReceiptImageDimensions(20_000, 1))
    }

    @Test
    fun normalizedDimensionsRespectBothOutputLimits() {
        val normalized = normalizedDimensions(
            ReceiptImageDimensions(8_000, 6_000),
            ReceiptPendingImageImporter.MaxNormalizedLongestSide,
            ReceiptPendingImageImporter.MaxNormalizedPixels
        )

        assertTrue(normalized.longestSide <= ReceiptPendingImageImporter.MaxNormalizedLongestSide)
        assertTrue(normalized.pixels <= ReceiptPendingImageImporter.MaxNormalizedPixels)
        assertTrue(decodeSampleSize(ReceiptImageDimensions(8_000, 6_000), normalized) > 1)
    }

    @Test
    fun allExifOrientationValuesHaveExplicitTransforms() {
        val transforms = mapOf(
            ExifInterface.ORIENTATION_UNDEFINED to ReceiptExifTransform(),
            ExifInterface.ORIENTATION_NORMAL to ReceiptExifTransform(),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to ReceiptExifTransform(flipHorizontal = true),
            ExifInterface.ORIENTATION_ROTATE_180 to ReceiptExifTransform(rotationDegrees = 180f),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to ReceiptExifTransform(180f, true),
            ExifInterface.ORIENTATION_TRANSPOSE to ReceiptExifTransform(90f, true),
            ExifInterface.ORIENTATION_ROTATE_90 to ReceiptExifTransform(rotationDegrees = 90f),
            ExifInterface.ORIENTATION_TRANSVERSE to ReceiptExifTransform(-90f, true),
            ExifInterface.ORIENTATION_ROTATE_270 to ReceiptExifTransform(rotationDegrees = -90f)
        )

        transforms.forEach { (orientation, expected) ->
            assertEquals(expected, exifTransformFor(orientation))
        }
        assertImportError(ReceiptImageImportError.CorruptImage) {
            exifTransformFor(99)
        }
    }

    @Test
    fun nullAndWildcardMimeAllowSignatureBasedDetection() = runBlocking {
        val nullMime = fixture(bytes = jpegBytes(1), mimeType = null)
        val wildcardMime = fixture(bytes = pngBytes(), mimeType = "image/*")

        assertTrue(nullMime.store.fileFor(nullMime.importer.importImage(TestUri).captureId).isFile)
        assertTrue(wildcardMime.store.fileFor(wildcardMime.importer.importImage(TestUri).captureId).isFile)
    }

    @Test
    fun explicitMimeAndSignatureMismatchIsRejected() = runBlocking {
        val fixture = fixture(bytes = pngBytes(), mimeType = "image/jpeg")

        assertImportError(ReceiptImageImportError.UnsupportedFormat) {
            fixture.importer.importImage(TestUri)
        }
        fixture.assertNoPending()
    }

    @Test
    fun unsupportedMimeIsRejectedBeforeOpeningSource() = runBlocking {
        val source = FakeSource(jpegBytes(1), "image/webp")
        val fixture = fixture(source = source)

        assertImportError(ReceiptImageImportError.UnsupportedFormat) {
            fixture.importer.importImage(TestUri)
        }
        assertEquals(0, source.openCount)
    }

    @Test
    fun gifAndWebpSignaturesAreRejectedEvenWithGenericMime() = runBlocking {
        val gif = fixture("GIF89a".toByteArray(), "image/*")
        val webp = fixture(
            byteArrayOf(
                'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                0, 0, 0, 0,
                'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()
            ),
            "image/*"
        )

        assertImportError(ReceiptImageImportError.UnsupportedFormat) {
            gif.importer.importImage(TestUri)
        }
        assertImportError(ReceiptImageImportError.UnsupportedFormat) {
            webp.importer.importImage(TestUri)
        }
    }

    @Test
    fun truncatedJpegAndPngAreReportedAsCorrupt() = runBlocking {
        val jpeg = fixture(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1), null)
        val png = fixture(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A
            ),
            "image/png",
            normalizer = FakeNormalizer(failure = ReceiptImageImportException(ReceiptImageImportError.CorruptImage))
        )

        assertImportError(ReceiptImageImportError.CorruptImage) {
            jpeg.importer.importImage(TestUri)
        }
        assertImportError(ReceiptImageImportError.CorruptImage) {
            png.importer.importImage(TestUri)
        }
    }

    @Test
    fun sourceReadFailureAndMidCopyFailureKeepNoPending() = runBlocking {
        val openFailure = fixture(
            source = FakeSource(jpegBytes(1), "image/jpeg", openFailure = IOException("read"))
        )
        val midCopyFailure = fixture(
            source = FakeSource(
                jpegBytes(1),
                "image/jpeg",
                streamFactory = {
                    object : InputStream() {
                        private var reads = 0
                        override fun read(): Int {
                            if (reads++ > 1) throw IOException("mid-copy")
                            return 0xFF
                        }
                    }
                }
            )
        )

        assertImportError(ReceiptImageImportError.UnreadableImage) {
            openFailure.importer.importImage(TestUri)
        }
        assertImportError(ReceiptImageImportError.UnreadableImage) {
            midCopyFailure.importer.importImage(TestUri)
        }
        openFailure.assertNoPending()
        midCopyFailure.assertNoPending()
        midCopyFailure.assertNoImportTemps()
    }

    @Test
    fun outputFailureDistinguishesStorageShortageAndGenericFailure() = runBlocking {
        val noSpace = fixture(
            bytes = jpegBytes(1),
            mimeType = "image/jpeg",
            outputFactory = { throw IOException("ENOSPC: no space left on device") }
        )
        val generic = fixture(
            bytes = jpegBytes(1),
            mimeType = "image/jpeg",
            outputFactory = { throw IOException("write failed") }
        )

        assertImportError(ReceiptImageImportError.InsufficientStorage) {
            noSpace.importer.importImage(TestUri)
        }
        assertImportError(ReceiptImageImportError.ImportFailed) {
            generic.importer.importImage(TestUri)
        }
        noSpace.assertNoImportTemps()
        generic.assertNoImportTemps()
    }

    @Test
    fun normalizerFailureBeforeAtomicPublicationLeavesNoFinalPending() = runBlocking {
        val fixture = fixture(
            bytes = pngBytes(),
            mimeType = "image/png",
            normalizer = FakeNormalizer(
                writeBeforeFailure = true,
                failure = IOException("interrupted")
            )
        )

        assertImportError(ReceiptImageImportError.ImportFailed) {
            fixture.importer.importImage(TestUri)
        }

        fixture.assertNoPending()
        fixture.assertNoImportTemps()
    }

    @Test
    fun captureIdCollisionNeverOverwritesExistingPending() = runBlocking {
        val directory = temporaryFolder.newFolder("collision")
        val store = ReceiptImageStore(directory)
        val existing = store.prepareFile("same-id").apply { writeBytes(jpegBytes(7)) }
        val ids = ArrayDeque(listOf("same-id", "new-id"))
        val fixture = fixture(
            bytes = jpegBytes(1),
            mimeType = "image/jpeg",
            directory = directory,
            store = store,
            newCaptureId = { ids.removeFirst() }
        )

        val imported = fixture.importer.importImage(TestUri)

        assertEquals("new-id", imported.captureId)
        assertArrayEquals(jpegBytes(7), existing.readBytes())
        assertArrayEquals(jpegBytes(1), store.fileFor("new-id").readBytes())
    }

    @Test
    fun choosingSameImageTwiceCreatesDistinctCaptureIds() = runBlocking {
        val ids = ArrayDeque(listOf("first-id", "second-id"))
        val fixture = fixture(
            bytes = jpegBytes(1),
            mimeType = "image/jpeg",
            newCaptureId = { ids.removeFirst() }
        )

        val first = fixture.importer.importImage(TestUri)
        val second = fixture.importer.importImage(TestUri)

        assertNotEquals(first.captureId, second.captureId)
        assertTrue(fixture.store.fileFor(first.captureId).isFile)
        assertTrue(fixture.store.fileFor(second.captureId).isFile)
    }

    @Test
    fun orphanImportTempsAreCleanedWithoutTouchingPendingOrStored() = runBlocking {
        val directory = temporaryFolder.newFolder("orphan-cleanup")
        val store = ReceiptImageStore(directory)
        val pending = store.prepareFile("pending").apply { writeBytes(jpegBytes(1)) }
        File(directory, "import_orphan.source.tmp").writeText("partial")
        File(directory, "import_orphan.tmp").writeText("partial")
        val storedDirectory = temporaryFolder.newFolder("stored")
        val stored = File(storedDirectory, "evidence_keep.jpg").apply { writeBytes(jpegBytes(9)) }
        val fixture = fixture(
            bytes = jpegBytes(2),
            mimeType = "image/jpeg",
            directory = directory,
            store = store
        )

        assertEquals(2, fixture.importer.cleanupOrphanedImports())

        assertTrue(pending.exists())
        assertTrue(stored.exists())
        fixture.assertNoImportTemps()
    }

    private fun fixture(
        bytes: ByteArray,
        mimeType: String?,
        normalizer: FakeNormalizer = FakeNormalizer(),
        directory: File = temporaryFolder.newFolder(),
        store: ReceiptImageStore = ReceiptImageStore(directory),
        newCaptureId: () -> String = { "capture-${System.nanoTime()}" },
        outputFactory: (File) -> FileOutputStream = ::FileOutputStream
    ): Fixture = fixture(
        source = FakeSource(bytes, mimeType),
        normalizer = normalizer,
        directory = directory,
        store = store,
        newCaptureId = newCaptureId,
        outputFactory = outputFactory
    )

    private fun fixture(
        source: FakeSource,
        normalizer: FakeNormalizer = FakeNormalizer(),
        directory: File = temporaryFolder.newFolder(),
        store: ReceiptImageStore = ReceiptImageStore(directory),
        newCaptureId: () -> String = { "capture-${System.nanoTime()}" },
        outputFactory: (File) -> FileOutputStream = ::FileOutputStream
    ): Fixture {
        val importer = ReceiptPendingImageImporter(
            source = source,
            imageStore = store,
            normalizer = normalizer,
            now = { 123L },
            newCaptureId = newCaptureId,
            outputFactory = outputFactory
        )
        return Fixture(directory, store, normalizer, importer)
    }

    private data class Fixture(
        val directory: File,
        val store: ReceiptImageStore,
        val normalizer: FakeNormalizer,
        val importer: ReceiptPendingImageImporter
    ) {
        fun assertNoPending() {
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("receipt_") })
        }

        fun assertNoImportTemps() {
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("import_") })
        }
    }

    private class FakeSource(
        private val bytes: ByteArray,
        private val type: String?,
        private val openFailure: IOException? = null,
        private val streamFactory: (() -> InputStream)? = null
    ) : ReceiptExternalImageSource {
        var openCount: Int = 0

        override fun mimeType(uri: String): String? = type

        override fun open(uri: String): InputStream {
            openCount++
            openFailure?.let { throw it }
            return streamFactory?.invoke() ?: ByteArrayInputStream(bytes)
        }
    }

    private class FakeNormalizer(
        private val writeBeforeFailure: Boolean = false,
        private val failure: Throwable? = null
    ) : ReceiptImageNormalizer {
        var callCount: Int = 0
        var lastFormat: ReceiptSourceImageFormat? = null

        override fun normalize(
            sourceFile: File,
            sourceFormat: ReceiptSourceImageFormat,
            destinationFile: File
        ) {
            callCount++
            lastFormat = sourceFormat
            if (writeBeforeFailure) destinationFile.writeBytes(jpegBytes(5))
            failure?.let { throw it }
            if (sourceFormat == ReceiptSourceImageFormat.Jpeg) {
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destinationFile).use { output -> input.copyTo(output) }
                }
            } else {
                destinationFile.writeBytes(jpegBytes(9))
            }
        }
    }

    private companion object {
        const val TestUri = "content://test/receipt"

        fun jpegBytes(vararg payload: Int): ByteArray = buildList {
            add(0xFF.toByte())
            add(0xD8.toByte())
            payload.forEach { add(it.toByte()) }
            add(0xFF.toByte())
            add(0xD9.toByte())
        }.toByteArray()

        fun pngBytes(): ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
            1, 2, 3
        )

        fun assertImportError(
            expected: ReceiptImageImportError,
            block: suspend () -> Unit
        ) {
            val failure = runBlocking {
                runCatching { block() }.exceptionOrNull()
            }
            assertEquals(expected, (failure as? ReceiptImageImportException)?.error)
        }
    }
}
