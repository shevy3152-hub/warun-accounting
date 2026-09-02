package com.warun.accounting.ui.fixedcost

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.data.fixedcost.FixedCostSaveResult
import com.warun.accounting.data.fixedcost.fixedCostPaymentMethodOrNull
import com.warun.accounting.evidence.FixedCostEvidenceFileStore
import com.warun.accounting.evidence.FixedCostEvidenceSaveCoordinator
import com.warun.accounting.evidence.FixedCostEvidenceSaveRequest
import com.warun.accounting.evidence.FixedCostEvidenceSource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FixedCostDirectEvidenceUiState(
    val attachments: List<FixedCostEvidenceAttachment> = emptyList(),
    val isSaving: Boolean = false,
    val result: FixedCostSaveResult? = null,
    val message: String? = null
)

@HiltViewModel
class FixedCostDirectEvidenceViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val coordinator: FixedCostEvidenceSaveCoordinator
) : ViewModel() {
    private val _state = MutableStateFlow(
        FixedCostDirectEvidenceUiState(restoreAttachments())
    )
    val state = _state.asStateFlow()

    fun addUri(resolver: ContentResolver, uri: Uri, mediaType: String?) {
        if (_state.value.isSaving) return
        val mime = (mediaType ?: resolver.getType(uri))?.substringBefore(';')?.lowercase()
            ?: return setMessage("ファイル形式を確認できません")
        if (mime !in setOf("image/jpeg", "image/png", "application/pdf")) {
            return setMessage("JPEG、PNG、PDFだけ登録できます")
        }
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                FixedCostEvidenceAttachment(
                    uri.toString(),
                    if (nameIndex >= 0) cursor.getString(nameIndex) ?: "Evidence" else "Evidence",
                    mime,
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L,
                    0
                )
            } ?: FixedCostEvidenceAttachment(uri.toString(), "Evidence", mime, -1L, 0)
        if (_state.value.attachments.any { it.uri == size.uri }) return
        if (size.byteSize !in 1..FixedCostEvidenceFileStore.MaxBytes) {
            return setMessage("Evidenceのサイズを確認できません")
        }
        update(_state.value.attachments + size)
    }

    fun save(resolver: ContentResolver, dailyReportId: String, fixedCostType: String, amount: Long) {
        val current = _state.value
        if (current.isSaving || amount <= 0L || current.attachments.isEmpty()) return
        _state.value = current.copy(isSaving = true, result = null, message = null)
        viewModelScope.launch {
            val key = "direct:$dailyReportId:$fixedCostType"
            val receiptId = "fixed-cost-" + UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8))
            val result = coordinator.saveResult(
                resolver,
                FixedCostEvidenceSaveRequest(
                    receiptId = receiptId,
                    dailyReportId = dailyReportId,
                    fixedCostType = fixedCostType,
                    paymentMethod = fixedCostPaymentMethodOrNull(fixedCostType).orEmpty(),
                    appliedAmount = amount,
                    sources = current.attachments.map { FixedCostEvidenceSource(Uri.parse(it.uri), it.mediaType, it.sortOrder) },
                    directRegistrationKey = key
                )
            )
            _state.value = _state.value.copy(
                isSaving = false,
                result = result,
                message = if (result == FixedCostSaveResult.Success) "証憑を保存しました。" else "証憑を保存できませんでした。"
            )
        }
    }

    private fun setMessage(message: String) { _state.value = _state.value.copy(message = message) }
    private fun update(items: List<FixedCostEvidenceAttachment>) {
        val normalized = normalizeFixedCostEvidenceAttachments(items)
        savedStateHandle[AttachmentsKey] = ArrayList(normalized.map { listOf(it.uri, it.displayName, it.mediaType, it.byteSize.toString()).joinToString("\u001f") })
        _state.value = _state.value.copy(attachments = normalized, message = null)
    }
    private fun restoreAttachments() = savedStateHandle.get<ArrayList<String>>(AttachmentsKey).orEmpty().mapNotNull { value ->
        value.split("\u001f").takeIf { it.size == 4 }?.let { p -> FixedCostEvidenceAttachment(p[0], p[1], p[2], p[3].toLongOrNull() ?: -1L, 0) }
    }
    private companion object { const val AttachmentsKey = "fixed-cost-direct-attachments" }
}

@Composable
fun FixedCostDirectEvidenceScreen(
    dailyReportId: String,
    fixedCostType: String,
    amount: Long,
    captured: ReceiptCaptureResult?,
    onCaptureConsumed: () -> Unit,
    onOpenCamera: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: FixedCostDirectEvidenceViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.addUri(context.contentResolver, uri, context.contentResolver.getType(uri))
    }
    LaunchedEffect(captured?.captureId) {
        captured?.let {
            viewModel.addUri(context.contentResolver, Uri.parse(it.localUri), "image/jpeg")
            onCaptureConsumed()
        }
    }
    LaunchedEffect(state.result) { if (state.result == FixedCostSaveResult.Success) onDone() }
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("fixed-cost-direct-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${fixedCostLabelForUi(fixedCostType)}の証憑", style = MaterialTheme.typography.headlineSmall)
        Text("日報金額：${amount}円")
        if (amount <= 0L) Text("金額を入力して保存してから証憑を登録してください。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenCamera, enabled = !state.isSaving && amount > 0L, modifier = Modifier.testTag("fixed-cost-direct-camera")) { Text("カメラ撮影") }
            Button(onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) }, enabled = !state.isSaving && amount > 0L, modifier = Modifier.testTag("fixed-cost-direct-picker")) { Text("ファイル選択") }
        }
        state.attachments.forEach { item ->
            Text("${item.displayName} / ${item.mediaType} / ${item.byteSize} bytes")
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("fixed-cost-direct-message")) }
        Button(
            onClick = { viewModel.save(context.contentResolver, dailyReportId, fixedCostType, amount) },
            enabled = !state.isSaving && amount > 0L && state.attachments.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("fixed-cost-direct-save")
        ) { if (state.isSaving) CircularProgressIndicator() else Text("確認して保存") }
        TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("閉じる") }
    }
}

private fun fixedCostLabelForUi(type: String): String = when (type) {
    "electricity" -> "電気代"
    "water" -> "水道代"
    "communication" -> "通信費"
    "gas" -> "ガス代"
    else -> "固定費"
}
