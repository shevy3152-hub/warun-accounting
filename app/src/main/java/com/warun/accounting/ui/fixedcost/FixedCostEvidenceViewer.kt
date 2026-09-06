@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.warun.accounting.ui.fixedcost

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.fixedcost.FixedCostEvidenceTarget
import com.warun.accounting.evidence.FixedCostEvidenceFileStore
import com.warun.accounting.ui.image.ZoomableReceiptImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface FixedCostViewerState {
    data object Loading : FixedCostViewerState
    data class Ready(val bitmap: Bitmap) : FixedCostViewerState
    data object Unavailable : FixedCostViewerState
}

@Composable
fun FixedCostEvidenceViewer(
    evidence: List<EvidenceRecord>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    managementContext: FixedCostEvidenceManagementContext? = null,
    associationViewModel: FixedCostEvidenceAssociationViewModel? = null
) {
    if (evidence.isEmpty()) return
    var index by remember(evidence, initialIndex) {
        mutableIntStateOf(initialIndex.coerceIn(evidence.indices))
    }
    val current = evidence[index]
    val state by fixedCostViewerState(LocalContext.current, current)
    val associationState = associationViewModel?.state?.collectAsStateWithLifecycle()?.value
    var showReassign by remember(current.id) { mutableStateOf(false) }
    var showUnlink by remember(current.id) { mutableStateOf(false) }
    var showReassignConfirm by remember(current.id) { mutableStateOf(false) }
    var selectedDate by remember(current.id) { mutableStateOf<String?>(null) }
    var selectedType by remember(current.id) { mutableStateOf(managementContext?.fixedCostType.orEmpty()) }
    var operationStarted by remember(current.id) { mutableStateOf(false) }

    val managementContextAllowed = managementContext == null || managementContext.evidenceId == evidence.getOrNull(initialIndex.coerceIn(evidence.indices))?.id
    var activeManagementContext by remember(evidence, initialIndex) {
        mutableStateOf(managementContext?.takeIf { managementContextAllowed })
    }
    androidx.compose.runtime.LaunchedEffect(index, current.id) {
        activeManagementContext = activeManagementContext?.copy(evidenceId = current.id)
    }
    val currentContext = activeManagementContext
    val currentReport = associationState?.reports?.firstOrNull { it.id == currentContext?.dailyReportId }
    val selectedReport = associationState?.reports?.firstOrNull { it.reportDate == selectedDate }
    val sameTarget = selectedReport?.id == currentContext?.dailyReportId && selectedType == currentContext?.fixedCostType

    androidx.compose.runtime.LaunchedEffect(associationState?.lastOperationId) {
        if (associationState?.lastResult != null && associationState.lastResult != com.warun.accounting.data.fixedcost.FixedCostEvidenceAssociationResult.Success) {
            operationStarted = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp).testTag("fixed-cost-viewer"),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("fixed-cost-viewer-close")) { Text("閉じる") }
                    Column {
                        Text("保存済み固定費証憑", style = MaterialTheme.typography.titleMedium)
                        Text("${index + 1} / ${evidence.size}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp).testTag("fixed-cost-viewer-metadata")) {
                    Text(fileName(current))
                    Text("MIME: ${current.mediaType}")
                    Text("サイズ: ${current.byteSize} bytes")
                    Text("位置: ${index + 1} / ${evidence.size}")
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (val loaded = state) {
                        FixedCostViewerState.Loading -> Text("表示を準備しています…")
                        FixedCostViewerState.Unavailable -> Text(
                            "表示できません",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("fixed-cost-viewer-unavailable")
                        )
                        is FixedCostViewerState.Ready -> ZoomableReceiptImage(
                            bitmap = loaded.bitmap.asImageBitmap(),
                            imageKey = "${current.id}:${current.sha256}",
                            contentDescription = "保存済み固定費証憑",
                            modifier = Modifier.fillMaxSize().testTag("fixed-cost-viewer-content")
                        )
                    }
                }
                if (currentContext != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("登録先：${currentReport?.reportDate ?: currentContext?.dailyReportId.orEmpty()} / ${fixedCostTypeLabel(currentContext?.fixedCostType.orEmpty())}", modifier = Modifier.testTag("fixed-cost-viewer-current-target"))
                        Button(onClick = { showReassign = true }, enabled = !operationStarted && associationState?.isBusy != true, modifier = Modifier.fillMaxWidth().testTag("fixed-cost-viewer-reassign")) { Text("登録先を変更") }
                        TextButton(onClick = { showUnlink = true }, enabled = !operationStarted && associationState?.isBusy != true, modifier = Modifier.fillMaxWidth().testTag("fixed-cost-viewer-unlink")) { Text("関連付けを解除") }
                        associationState?.message?.let { Text(it, modifier = Modifier.testTag("fixed-cost-viewer-operation-message"), color = if (associationState.lastResult is com.warun.accounting.data.fixedcost.FixedCostEvidenceAssociationResult.Failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { index-- }, enabled = index > 0, modifier = Modifier.testTag("fixed-cost-viewer-previous")) { Text("前へ") }
                    Button(onClick = onDismiss, modifier = Modifier.testTag("fixed-cost-viewer-close-bottom")) { Text("閉じる") }
                    TextButton(onClick = { index++ }, enabled = index < evidence.lastIndex, modifier = Modifier.testTag("fixed-cost-viewer-next")) { Text("次へ") }
                }
            }
        }
    }

    if (showReassign) {
        AlertDialog(
            onDismissRequest = { if (!associationState?.isBusy.orFalse()) showReassign = false },
            title = { Text("登録先を変更") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("変更前：${currentReport?.reportDate ?: currentContext?.dailyReportId.orEmpty()} / ${fixedCostTypeLabel(currentContext?.fixedCostType.orEmpty())}")
                    Text("変更後の日付")
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (associationState?.isBusy != true) expanded = !expanded }) {
                        OutlinedButton(onClick = { expanded = true }, enabled = associationState?.isBusy != true, modifier = Modifier.menuAnchor().fillMaxWidth()) {
                            Text(selectedDate ?: "保存済みの日報から選択")
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                        }
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            associationState?.reports.orEmpty().sortedByDescending { it.reportDate }.forEach { report ->
                                DropdownMenuItem(text = { Text(report.reportDate) }, onClick = { selectedDate = report.reportDate; expanded = false })
                            }
                        }
                    }
                    Text("変更後の固定費種別")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("electricity" to "電気代", "gas" to "ガス代", "water" to "水道代", "communication" to "通信費").forEach { (type, label) ->
                            TextButton(onClick = { selectedType = type }, enabled = associationState?.isBusy != true, modifier = Modifier.testTag("fixed-cost-reassign-type-$type")) { Text(if (selectedType == type) "✓$label" else label) }
                        }
                    }
                    Text("証憑画像の登録先だけを変更します。日報の金額は自動変更されません。")
                    if (selectedDate != null && selectedReport == null) Text("先に正しい日付の日報を保存してください。", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showReassign = false; showReassignConfirm = true },
                    enabled = selectedReport != null && !sameTarget && associationState?.isBusy != true,
                    modifier = Modifier.testTag("fixed-cost-reassign-next")
                ) { Text("変更内容を確認") }
            },
            dismissButton = { TextButton(onClick = { showReassign = false }) { Text("キャンセル") } }
        )
    }
    if (showReassignConfirm) {
        AlertDialog(
            onDismissRequest = { if (!associationState?.isBusy.orFalse()) showReassignConfirm = false },
            title = { Text("登録先を変更しますか") },
            text = { Text("変更前：${currentReport?.reportDate ?: currentContext?.dailyReportId.orEmpty()} / ${fixedCostTypeLabel(currentContext?.fixedCostType.orEmpty())}\n変更後：${selectedReport?.reportDate.orEmpty()} / ${fixedCostTypeLabel(selectedType)}\n証憑画像の登録先だけを変更します。日報の金額は自動変更されません。") },
            confirmButton = {
                Button(onClick = {
                    val vm = associationViewModel
                    val ctx = currentContext
                    val report = selectedReport
                    if (vm == null || ctx == null || report == null) return@Button
                    operationStarted = true
                    vm.reassign(ctx, FixedCostEvidenceTarget(report.id, selectedType))
                    showReassignConfirm = false
                }, enabled = !operationStarted && associationState?.isBusy != true, modifier = Modifier.testTag("fixed-cost-reassign-confirm")) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { showReassignConfirm = false }) { Text("キャンセル") } }
        )
    }
    if (showUnlink) {
        AlertDialog(
            onDismissRequest = { if (!associationState?.isBusy.orFalse()) showUnlink = false },
            title = { Text("関連付けを解除しますか") },
            text = { Text("日報との関連付けだけを解除します。保存済みの証憑画像と日報の金額は削除されません。") },
            confirmButton = {
                OutlinedButton(onClick = {
                    val vm = associationViewModel
                    val ctx = currentContext
                    if (vm == null || ctx == null) return@OutlinedButton
                    operationStarted = true
                    vm.unlink(ctx)
                    showUnlink = false
                }, enabled = !operationStarted && associationState?.isBusy != true, modifier = Modifier.testTag("fixed-cost-unlink-confirm")) { Text("解除する") }
            },
            dismissButton = { TextButton(onClick = { showUnlink = false }) { Text("キャンセル") } }
        )
    }
}

