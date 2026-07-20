package com.warun.accounting.ui.receipt

import androidx.lifecycle.SavedStateHandle
import com.warun.accounting.camera.ReceiptCaptureResult

const val ReceiptCaptureResultKey = "receipt.capture.result"

fun ReceiptCaptureResult.toSavedValue(): ArrayList<String> = arrayListOf(
    captureId,
    localUri,
    capturedAt.toString()
)

fun consumeReceiptCaptureResult(savedStateHandle: SavedStateHandle): ReceiptCaptureResult? {
    val value = savedStateHandle.remove<ArrayList<String>>(ReceiptCaptureResultKey) ?: return null
    val capturedAt = value.getOrNull(2)?.toLongOrNull() ?: return null
    return ReceiptCaptureResult(
        captureId = value.getOrNull(0).orEmpty(),
        localUri = value.getOrNull(1).orEmpty(),
        capturedAt = capturedAt
    )
}
