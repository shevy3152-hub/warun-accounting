package com.warun.accounting.ui.fixedcost

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.warun.accounting.data.AccountingRepository
import com.warun.accounting.data.fixedcost.FixedCostEvidenceAssociationResult
import com.warun.accounting.data.fixedcost.FixedCostEvidenceTarget
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.evidence.FixedCostEvidenceFileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FixedCostEvidenceManagementContext(
    val evidenceId: String,
    val dailyReportId: String,
    val fixedCostType: String
)

data class FixedCostEvidenceAssociationUiState(
    val unclassifiedEvidence: List<EvidenceRecord> = emptyList(),
    val reports: List<DailyReport> = emptyList(),
    val isBusy: Boolean = false,
    val lastOperationId: String? = null,
    val lastResult: FixedCostEvidenceAssociationResult? = null,
    val message: String? = null
)

@HiltViewModel
class FixedCostEvidenceAssociationViewModel @Inject constructor(
    private val repository: AccountingRepository
) : ViewModel() {
    private val _state = MutableStateFlow(FixedCostEvidenceAssociationUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeUnclassifiedFixedCostEvidence().collectLatest { items ->
                _state.value = _state.value.copy(unclassifiedEvidence = items)
            }
        }
        viewModelScope.launch {
            repository.observeDailyReports().collectLatest { reports ->
                _state.value = _state.value.copy(reports = reports)
            }
        }
    }

    fun reassign(
        context: FixedCostEvidenceManagementContext,
        newTarget: FixedCostEvidenceTarget
    ) = runOperation { operationId, executedAt ->
        repository.reassignFixedCostEvidence(
            evidenceId = context.evidenceId,
            expectedCurrentTarget = FixedCostEvidenceTarget(context.dailyReportId, context.fixedCostType),
            newTarget = newTarget,
            operationId = operationId,
            executedAt = executedAt
        )
    }

    fun unlink(context: FixedCostEvidenceManagementContext) = runOperation { operationId, executedAt ->
        repository.unlinkFixedCostEvidence(
            evidenceId = context.evidenceId,
            expectedCurrentTarget = FixedCostEvidenceTarget(context.dailyReportId, context.fixedCostType),
            operationId = operationId,
            executedAt = executedAt
        )
    }

    fun assign(evidenceId: String, target: FixedCostEvidenceTarget) = runOperation { operationId, executedAt ->
        repository.assignFixedCostEvidence(
            evidenceId = evidenceId,
            target = target,
            operationId = operationId,
            executedAt = executedAt
        )
    }

    fun clearResult() {
        _state.value = _state.value.copy(lastOperationId = null, lastResult = null, message = null)
    }

    private fun runOperation(
        operation: suspend (operationId: String, executedAt: Long) -> FixedCostEvidenceAssociationResult
    ) {
        if (_state.value.isBusy) return
        val operationId = UUID.randomUUID().toString()
        _state.value = _state.value.copy(isBusy = true, lastOperationId = null, lastResult = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { operation(operationId, System.currentTimeMillis()) }
                .getOrElse { FixedCostEvidenceAssociationResult.Failure(it) }
            _state.value = _state.value.copy(
                isBusy = false,
                lastOperationId = operationId,
                lastResult = result,
                message = associationResultMessage(result)
            )
        }
    }
}

internal fun associationResultMessage(result: FixedCostEvidenceAssociationResult): String = when (result) {
    FixedCostEvidenceAssociationResult.Success -> "Evidenceの登録先を更新しました。日報の金額は変更されていません。"
    FixedCostEvidenceAssociationResult.CurrentTargetMismatch -> "現在の登録先が変わっています。最新状態を再取得してから操作してください。"
    FixedCostEvidenceAssociationResult.SameTarget -> "同じ登録先は選択できません。"
    FixedCostEvidenceAssociationResult.DailyReportNotFound -> "先に正しい日付の日報を保存してください。"
    FixedCostEvidenceAssociationResult.InvalidFixedCostType -> "固定費種別を確認してください。"
    FixedCostEvidenceAssociationResult.EvidenceNotFound -> "対象Evidenceが見つかりません。最新状態を再取得してください。"
    FixedCostEvidenceAssociationResult.EvidenceNotStored -> "保存済みEvidenceだけを操作できます。"
    FixedCostEvidenceAssociationResult.LinkedToExpense -> "支出に関連付け済みのEvidenceは固定費へ登録できません。"
    FixedCostEvidenceAssociationResult.AlreadyAssigned -> "このEvidenceは既に固定費へ登録されています。"
    FixedCostEvidenceAssociationResult.OperationAlreadyUsed -> "操作は既に完了しています。最新状態を再取得してください。"
    is FixedCostEvidenceAssociationResult.Failure -> "Evidenceの登録先を更新できませんでした。"
}

