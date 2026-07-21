package com.warun.accounting.ui.receipt

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.future.ReceiptOcrGateway
import com.warun.accounting.future.ReceiptOcrRequest
import com.warun.accounting.ocr.ReceiptOcrException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ReceiptOcrUiState {
    data object Idle : ReceiptOcrUiState
    data class Ready(val capture: ReceiptCaptureResult) : ReceiptOcrUiState
    data class Processing(val capture: ReceiptCaptureResult) : ReceiptOcrUiState
    data class Success(val capture: ReceiptCaptureResult, val rawText: String) : ReceiptOcrUiState
    data class Empty(val capture: ReceiptCaptureResult) : ReceiptOcrUiState
    data class Error(val capture: ReceiptCaptureResult, val message: String) : ReceiptOcrUiState
}

val ReceiptOcrUiState.captureOrNull: ReceiptCaptureResult?
    get() = when (this) {
        ReceiptOcrUiState.Idle -> null
        is ReceiptOcrUiState.Ready -> capture
        is ReceiptOcrUiState.Processing -> capture
        is ReceiptOcrUiState.Success -> capture
        is ReceiptOcrUiState.Empty -> capture
        is ReceiptOcrUiState.Error -> capture
    }

@HiltViewModel
class ReceiptOcrViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val ocrGateway: ReceiptOcrGateway
) : ViewModel() {
    private val _uiState = MutableStateFlow(restoreState())
    val uiState: StateFlow<ReceiptOcrUiState> = _uiState.asStateFlow()
    private var activeJob: Job? = null

    fun runOcr(capture: ReceiptCaptureResult): Boolean {
        val current = _uiState.value
        if (current is ReceiptOcrUiState.Processing) return false
        if (current.captureOrNull?.captureId == capture.captureId && current !is ReceiptOcrUiState.Ready) {
            return false
        }

        persistCapture(capture)
        setState(ReceiptOcrUiState.Processing(capture), StatusProcessing)
        activeJob = viewModelScope.launch {
            runCatching {
                ocrGateway.readReceipt(
                    ReceiptOcrRequest(
                        imageId = capture.captureId,
                        localUri = capture.localUri
                    )
                )
            }.onSuccess { draft ->
                if (_uiState.value.captureOrNull?.captureId != capture.captureId) return@onSuccess
                if (draft.rawText.isBlank()) {
                    setState(ReceiptOcrUiState.Empty(capture), StatusEmpty)
                } else {
                    savedStateHandle[RawTextKey] = draft.rawText
                    setState(ReceiptOcrUiState.Success(capture, draft.rawText), StatusSuccess)
                }
            }.onFailure { error ->
                if (_uiState.value.captureOrNull?.captureId != capture.captureId) return@onFailure
                val message = (error as? ReceiptOcrException)?.message
                    ?: "OCR処理に失敗しました"
                savedStateHandle[ErrorMessageKey] = message
                setState(ReceiptOcrUiState.Error(capture, message), StatusError)
            }
        }
        return true
    }

    fun retry(): Boolean {
        val capture = _uiState.value.captureOrNull ?: return false
        if (_uiState.value is ReceiptOcrUiState.Processing) return false
        setState(ReceiptOcrUiState.Ready(capture), StatusReady)
        return runOcr(capture)
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        savedStateHandle.remove<ArrayList<String>>(CaptureKey)
        savedStateHandle.remove<String>(StatusKey)
        savedStateHandle.remove<String>(RawTextKey)
        savedStateHandle.remove<String>(ErrorMessageKey)
        _uiState.value = ReceiptOcrUiState.Idle
    }

    private fun restoreState(): ReceiptOcrUiState {
        val capture = savedStateHandle.get<ArrayList<String>>(CaptureKey)?.toCaptureResult()
            ?: return ReceiptOcrUiState.Idle
        return when (savedStateHandle.get<String>(StatusKey)) {
            StatusSuccess -> {
                val rawText = savedStateHandle.get<String>(RawTextKey).orEmpty()
                if (rawText.isBlank()) ReceiptOcrUiState.Empty(capture)
                else ReceiptOcrUiState.Success(capture, rawText)
            }
            StatusEmpty -> ReceiptOcrUiState.Empty(capture)
            StatusError -> ReceiptOcrUiState.Error(
                capture,
                savedStateHandle.get<String>(ErrorMessageKey) ?: "OCR処理に失敗しました"
            )
            StatusProcessing,
            StatusReady -> ReceiptOcrUiState.Ready(capture)
            else -> ReceiptOcrUiState.Ready(capture)
        }
    }

    private fun persistCapture(capture: ReceiptCaptureResult) {
        savedStateHandle[CaptureKey] = arrayListOf(
            capture.captureId,
            capture.localUri,
            capture.capturedAt.toString()
        )
        savedStateHandle.remove<String>(RawTextKey)
        savedStateHandle.remove<String>(ErrorMessageKey)
    }

    private fun setState(state: ReceiptOcrUiState, status: String) {
        savedStateHandle[StatusKey] = status
        _uiState.value = state
    }

    private fun ArrayList<String>.toCaptureResult(): ReceiptCaptureResult? {
        val captureId = getOrNull(0).orEmpty()
        val localUri = getOrNull(1).orEmpty()
        val capturedAt = getOrNull(2)?.toLongOrNull() ?: return null
        if (captureId.isBlank()) return null
        return ReceiptCaptureResult(captureId, localUri, capturedAt)
    }

    private companion object {
        const val CaptureKey = "receipt.ocr.capture"
        const val StatusKey = "receipt.ocr.status"
        const val RawTextKey = "receipt.ocr.rawText"
        const val ErrorMessageKey = "receipt.ocr.error"
        const val StatusReady = "ready"
        const val StatusProcessing = "processing"
        const val StatusSuccess = "success"
        const val StatusEmpty = "empty"
        const val StatusError = "error"
    }
}