private fun Boolean?.orFalse(): Boolean = this == true

@Composable
private fun fixedCostViewerState(context: android.content.Context, evidence: EvidenceRecord) = produceState<FixedCostViewerState>(
    initialValue = FixedCostViewerState.Loading,
    key1 = evidence.id,
    key2 = evidence.sha256
) {
    value = withContext(Dispatchers.IO) {
        runCatching {
            val store = FixedCostEvidenceFileStore(
                pendingDirectory = File(context.filesDir, "fixed-cost-evidence/pending"),
                storedDirectory = File(context.filesDir, "fixed-cost-evidence/stored")
            )
            val file = store.storedFileFor(evidence.id, evidence.mediaType)
            check(file.isFile && file.length() == evidence.byteSize)
            if (evidence.mediaType.startsWith("image/")) {
                decodeImage(file)
            } else {
                decodePdf(file)
            }
        }.fold(
            onSuccess = { FixedCostViewerState.Ready(it) },
            onFailure = { FixedCostViewerState.Unavailable }
        )
    }
}

private fun decodeImage(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    return requireNotNull(BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
        inSampleSize = sample
    }))
}

private fun decodePdf(file: File): Bitmap {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            check(renderer.pageCount > 0)
            renderer.openPage(0).use { page ->
                val scale = minOf(1f, 2048f / maxOf(page.width, page.height).toFloat())
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }
}

private fun fileName(evidence: EvidenceRecord): String =
    "evidence_${evidence.id}.${when (evidence.mediaType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "application/pdf" -> "pdf"
        else -> "bin"
    }}"
