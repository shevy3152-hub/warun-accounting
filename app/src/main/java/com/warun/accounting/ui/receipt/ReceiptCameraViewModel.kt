package com.warun.accounting.ui.receipt

import androidx.lifecycle.ViewModel
import com.warun.accounting.camera.ReceiptCaptureResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ReceiptCameraUiState {
    data object Idle : ReceiptCameraUiState
    data object Initializing : ReceiptCameraUiState
    data object Previewing : ReceiptCameraUiState
    data object Capturing : ReceiptCameraUiState
    data class Captured(val result: ReceiptCaptureResult) : ReceiptCameraUiState
    data class Error(val message: String) : ReceiptCameraUiState
}

class ReceiptCameraViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<ReceiptCameraUiState>(ReceiptCameraUiState.Idle)
    val uiState: StateFlow<ReceiptCameraUiState> = _uiState.asStateFlow()

    fun startInitializing() {
        _uiState.value = ReceiptCameraUiState.Initializing
    }

    fun onPreviewReady() {
        _uiState.value = ReceiptCameraUiState.Previewing
    }

    fun startCapture(): Boolean {
        if (_uiState.value != ReceiptCameraUiState.Previewing) return false
        _uiState.value = ReceiptCameraUiState.Capturing
        return true
    }

    fun onCaptured(result: ReceiptCaptureResult) {
        _uiState.value = ReceiptCameraUiState.Captured(result)
    }

    fun onError(message: String) {
        _uiState.value = ReceiptCameraUiState.Error(message)
    }

    fun resumePreview() {
        _uiState.value = ReceiptCameraUiState.Previewing
    }
}
