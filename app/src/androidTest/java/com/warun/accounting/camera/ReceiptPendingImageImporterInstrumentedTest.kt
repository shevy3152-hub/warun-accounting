package com.warun.accounting.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptPendingImageImporterInstrumentedTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        root = File(context.cacheDir, "receipt-import-test-${System.nanoTime()}")
        check(root.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun uprightJpegIsCopiedWithoutRecompression() = runBlocking {
        val source = File(root, "source.jpg")
        createJpeg(source, 80, 40)
        val original = source.readBytes()
        val fixture = fixture(source, "upright")

        val result = fixture.importer.importImage(SourceUri)

        assertArrayEquals(original, fixture.store.fileFor(result.captureId).readBytes())
    }

    @Test
    fun transparentPngIsCompositedOnWhiteAndEncodedAsJpeg() = runBlocking {
        val source = File(root, "transparent.png")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        for (y in 0 until bitmap.height) {
            for (x in bitmap.width / 2 until bitmap.width) {
                bitmap.setPixel(x, y, Color.RED)
            }
        }
        FileOutputStream(source).use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
        val fixture = fixture(source, "png")

        val result = fixture.importer.importImage(SourceUri)
        val pending = fixture.store.fileFor(result.captureId)
        val decoded = requireNotNull(BitmapFactory.decodeFile(pending.absolutePath))

        assertEquals(0xFF, pending.inputStream().use { it.read() })
        val white = decoded.getPixel(2, decoded.height / 2)
        val red = decoded.getPixel(decoded.width - 3, decoded.height / 2)
        assertTrue(Color.red(white) > 235 && Color.green(white) > 235 && Color.blue(white) > 235)
        assertTrue(Color.red(red) > 180 && Color.green(red) < 80 && Color.blue(red) < 80)
        decoded.recycle()
    }

    @Test
    fun everyRotatedOrMirroredExifOrientationIsRenderedIntoUprightPixels() = runBlocking {
        val orientations = listOf(
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to listOf(Green, Red, Yellow, Blue),
            ExifInterface.ORIENTATION_ROTATE_180 to listOf(Yellow, Blue, Green, Red),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to listOf(Blue, Yellow, Red, Green),
            ExifInterface.ORIENTATION_TRANSPOSE to listOf(Red, Blue, Green, Yellow),
            ExifInterface.ORIENTATION_ROTATE_90 to listOf(Blue, Red, Yellow, Green),
            ExifInterface.ORIENTATION_TRANSVERSE to listOf(Yellow, Green, Blue, Red),
            ExifInterface.ORIENTATION_ROTATE_270 to listOf(Green, Yellow, Red, Blue)
        )
        orientations.forEachIndexed { index, (orientation, expectedCorners) ->
            val source = File(root, "orientation-$orientation.jpg")
            createQuadrantJpeg(source, 120, 80)
            ExifInterface(source.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val fixture = fixture(source, "orientation-$index")

            val result = fixture.importer.importImage(SourceUri)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(fixture.store.fileFor(result.captureId).absolutePath, options)
            val swapsAxes = orientation in setOf(
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSVERSE,
                ExifInterface.ORIENTATION_ROTATE_270
            )
            assertEquals(if (swapsAxes) 80 else 120, options.outWidth)
            assertEquals(if (swapsAxes) 120 else 80, options.outHeight)
            assertEquals(
                ExifInterface.ORIENTATION_UNDEFINED,
                ExifInterface(fixture.store.fileFor(result.captureId).absolutePath)
                    .getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED
                    )
            )
            val decoded = requireNotNull(
                BitmapFactory.decodeFile(fixture.store.fileFor(result.captureId).absolutePath)
            )
            val actualCorners = listOf(
                decoded.getPixel(decoded.width / 4, decoded.height / 4),
                decoded.getPixel(decoded.width * 3 / 4, decoded.height / 4),
                decoded.getPixel(decoded.width / 4, decoded.height * 3 / 4),
                decoded.getPixel(decoded.width * 3 / 4, decoded.height * 3 / 4)
            )
            expectedCorners.zip(actualCorners).forEach { (expected, actual) ->
                assertColorNear(expected, actual)
            }
            decoded.recycle()
        }
    }

    @Test
    fun sameSourceImportedTwiceUsesDifferentPendingFiles() = runBlocking {
        val source = File(root, "twice.jpg")
        createJpeg(source, 80, 40)
        val ids = ArrayDeque(listOf("first", "second"))
        val store = ReceiptImageStore(File(root, "pending-twice"))
        val importer = importer(source, store) { ids.removeFirst() }

        val first = importer.importImage(SourceUri)
        val second = importer.importImage(SourceUri)

        assertEquals("first", first.captureId)
        assertEquals("second", second.captureId)
        assertTrue(store.fileFor(first.captureId).isFile)
        assertTrue(store.fileFor(second.captureId).isFile)
    }

    private fun fixture(sourceFile: File, captureId: String): Fixture {
        val store = ReceiptImageStore(File(root, "pending-$captureId"))
        return Fixture(store, importer(sourceFile, store) { captureId })
    }

    private fun importer(
        sourceFile: File,
        store: ReceiptImageStore,
        newCaptureId: () -> String
    ) = ReceiptPendingImageImporter(
        source = object : ReceiptExternalImageSource {
            override fun mimeType(uri: String): String? = null
            override fun open(uri: String): InputStream = FileInputStream(sourceFile)
        },
        imageStore = store,
        normalizer = AndroidReceiptImageNormalizer(),
        now = { 1L },
        newCaptureId = newCaptureId,
        outputFactory = ::FileOutputStream
    )

    private fun createJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (y in 0 until height) {
            for (x in 0 until width / 2) {
                bitmap.setPixel(x, y, Color.BLACK)
            }
        }
        FileOutputStream(file).use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it))
        }
        bitmap.recycle()
    }

    private fun createQuadrantJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(
                    x,
                    y,
                    when {
                        x < width / 2 && y < height / 2 -> Red
                        x >= width / 2 && y < height / 2 -> Green
                        x < width / 2 -> Blue
                        else -> Yellow
                    }
                )
            }
        }
        FileOutputStream(file).use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
        }
        bitmap.recycle()
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        val totalDifference =
            kotlin.math.abs(Color.red(expected) - Color.red(actual)) +
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) +
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual))
        assertTrue(
            "expected=${Integer.toHexString(expected)}, actual=${Integer.toHexString(actual)}",
            totalDifference < 90
        )
    }

    private data class Fixture(
        val store: ReceiptImageStore,
        val importer: ReceiptPendingImageImporter
    )

    private companion object {
        const val SourceUri = "content://test/source"
        const val Red = Color.RED
        const val Green = Color.GREEN
        const val Blue = Color.BLUE
        const val Yellow = Color.YELLOW
    }
}
