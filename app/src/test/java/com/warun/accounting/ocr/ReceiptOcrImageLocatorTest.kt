package com.warun.accounting.ocr

import com.warun.accounting.camera.ReceiptImageStore
import com.warun.accounting.future.ReceiptOcrRequest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptOcrImageLocatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun captureIdResolvesPendingImageBeforeLocalUri() {
        val directory = temporaryFolder.newFolder("pending")
        val store = ReceiptImageStore(directory)
        val image = store.prepareFile("capture-1").apply { writeText("jpeg") }
        val locator = ReceiptOcrImageLocator(store)

        assertEquals(
            image.toURI().toString(),
            locator.locate(ReceiptOcrRequest("capture-1", "file:/missing.jpg"))
        )
    }

    @Test
    fun missingCaptureAndMissingFileUriAreRejected() {
        val directory = temporaryFolder.newFolder("pending")
        val locator = ReceiptOcrImageLocator(ReceiptImageStore(directory))
        val missing = File(directory, "missing.jpg").toURI().toString()

        assertThrows(ReceiptOcrImageNotFoundException::class.java) {
            locator.locate(ReceiptOcrRequest("capture-1", missing))
        }
    }

    @Test
    fun missingCaptureAndEmptyUriAreRejected() {
        val directory = temporaryFolder.newFolder("pending")
        val locator = ReceiptOcrImageLocator(ReceiptImageStore(directory))

        assertThrows(ReceiptOcrImageNotFoundException::class.java) {
            locator.locate(ReceiptOcrRequest("capture-1", ""))
        }
    }
}
