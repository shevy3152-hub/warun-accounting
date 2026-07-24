package com.warun.accounting.ui.receipt

import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.camera.ReceiptImageStore

data class ReceiptCaptureDiscardResult(
    val deletedCaptureIds: Set<String>,
    val retainedCaptureIds: Set<String>
) {
    val allDiscarded: Boolean = retainedCaptureIds.isEmpty()
}

class ReceiptCaptureStartCoordinator(
    private val pendingImageStore: ReceiptImageStore
) {
    fun startNewCapture(
        capturesToDiscard: Iterable<ReceiptCaptureResult?>,
        clearSessionState: () -> Unit,
        openCamera: () -> Unit
    ): Boolean {
        val result = discardCaptures(capturesToDiscard)
        if (!result.allDiscarded) return false

        clearSessionState()
        openCamera()
        return true
    }

    fun adoptImportedCapture(
        importedCapture: ReceiptCaptureResult,
        capturesToDiscard: Iterable<ReceiptCaptureResult?>,
        clearOcrSessionState: () -> Unit,
        adoptCapture: (ReceiptCaptureResult) -> Unit,
        clearDiscardedOwnership: (Set<String>) -> Unit
    ): ReceiptCaptureDiscardResult {
        clearOcrSessionState()
        adoptCapture(importedCapture)
        val result = discardCaptures(
            capturesToDiscard.filter { it?.captureId != importedCapture.captureId }
        )
        clearDiscardedOwnership(result.deletedCaptureIds)
        return result
    }

    fun discardCaptures(
        capturesToDiscard: Iterable<ReceiptCaptureResult?>
    ): ReceiptCaptureDiscardResult {
        val captureIds = capturesToDiscard
            .mapNotNull { it?.captureId }
            .filter { it.isNotBlank() }
            .distinct()

        if (captureIds.any { !pendingImageStore.canDelete(it) }) {
            return ReceiptCaptureDiscardResult(
                deletedCaptureIds = emptySet(),
                retainedCaptureIds = captureIds.toSet()
            )
        }

        val deleted = mutableSetOf<String>()
        val retained = mutableSetOf<String>()
        captureIds.forEach { captureId ->
            if (pendingImageStore.delete(captureId)) {
                deleted += captureId
            } else {
                retained += captureId
            }
        }
        return ReceiptCaptureDiscardResult(
            deletedCaptureIds = deleted,
            retainedCaptureIds = retained
        )
    }
}
