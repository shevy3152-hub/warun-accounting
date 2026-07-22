package com.warun.accounting.ui.receipt

import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.camera.ReceiptImageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptCaptureStartCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun receiptTopAndExpenseEntryUseTheSameCleanupSequence() {
        val pendingDirectory = temporaryFolder.newFolder("pending")
        val store = ReceiptImageStore(pendingDirectory)
        val coordinator = ReceiptCaptureStartCoordinator(store)
        var clearCount = 0
        var openCount = 0

        val receiptTopCapture = capture("receipt-top")
        store.prepareFile(receiptTopCapture.captureId).writeBytes(byteArrayOf(1))
        assertTrue(
            coordinator.startNewCapture(
                capturesToDiscard = listOf(receiptTopCapture),
                clearSessionState = { clearCount += 1 },
                openCamera = { openCount += 1 }
            )
        )
        assertFalse(store.fileFor(receiptTopCapture.captureId).exists())

        val expenseCapture = capture("expense-entry")
        store.prepareFile(expenseCapture.captureId).writeBytes(byteArrayOf(2))
        assertTrue(
            coordinator.startNewCapture(
                capturesToDiscard = listOf(expenseCapture),
                clearSessionState = { clearCount += 1 },
                openCamera = { openCount += 1 }
            )
        )
        assertFalse(store.fileFor(expenseCapture.captureId).exists())
        assertEquals(2, clearCount)
        assertEquals(2, openCount)
    }

    @Test
    fun deletesOnlyIdentifiedPendingCapturesAndNeverTouchesStoredEvidence() {
        val pendingDirectory = temporaryFolder.newFolder("pending")
        val storedDirectory = temporaryFolder.newFolder("stored")
        val store = ReceiptImageStore(pendingDirectory)
        val coordinator = ReceiptCaptureStartCoordinator(store)
        val current = capture("current-capture")
        val previous = capture("previous-capture")
        val unrelated = capture("unrelated-capture")
        store.prepareFile(current.captureId).writeBytes(byteArrayOf(1))
        store.prepareFile(previous.captureId).writeBytes(byteArrayOf(2))
        store.prepareFile(unrelated.captureId).writeBytes(byteArrayOf(3))
        val storedEvidence = storedDirectory.resolve("evidence_${current.captureId}.jpg")
            .also { it.writeBytes(byteArrayOf(9)) }

        assertTrue(
            coordinator.startNewCapture(
                capturesToDiscard = listOf(current, previous, current),
                clearSessionState = {},
                openCamera = {}
            )
        )

        assertFalse(store.fileFor(current.captureId).exists())
        assertFalse(store.fileFor(previous.captureId).exists())
        assertTrue(store.fileFor(unrelated.captureId).exists())
        assertTrue(storedEvidence.exists())
        assertEquals(byteArrayOf(9).toList(), storedEvidence.readBytes().toList())
    }

    @Test
    fun cleanupFailureKeepsStateAndDoesNotOpenCamera() {
        val pendingDirectory = temporaryFolder.newFolder("pending")
        val store = ReceiptImageStore(pendingDirectory)
        val coordinator = ReceiptCaptureStartCoordinator(store)
        val capture = capture("cannot-delete")
        store.fileFor(capture.captureId).apply {
            mkdirs()
            resolve("child").writeText("block directory deletion")
        }
        var cleared = false
        var opened = false

        assertFalse(
            coordinator.startNewCapture(
                capturesToDiscard = listOf(capture),
                clearSessionState = { cleared = true },
                openCamera = { opened = true }
            )
        )

        assertFalse(cleared)
        assertFalse(opened)
    }

    private fun capture(id: String) = ReceiptCaptureResult(
        captureId = id,
        localUri = "file:/pending/receipt_$id.jpg",
        capturedAt = 1L
    )
}
