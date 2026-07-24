package com.warun.accounting.ui.receipt

import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.camera.ReceiptImageStore

class ReceiptCaptureStartCoordinator(
    private val pendingImageStore: ReceiptImageStore
) {
    fun startNewCapture(
        capturesToDiscard: Iterable<ReceiptCaptureResult?>,
        clearSessionState: () -> Unit,
        openCamera: () -> Unit
    ): Boolean {
        val captureIds = capturesToDiscard
            .mapNotNull { it?.captureId }
            .filter { it.isNotBlank() }
            .distinct()

        if (captureIds.any { !pendingImageStore.canDelete(it) }) return false
        if (captureIds.any { !pendingImageStore.delete(it) }) return false

        clearSessionState()
        openCamera()
        return true
    }
}
