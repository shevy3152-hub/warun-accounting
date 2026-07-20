package com.warun.accounting.ui.receipt

import com.warun.accounting.camera.ReceiptCaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptCameraViewModelTest {
    @Test
    fun captureCanStartOnlyOnceWhilePreviewing() {
        val viewModel = ReceiptCameraViewModel()
        viewModel.startInitializing()
        assertEquals(ReceiptCameraUiState.Initializing, viewModel.uiState.value)
        viewModel.onPreviewReady()

        assertTrue(viewModel.startCapture())
        assertEquals(ReceiptCameraUiState.Capturing, viewModel.uiState.value)
        assertFalse(viewModel.startCapture())
    }

    @Test
    fun capturedAndRetakeTransitionsKeepOnlyReferenceData() {
        val viewModel = ReceiptCameraViewModel()
        val result = ReceiptCaptureResult("capture-1", "file:/receipt.jpg", 123L)
        viewModel.onCaptured(result)

        assertEquals(ReceiptCameraUiState.Captured(result), viewModel.uiState.value)
        viewModel.resumePreview()
        assertEquals(ReceiptCameraUiState.Previewing, viewModel.uiState.value)
    }

    @Test
    fun initializationFailureTransitionsToError() {
        val viewModel = ReceiptCameraViewModel()
        viewModel.startInitializing()
        viewModel.onError("failure")
        assertEquals(ReceiptCameraUiState.Error("failure"), viewModel.uiState.value)
    }
}
