@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.warun.accounting.ui.receipt

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.warun.accounting.ui.input.DateInputTextField
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.camera.ReceiptImageStore
import com.warun.accounting.ocr.parser.ReceiptCandidateConfidence
import com.warun.accounting.ocr.parser.ReceiptCandidateEvidence
import com.warun.accounting.ui.image.ZoomableReceiptImage
import com.warun.accounting.ui.viewmodel.ExpenseInput
import java.text.NumberFormat
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReceiptOcrPanel(
    capturedReceipt: ReceiptCaptureResult?,
    onCaptureCleared: () -> Unit,
    onOpenReceiptCamera: () -> Unit,
    knownStoreNames: List<String> = emptyList(),
    existingExpense: ExpenseInput? = null,
    existingPendingCapture: ReceiptCaptureResult? = null,
    onApplyToExpense: ((ReceiptOcrApplyResult, String) -> Boolean)? = null,
    viewModel: ReceiptOcrViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val imageStore = remember(context) {
        journalProtectedReceiptImageStore(context)
    }
    val uiState by viewModel.uiState.collectAsState()
    val effectiveCapture = capturedReceipt ?: uiState.captureOrNull

    LaunchedEffect(knownStoreNames) {
        viewModel.updateKnownStoreNames(knownStoreNames)
    }

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
            ReceiptCapturePreview(effectiveCapture, imageStore)
            when (val state = uiState) {
                ReceiptOcrUiState.Idle,
                is ReceiptOcrUiState.Ready,
                is ReceiptOcrUiState.Processing -> {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    Text("レシートの文字を読み取っています…")
                }
                is ReceiptOcrUiState.Success -> {
                    val parseResult = state.parseResult
                    val review = state.review
                    val applyResult = viewModel.createApplyResult(existingExpense)
                    Text("抽出候補を確認・編集", style = MaterialTheme.typography.labelLarge)
                    EditableCandidate(
                        label = "支払先／店舗名",
                        value = review.supplierName,
                        onValueChange = viewModel::updateSupplierName,
                        error = review.supplierError,
                        evidence = parseResult.bestStore?.evidence,
                        confidence = parseResult.bestStore?.confidence,
                        alternatives = parseResult.storeCandidates.drop(1).take(3).map {
                            it.displayName to it.displayName
                        },
                        onAlternativeSelected = viewModel::updateSupplierName,
                        onConfirmLowConfidence = viewModel::confirmSupplier,
                        isConfirmed = review.supplierConfirmed,
                        confirmLabel = if (review.supplierName.isBlank()) {
                            "支払先を空欄で反映することを確認"
                        } else {
                            "この支払先候補を確認"
                        }
                    )
                    EditableCandidate(
                        label = "購入日",
                        value = review.purchaseDate,
                        onValueChange = viewModel::updatePurchaseDate,
                        error = review.purchaseDateError,
                        evidence = parseResult.bestDateTime?.evidence,
                        confidence = parseResult.bestDateTime?.confidence,
                        alternatives = parseResult.dateTimeCandidates.drop(1).take(3).map {
                            it.normalizedValue to it.normalizedDate
                        },
                        onAlternativeSelected = viewModel::updatePurchaseDate,
                        onConfirmLowConfidence = viewModel::confirmPurchaseDate,
                        isConfirmed = review.purchaseDateConfirmed,
                        confirmLabel = "この購入日候補を確認",
                        isDateInput = true
                    )
                    EditableCandidate(
                        label = "合計金額",
                        value = review.totalAmount,
                        onValueChange = viewModel::updateTotalAmount,
                        error = review.totalAmountError,
                        evidence = parseResult.bestTotalAmount?.evidence,
                        confidence = parseResult.bestTotalAmount?.confidence,
                        alternatives = parseResult.totalAmountCandidates.drop(1).take(3).map {
                            "${NumberFormat.getNumberInstance(Locale.JAPAN).format(it.amount)}円" to it.amount.toString()
                        },
                        onAlternativeSelected = viewModel::updateTotalAmount,
                        onConfirmLowConfidence = viewModel::confirmTotalAmount,
                        isConfirmed = review.totalAmountConfirmed,
                        confirmLabel = "この合計金額候補を確認",
                        keyboardType = KeyboardType.Number
                    )
                    if (existingExpense != null && applyResult != null) {
                        ReceiptMergeNotice(planReceiptOcrMerge(existingExpense, applyResult))
                    }
                    if (onApplyToExpense != null) {
                        Button(
                            onClick = {
                                applyCurrentReceiptOcr(
                                    result = applyResult,
                                    expectedCaptureId = effectiveCapture.captureId,
                                    applyToExpense = onApplyToExpense,
                                    onConsumed = {
                                        existingPendingCapture
                                            ?.takeIf { it.captureId != applyResult?.capture?.captureId }
                                            ?.let { imageStore.delete(it.captureId) }
                                        viewModel.clear()
                                        onCaptureCleared()
                                    }
                                )
                            },
                            enabled = review.canApplyWithExisting(existingExpense),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("支出入力へ反映")
                        }
                        Text(
                            "反映するのは支払先・支出日・金額だけです。カテゴリ・支払方法・メモは保持されます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                    onClick = onOpenReceiptCamera,
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

internal fun applyCurrentReceiptOcr(
    result: ReceiptOcrApplyResult?,
    expectedCaptureId: String,
    applyToExpense: (ReceiptOcrApplyResult, String) -> Boolean,
    onConsumed: () -> Unit
): Boolean {
    val currentResult = result?.takeIf { it.capture.captureId == expectedCaptureId } ?: return false
    if (!applyToExpense(currentResult, expectedCaptureId)) return false
    onConsumed()
    return true
}

@Composable
private fun ReceiptCapturePreview(
    capture: ReceiptCaptureResult,
    imageStore: ReceiptImageStore
) {
    key(capture.captureId) {
        var isExpanded by remember(capture.captureId) { mutableStateOf(false) }
        val previewState by produceState<ReceiptPreviewState>(
            initialValue = ReceiptPreviewState.Loading,
            key1 = capture.captureId,
            key2 = capture.localUri
        ) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    decodeReceiptPreview(imageStore.fileFor(capture.captureId))
                }.getOrNull()?.let(ReceiptPreviewState::Loaded)
                    ?: ReceiptPreviewState.Unavailable
            }
        }
        when (val state = previewState) {
            ReceiptPreviewState.Loading -> Text(
                "撮影画像を読み込んでいます…",
                style = MaterialTheme.typography.bodySmall
            )
            ReceiptPreviewState.Unavailable -> Text(
                "撮影画像を表示できません",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            is ReceiptPreviewState.Loaded -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        bitmap = state.bitmap,
                        contentDescription = "撮影したレシート。タップして拡大",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { isExpanded = true }
                    )
                    Text(
                        "タップして拡大",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isExpanded) {
                    PendingReceiptImageDialog(
                        capture = capture,
                        imageStore = imageStore,
                        onDismiss = { isExpanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingReceiptImageDialog(
    capture: ReceiptCaptureResult,
    imageStore: ReceiptImageStore,
    onDismiss: () -> Unit
) {
    val imageState by produceState<ReceiptPreviewState>(
        initialValue = ReceiptPreviewState.Loading,
        key1 = capture.captureId,
        key2 = capture.localUri
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                decodeReceiptPreview(
                    file = imageStore.fileFor(capture.captureId),
                    maxDimension = ReceiptExpandedPreviewMaxDimension
                )
            }.getOrNull()?.let(ReceiptPreviewState::Loaded)
                ?: ReceiptPreviewState.Unavailable
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "拡大表示を閉じる"
                        )
                    }
                    Column {
                        Text("OCR確認画像", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "ピンチで拡大し、拡大中は1本指で移動できます",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                when (val state = imageState) {
                    ReceiptPreviewState.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                    ReceiptPreviewState.Unavailable -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "画像を表示できません",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    is ReceiptPreviewState.Loaded -> ZoomableReceiptImage(
                        bitmap = state.bitmap,
                        imageKey = capture.captureId,
                        contentDescription = "拡大表示したOCR確認画像",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private fun decodeReceiptPreview(
    file: File,
    maxDimension: Int = ReceiptPreviewMaxDimension
): ImageBitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )?.asImageBitmap()
}

private sealed interface ReceiptPreviewState {
    data object Loading : ReceiptPreviewState
    data object Unavailable : ReceiptPreviewState
    data class Loaded(val bitmap: ImageBitmap) : ReceiptPreviewState
}

private const val ReceiptPreviewMaxDimension = 1_600
private const val ReceiptExpandedPreviewMaxDimension = 2_048

@Composable
private fun EditableCandidate(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    evidence: ReceiptCandidateEvidence?,
    confidence: ReceiptCandidateConfidence?,
    alternatives: List<Pair<String, String>>,
    onAlternativeSelected: (String) -> Unit,
    onConfirmLowConfidence: () -> Unit,
    isConfirmed: Boolean,
    confirmLabel: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isDateInput: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isDateInput) {
            DateInputTextField(
                label = label,
                value = value,
                onValueChange = onValueChange,
                isError = error != null,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (evidence == null) {
            Text(
                "OCR候補：未検出",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (confidence != null) {
            val source = evidence.lines.joinToString(" / ") { it.original.trim() }.take(120)
            Text(
                text = "根拠：${evidence.reason}（${confidence.label}）${if (source.isBlank()) "" else " / $source"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (alternatives.isNotEmpty()) {
            Text("代替候補", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                alternatives.forEach { (display, editValue) ->
                    OutlinedButton(onClick = { onAlternativeSelected(editValue) }) {
                        Text(display)
                    }
                }
            }
        }
        if (!isConfirmed && (confidence == ReceiptCandidateConfidence.Low || value.isBlank())) {
            OutlinedButton(onClick = onConfirmLowConfidence, modifier = Modifier.fillMaxWidth()) {
                Text(confirmLabel)
            }
        }
    }
}

@Composable
private fun ReceiptMergeNotice(plan: ReceiptOcrMergePlan) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("支出入力への反映内容", style = MaterialTheme.typography.labelLarge)
            plan.displayLines().forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val ReceiptCandidateConfidence.label: String
    get() = when (this) {
        ReceiptCandidateConfidence.High -> "高"
        ReceiptCandidateConfidence.Medium -> "中"
        ReceiptCandidateConfidence.Low -> "低"
    }
