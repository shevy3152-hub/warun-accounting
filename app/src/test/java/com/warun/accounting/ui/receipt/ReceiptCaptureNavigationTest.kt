package com.warun.accounting.ui.receipt

import androidx.lifecycle.SavedStateHandle
import com.warun.accounting.camera.ReceiptCaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptCaptureNavigationTest {
    @Test
    fun resultIsConsumedOnlyOnce() {
        val result = ReceiptCaptureResult("capture-1", "file:/receipt.jpg", 123L)
        val handle = SavedStateHandle(mapOf(ReceiptCaptureResultKey to result.toSavedValue()))

        assertEquals(result, consumeReceiptCaptureResult(handle))
        assertNull(consumeReceiptCaptureResult(handle))
    }
}