internal fun fixedCostTypeLabel(type: String): String = when (type) {
    "electricity" -> "電気代"
    "gas" -> "ガス代"
    "water" -> "水道代"
    "communication" -> "通信費"
    else -> type
}

@Composable
internal fun UnclassifiedEvidenceChoiceDialog(
    onNewEvidence: () -> Unit,
    onChooseUnclassified: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("固定費証憑を追加") },
        text = { Text("新しい証憑を追加するか、保存済みの未分類証憑を選べます。") },
        confirmButton = {
            Button(onClick = onNewEvidence, modifier = Modifier.testTag("fixed-cost-add-new")) { Text("新しい証憑を追加") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChooseUnclassified, modifier = Modifier.testTag("fixed-cost-choose-unclassified")) { Text("未分類の証憑から選ぶ") }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        }
    )
}

@Composable
internal fun UnclassifiedEvidencePickerDialog(
    evidence: List<EvidenceRecord>,
    targetDate: String,
    targetType: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("未分類の証憑から選ぶ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("登録先：$targetDate / ${fixedCostTypeLabel(targetType)}")
                Text("保存済みの証憑画像を関連付けます。日報の金額は変更されません。")
                if (evidence.isEmpty()) {
                    Text("未分類の証憑はありません。")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(evidence, key = { it.id }) { item ->
                            val selected = item.id == selectedId
                            OutlinedButton(
                                onClick = { selectedId = item.id },
                                modifier = Modifier.fillMaxWidth().testTag("unclassified-evidence-${item.id}")
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(if (selected) "✓ ${evidenceFileName(item)}" else evidenceFileName(item))
                                    Text("保存日時：${item.storedAt ?: item.createdAt}")
                                    FixedCostStoredEvidencePreview(item)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedId?.let(onConfirm) },
                enabled = selectedId != null,
                modifier = Modifier.testTag("unclassified-evidence-confirm")
            ) { Text("この登録先へ関連付け") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
internal fun FixedCostStoredEvidencePreview(evidence: EvidenceRecord) {
    val context = LocalContext.current
    val state by produceState<Bitmap?>(initialValue = null, key1 = evidence.id, key2 = evidence.sha256) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val store = FixedCostEvidenceFileStore(
                    pendingDirectory = File(context.filesDir, "fixed-cost-evidence/pending"),
                    storedDirectory = File(context.filesDir, "fixed-cost-evidence/stored")
                )
                val file = store.storedFileFor(evidence.id, evidence.mediaType)
                if (!file.isFile || file.length() != evidence.byteSize) return@runCatching null
                if (evidence.mediaType.startsWith("image/")) BitmapFactory.decodeFile(file.absolutePath)
                else android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (renderer.pageCount == 0) null else renderer.openPage(0).use { page ->
                            Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).also { bitmap ->
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }.getOrNull()
        }
    }
    state?.let { bitmap ->
        AndroidView(
            factory = { android.widget.ImageView(context).apply { adjustViewBounds = true } },
            update = { it.setImageBitmap(bitmap) },
            modifier = Modifier.size(84.dp)
        )
    }
}

private fun evidenceFileName(evidence: EvidenceRecord): String =
    "evidence_${evidence.id}.${when (evidence.mediaType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "application/pdf" -> "pdf"
        else -> "bin"
    }}"
