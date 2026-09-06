package com.warun.accounting.ui.fixedcost

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val ownedPersistableUriGrants = mutableSetOf<String>()
    private val _state = MutableStateFlow(
        FixedCostDirectEvidenceUiState(restoreAttachments())
    )
    val state = _state.asStateFlow()

    fun recoverPreparedJournal(dailyReportId: String, fixedCostType: String) {
        val key = "direct:$dailyReportId:$fixedCostType"
        val applicationId = UUID.nameUUIDFromBytes(
            "fixed-cost-application:$key".toByteArray(StandardCharsets.UTF_8)
        ).toString()
        viewModelScope.launch(Dispatchers.IO) {
            if (coordinator.abandonEligiblePreparedJournal(applicationId) ==
                com.warun.accounting.evidence.FixedCostPreparedJournalRecovery.Protected
            ) {
                _state.value = _state.value.copy(
                    message = resultMessage(FixedCostSaveResult.RecoveryRequired(
                        IllegalStateException("Fixed-cost journal requires recovery")
                    ))
                )
            }
        }
    }

    fun addUri(resolver: ContentResolver, uri: Uri, mediaType: String?) {
        if (_state.value.isSaving) return
        val mime = (mediaType ?: resolver.getType(uri))?.substringBefore(';')?.lowercase()
            ?: return setMessage("ファイル形式を確認できません")
        if (mime !in setOf("image/jpeg", "image/png", "application/pdf")) {
            return setMessage("JPEG、PNG、PDFだけ登録できます")
        }
        if (_state.value.attachments.any { it.uri == uri.toString() }) return
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val persisted = runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (persisted) ownedPersistableUriGrants += uri.toString()
        }
        viewModelScope.launch {
            when (val inspected = withContext(Dispatchers.IO) {
                inspectFixedCostEvidenceUri(resolver, uri, mime)
            }) {
                is FixedCostEvidenceUriInspection.Ready -> update(
                    _state.value.attachments + FixedCostEvidenceAttachment(
                        uri.toString(), inspected.metadata.displayName, inspected.metadata.mediaType,
                        inspected.metadata.byteSize, 0
                    )
                )
                FixedCostEvidenceUriInspection.TooLarge -> {
                    releaseOwnedGrant(resolver, uri.toString())
                    setMessage("Evidenceが50 MiBを超えています")
                }
                FixedCostEvidenceUriInspection.Unavailable -> {
                    releaseOwnedGrant(resolver, uri.toString())
                    setMessage("Evidenceのサイズを確認できません。別のファイルを選択してください")
                }
            }
        }
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
                message = resultMessage(result)
            )
            if (result == FixedCostSaveResult.Success) {
                current.attachments.forEach { releaseOwnedGrant(resolver, it.uri) }
            }
        }
    }

    fun cancel(resolver: ContentResolver) {
        _state.value.attachments.forEach { releaseOwnedGrant(resolver, it.uri) }
        _state.value = _state.value.copy(attachments = emptyList())
        savedStateHandle.remove<ArrayList<String>>(AttachmentsKey)
    }

    private fun releaseOwnedGrant(resolver: ContentResolver, uri: String) {
        if (ownedPersistableUriGrants.remove(uri)) {
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
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
    viewModel: FixedCostDirectEvidenceViewModel = hiltViewModel(),
    associationViewModel: FixedCostEvidenceAssociationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val associationState by associationViewModel.state.collectAsStateWithLifecycle()
    var showAddChoice by remember { mutableStateOf(false) }
    var showUnclassified by remember { mutableStateOf(false) }
    var pendingAssignment by remember { mutableStateOf<String?>(null) }
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
    LaunchedEffect(dailyReportId, fixedCostType) {
        viewModel.recoverPreparedJournal(dailyReportId, fixedCostType)
    }
    LaunchedEffect(state.result) { if (state.result == FixedCostSaveResult.Success) onDone() }
    LaunchedEffect(associationState.lastOperationId) {
        if (pendingAssignment != null && associationState.lastOperationId != null) {
            val success = associationState.lastResult == com.warun.accounting.data.fixedcost.FixedCostEvidenceAssociationResult.Success
            pendingAssignment = null
            associationViewModel.clearResult()
            if (success) onDone()
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("fixed-cost-direct-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${fixedCostLabelForUi(fixedCostType)}の証憑", style = MaterialTheme.typography.headlineSmall)
        Text("日報金額：${amount}円")
        if (amount <= 0L) Text("金額を入力して保存してから証憑を登録してください。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenCamera, enabled = !state.isSaving && amount > 0L, modifier = Modifier.testTag("fixed-cost-direct-camera")) { Text("カメラ撮影") }
            Button(onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) }, enabled = !state.isSaving && amount > 0L, modifier = Modifier.testTag("fixed-cost-direct-picker")) { Text("ファイル選択") }
        }
        if (associationState.unclassifiedEvidence.isNotEmpty()) {
            OutlinedButton(
                onClick = { showAddChoice = true },
                enabled = !state.isSaving && amount > 0L && !associationState.isBusy,
                modifier = Modifier.fillMaxWidth().testTag("fixed-cost-direct-unclassified")
            ) { Text("未分類の証憑から選ぶ") }
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
        TextButton(onClick = { viewModel.cancel(context.contentResolver); onDismiss() }, enabled = !state.isSaving) { Text("閉じる") }
    }
    if (showAddChoice) UnclassifiedEvidenceChoiceDialog(
        onNewEvidence = { showAddChoice = false; picker.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) },
        onChooseUnclassified = { showAddChoice = false; showUnclassified = true },
        onDismiss = { showAddChoice = false }
    )
    if (showUnclassified) UnclassifiedEvidencePickerDialog(
        evidence = associationState.unclassifiedEvidence,
        targetDate = associationState.reports.firstOrNull { it.id == dailyReportId }?.reportDate ?: "対象日報",
        targetType = fixedCostType,
        onConfirm = { evidenceId ->
            pendingAssignment = evidenceId
            associationViewModel.assign(evidenceId, com.warun.accounting.data.fixedcost.FixedCostEvidenceTarget(dailyReportId, fixedCostType))
            showUnclassified = false
        },
        onDismiss = { showUnclassified = false }
    )
}

private fun fixedCostLabelForUi(type: String): String = when (type) {
    "electricity" -> "電気代"
    "water" -> "水道代"
    "communication" -> "通信費"
    "gas" -> "ガス代"
    else -> "固定費"
}
