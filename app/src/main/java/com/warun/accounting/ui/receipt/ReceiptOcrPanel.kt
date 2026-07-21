package com.warun.accounting.ui.receipt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.camera.ReceiptImageStore
import java.io.File

@Composable
fun ReceiptOcrPanel(
    capturedReceipt: ReceiptCaptureResult?,
    onCaptureCleared: () -> Unit,
    onOpenReceiptCamera: () -> Unit,
    viewModel: ReceiptOcrViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val imageStore = remember(context) {
        ReceiptImageStore(File(context.filesDir, "receipt-images/pending"))
    }
    val uiState by viewModel.uiState.collectAsState()
    val effectiveCapture = capturedReceipt ?: uiState.captureOrNull

    LaunchedEffect(capturedReceipt?.captureId, uiState is ReceiptOcrUiState.Ready) {
        val capture = capturedReceipt ?: (uiState as? ReceiptOcrUiState.Ready)?.capture
        if (capture != null) viewModel.runOcr(capture)
    }

    if (effectiveCapture == null) return

    fun discardCapture() {
        imageStore.delete(effectiveCapture.captureId)
        viewModel.clear()
        onCaptureCleared()
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("OCR確認", style = MaterialTheme.typography.titleMedium)
            when (val state = uiState) {
                ReceiptOcrUiState.Idle,
                is ReceiptOcrUiState.Ready,
                is ReceiptOcrUiState.Processing -> {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    Text("レシートの文字を読み取っています…")
                }
                is ReceiptOcrUiState.Success -> {
                    Text("認識した全文", style = MaterialTheme.typography.labelLarge)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.rawText,
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                }
                is ReceiptOcrUiState.Empty -> {
                    Text("文字を認識できませんでした。画像を確認して再試行してください。")
                    OutlinedButton(onClick = { viewModel.retry() }, modifier = Modifier.fillMaxWidth()) {
                        Text("OCRを再試行")
                    }
                }
                is ReceiptOcrUiState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { viewModel.retry() }, modifier = Modifier.fillMaxWidth()) {
                        Text("OCRを再試行")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        discardCapture()
                        onOpenReceiptCamera()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("再撮影")
                }
                Button(onClick = ::discardCapture, modifier = Modifier.weight(1f)) {
                    Text("手入力へ戻る")
                }
            }
        }
    }
}
