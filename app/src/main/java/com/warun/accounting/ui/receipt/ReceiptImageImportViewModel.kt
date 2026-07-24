package com.warun.accounting.ui.receipt

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.camera.ReceiptImageImportError
import com.warun.accounting.camera.ReceiptImageImportException
import com.warun.accounting.camera.ReceiptImageImportGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ReceiptImageImportUiState {
    data object Idle : ReceiptImageImportUiState
    data class Importing(val requestId: String) : ReceiptImageImportUiState
    data class Imported(val result: ReceiptCaptureResult) : ReceiptImageImportUiState
    data class Error(val error: ReceiptImageImportError) : ReceiptImageImportUiState
}

@HiltViewModel
class ReceiptImageImportViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val importer: ReceiptImageImportGateway
) : ViewModel() {
    private companion object {
        const val StatusKey = "receipt.import.status"
        const val ResultKey = "receipt.import.result"
        const val StatusImporting = "importing"
        const val StatusImported = "imported"
        const val StatusError = "error"
        const val ErrorKey = "receipt.import.error"
    }

    private val _uiState = MutableStateFlow(restoreState())
    val uiState: StateFlow<ReceiptImageImportUiState> = _uiState.asStateFlow()
    private var activeRequestId: String? = null
    private var activeJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { importer.cleanupOrphanedImports() }
        }
    }

    fun handlePickerResult(externalUri: String?): Boolean =
        externalUri?.let(::importImage) ?: false

    fun importImage(externalUri: String): Boolean {
        if (
            externalUri.isBlank() ||
            _uiState.value is ReceiptImageImportUiState.Importing ||
            _uiState.value is ReceiptImageImportUiState.Imported
        ) {
            return false
        }
        val requestId = UUID.randomUUID().toString()
        activeRequestId = requestId
        savedStateHandle[StatusKey] = StatusImporting
        savedStateHandle.remove<ArrayList<String>>(ResultKey)
        savedStateHandle.remove<String>(ErrorKey)
        _uiState.value = ReceiptImageImportUiState.Importing(requestId)
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            runCatching { importer.importImage(externalUri) }
                .onSuccess { result ->
                    if (activeRequestId != requestId) return@onSuccess
                    activeRequestId = null
                    activeJob = null
                    savedStateHandle[StatusKey] = StatusImported
                    savedStateHandle[ResultKey] = result.toSavedStrings()
                    _uiState.value = ReceiptImageImportUiState.Imported(result)
                }
                .onFailure { failure ->
                    if (failure is CancellationException || activeRequestId != requestId) {
                        return@onFailure
                    }
                    activeRequestId = null
                    activeJob = null
                    val error = (failure as? ReceiptImageImportException)?.error
                        ?: ReceiptImageImportError.ImportFailed
                    savedStateHandle[StatusKey] = StatusError
                    savedStateHandle[ErrorKey] = error.name
                    _uiState.value = ReceiptImageImportUiState.Error(error)
                }
        }
        return true
    }

    fun consumeImported(captureId: String): ReceiptCaptureResult? {
        val imported = (_uiState.value as? ReceiptImageImportUiState.Imported)?.result
            ?: return null
        if (imported.captureId != captureId) return null
        clearPersistedState()
        _uiState.value = ReceiptImageImportUiState.Idle
        return imported
    }

    fun clearError() {
        if (_uiState.value !is ReceiptImageImportUiState.Error) return
        clearPersistedState()
        _uiState.value = ReceiptImageImportUiState.Idle
    }

    fun reset() {
        activeRequestId = null
        activeJob?.cancel()
        activeJob = null
        clearPersistedState()
        _uiState.value = ReceiptImageImportUiState.Idle
    }

    private fun restoreState(): ReceiptImageImportUiState {
        return when (savedStateHandle.get<String>(StatusKey)) {
            StatusImported -> {
                savedStateHandle.get<ArrayList<String>>(ResultKey)
                    ?.toCaptureResult()
                    ?.let(ReceiptImageImportUiState::Imported)
                    ?: ReceiptImageImportUiState.Error(ReceiptImageImportError.ImportFailed)
            }
            StatusImporting -> {
                savedStateHandle[StatusKey] = StatusError
                savedStateHandle[ErrorKey] = ReceiptImageImportError.ImportFailed.name
                ReceiptImageImportUiState.Error(ReceiptImageImportError.ImportFailed)
            }
            StatusError -> {
                val error = savedStateHandle.get<String>(ErrorKey)
                    ?.let { value ->
                        ReceiptImageImportError.entries.firstOrNull { it.name == value }
                    }
                    ?: ReceiptImageImportError.ImportFailed
                ReceiptImageImportUiState.Error(error)
            }
            else -> ReceiptImageImportUiState.Idle
        }
    }

    private fun clearPersistedState() {
        savedStateHandle.remove<String>(StatusKey)
        savedStateHandle.remove<ArrayList<String>>(ResultKey)
        savedStateHandle.remove<String>(ErrorKey)
    }
}

private fun ReceiptCaptureResult.toSavedStrings(): ArrayList<String> = arrayListOf(
    captureId,
    localUri,
    capturedAt.toString()
)

private fun ArrayList<String>.toCaptureResult(): ReceiptCaptureResult? {
    val captureId = getOrNull(0).orEmpty()
    val localUri = getOrNull(1).orEmpty()
    val capturedAt = getOrNull(2)?.toLongOrNull() ?: return null
    if (captureId.isBlank() || localUri.isBlank()) return null
    return ReceiptCaptureResult(captureId, localUri, capturedAt)
}
