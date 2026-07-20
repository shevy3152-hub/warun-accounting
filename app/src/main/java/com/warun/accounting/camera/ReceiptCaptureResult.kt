package com.warun.accounting.camera

data class ReceiptCaptureResult(
    val captureId: String,
    val localUri: String,
    val capturedAt: Long
)
