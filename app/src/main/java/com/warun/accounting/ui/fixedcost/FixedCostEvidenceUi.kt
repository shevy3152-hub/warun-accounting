@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.warun.accounting.ui.fixedcost

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.warun.accounting.data.AccountingRepository
import com.warun.accounting.data.fixedcost.ExistingAmountState
import com.warun.accounting.data.fixedcost.FixedCostDetailSnapshot
import com.warun.accounting.data.fixedcost.FixedCostSaveResult
import com.warun.accounting.evidence.FixedCostEvidenceFileStore
import com.warun.accounting.evidence.FixedCostEvidenceSaveCoordinator
import com.warun.accounting.evidence.FixedCostEvidenceSaveRequest
import com.warun.accounting.evidence.FixedCostEvidenceSource
import com.warun.accounting.ui.util.toYen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FixedCostEvidenceAttachment(
    val uri: String,
    val displayName: String,
    val mediaType: String,
    val byteSize: Long,
    val sortOrder: Int
)

data class FixedCostEvidenceUiState(
    val snapshot: FixedCostDetailSnapshot? = null,
    val selectedType: String = "electricity",
    val selectedDate: String? = null,
    val availableReportDates: List<String> = emptyList(),
    val attachments: List<FixedCostEvidenceAttachment> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val message: String? = null,
    val result: FixedCostSaveResult? = null
)

fun normalizeFixedCostEvidenceAttachments(
    attachments: List<FixedCostEvidenceAttachment>
): List<FixedCostEvidenceAttachment> = attachments
    .distinctBy { it.uri }
    .mapIndexed { index, item -> item.copy(sortOrder = index) }

fun fixedCostEvidenceCanSave(
    snapshot: FixedCostDetailSnapshot?,
    attachments: List<FixedCostEvidenceAttachment>,
    isSaving: Boolean
): Boolean = snapshot?.dailyReport != null &&
    snapshot.existingAmountState != ExistingAmountState.CONFLICT &&
    attachments.isNotEmpty() &&
    attachments.all { it.byteSize in 1..FixedCostEvidenceFileStore.MaxBytes } &&
    !isSaving

@HiltViewModel
class FixedCostEvidenceViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: AccountingRepository,
    private val coordinator: FixedCostEvidenceSaveCoordinator
) : ViewModel() {
    private val ownedPersistableUriGrants = mutableSetOf<String>()
    private val _state = MutableStateFlow(
        FixedCostEvidenceUiState(
            attachments = restoreAttachments()
        )
    )
    val state = _state.asStateFlow()

    fun load(receiptId: String) {
        if (_state.value.snapshot?.receipt?.id == receiptId && !_state.value.isLoading) return
        viewModelScope.launch {
            val receipt = repository.observeReceipts().firstOrNull { it.id == receiptId }
            val date = _state.value.selectedDate ?: receipt?.purchaseDate
            val reports = repository.observeDailyReports().first()
            val reportId = reports.firstOrNull { it.reportDate == date }?.id
            val snapshot = repository.getFixedCostDetail(receiptId, reportId, _state.value.selectedType)
            _state.value = _state.value.copy(
                snapshot = snapshot,
                selectedDate = date,
                availableReportDates = reports.map { it.reportDate }.distinct().sortedDescending(),
                isLoading = false,
                message = null
            )
        }
    }

    fun selectType(type: String) = reload(type = type)
    fun selectDate(date: String) = reload(date = date)

    private fun reload(type: String = _state.value.selectedType, date: String? = _state.value.selectedDate) {
        val receiptId = _state.value.snapshot?.receipt?.id ?: return
        _state.value = _state.value.copy(selectedType = type, selectedDate = date, isLoading = true)
        viewModelScope.launch {
            val reportId = repository.observeDailyReports().first().firstOrNull { it.reportDate == date }?.id
            val snapshot = repository.getFixedCostDetail(receiptId, reportId, type)
            _state.value = _state.value.copy(snapshot = snapshot, selectedDate = date, isLoading = false)
        }
    }

    fun addUris(resolver: ContentResolver, uris: List<Uri>) {
        val existing = _state.value.attachments.map { it.uri }.toSet()
        val additions = uris.distinctBy { it.toString() }.filterNot { it.toString() in existing }.mapNotNull { uri ->
            val mime = resolver.getType(uri)?.substringBefore(';')?.lowercase()
            if (mime !in setOf("image/jpeg", "image/png", "application/pdf")) return@mapNotNull null
            val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) {
                    val name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                    FixedCostEvidenceAttachment(uri.toString(), name, requireNotNull(mime), if (sizeIndex >= 0 && !c.isNull(sizeIndex)) c.getLong(sizeIndex) else -1L, 0)
                } else null } ?: return@mapNotNull null
            val persistable = runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (persistable) ownedPersistableUriGrants += uri.toString()
            if (!persistable && resolver.openInputStream(uri)?.use { true } != true) return@mapNotNull null
            size
        }
        val next = normalizeFixedCostEvidenceAttachments(_state.value.attachments + additions)
        savedStateHandle[AttachmentsKey] = ArrayList(next.map { encode(it) })
        _state.value = _state.value.copy(attachments = next, message = if (additions.size < uris.distinct().size) "利用できない形式・URIを除外しました。現在は読めますが、アプリ終了後は再選択が必要な場合があります。" else null)
    }

    fun remove(index: Int, resolver: ContentResolver) {
        val item = _state.value.attachments.getOrNull(index) ?: return
        releaseOwnedGrant(resolver, item.uri)
        updateAttachments(_state.value.attachments.filterIndexed { i, _ -> i != index })
    }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (index !in _state.value.attachments.indices || target !in _state.value.attachments.indices) return
        val list = _state.value.attachments.toMutableList().apply { add(target, removeAt(index)) }
        updateAttachments(list)
    }

    private fun updateAttachments(list: List<FixedCostEvidenceAttachment>) {
        val normalized = normalizeFixedCostEvidenceAttachments(list)
        savedStateHandle[AttachmentsKey] = ArrayList(normalized.map { encode(it) })
        _state.value = _state.value.copy(attachments = normalized)
    }

    fun save(resolver: ContentResolver) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val receipt = snapshot.receipt ?: return
        val report = snapshot.dailyReport ?: return
        if (current.isSaving || current.attachments.isEmpty() || snapshot.existingAmountState == ExistingAmountState.CONFLICT) return
        _state.value = current.copy(isSaving = true, result = null, message = null)
        viewModelScope.launch {
            val result = coordinator.saveResult(
                resolver,
                FixedCostEvidenceSaveRequest(
                    receiptId = receipt.id,
                    dailyReportId = report.id,
                    fixedCostType = current.selectedType,
                    paymentMethod = com.warun.accounting.data.fixedcost.fixedCostPaymentMethodOrNull(current.selectedType).orEmpty(),
                    appliedAmount = receipt.totalAmount,
                    sources = current.attachments.map { FixedCostEvidenceSource(Uri.parse(it.uri), it.mediaType, it.sortOrder) }
                )
            )
            if (result == FixedCostSaveResult.Success) {
                current.attachments.forEach { attachment ->
                    releaseOwnedGrant(resolver, attachment.uri)
                }
            }
            _state.value = _state.value.copy(isSaving = false, result = result, message = resultMessage(result))
        }
    }

    fun cancel(resolver: ContentResolver) {
        _state.value.attachments.forEach { attachment ->
            releaseOwnedGrant(resolver, attachment.uri)
        }
        savedStateHandle[AttachmentsKey] = ArrayList<String>()
        _state.value = _state.value.copy(attachments = emptyList())
    }

    private fun releaseOwnedGrant(resolver: ContentResolver, uri: String) {
        if (ownedPersistableUriGrants.remove(uri)) {
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun restoreAttachments(): List<FixedCostEvidenceAttachment> =
        savedStateHandle.get<ArrayList<String>>(AttachmentsKey).orEmpty().mapNotNull(::decode)

    private fun encode(item: FixedCostEvidenceAttachment) = listOf(item.uri, item.displayName, item.mediaType, item.byteSize.toString(), item.sortOrder.toString()).joinToString("\u001f")
    private fun decode(value: String): FixedCostEvidenceAttachment? = value.split("\u001f").takeIf { it.size == 5 }?.let { p -> FixedCostEvidenceAttachment(p[0], p[1], p[2], p[3].toLongOrNull() ?: -1L, p[4].toIntOrNull() ?: 0) }

    companion object { private const val AttachmentsKey = "fixed-cost-evidence-attachments" }
}

private fun resultMessage(result: FixedCostSaveResult): String = when (result) {
    FixedCostSaveResult.Success -> "日報へ反映し、証憑を保存しました。"
    FixedCostSaveResult.MissingDailyReport -> "対象日報がありません。先に日報を作成してください。"
    FixedCostSaveResult.MissingEvidence -> "Evidenceを1件以上添付してください。"
    FixedCostSaveResult.AlreadyApplied -> "このレシートは既に固定費へ反映されています。"
    FixedCostSaveResult.AmountConflict -> "日報の固定費金額とレシート金額が異なるため反映できません。"
    FixedCostSaveResult.ReceiptNotFound -> "レシートが見つかりません。"
    FixedCostSaveResult.ReceiptAlreadyConfirmed -> "このレシートは確認済みです。"
    FixedCostSaveResult.InvalidFixedCostType -> "固定費種別を確認してください。"
    is FixedCostSaveResult.SaveFailure -> "証憑を保存できませんでした。添付を確認して再試行してください。"
    is FixedCostSaveResult.RecoveryRequired -> "保存途中の状態を復旧する必要があります。"
}

@Composable
fun FixedCostEvidenceScreen(
    receiptId: String,
    onBack: () -> Unit,
    onOpenReport: (String) -> Unit,
    viewModel: FixedCostEvidenceViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showConfirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addUris(context.contentResolver, uris)
    }
    LaunchedEffect(receiptId) { viewModel.load(receiptId) }
    BackHandler(enabled = !state.isSaving) {
        viewModel.cancel(context.contentResolver)
        onBack()
    }
    LaunchedEffect(state.result) {
        if (state.result == FixedCostSaveResult.Success) state.snapshot?.dailyReport?.reportDate?.let(onOpenReport)
    }
    if (state.isLoading) { CircularProgressIndicator(modifier = Modifier.testTag("fixed-cost-loading")); return }
    val snapshot = state.snapshot
    val receipt = snapshot?.receipt
    if (snapshot == null || receipt == null) { Text("レシートが見つかりません。", modifier = Modifier.padding(16.dp).testTag("fixed-cost-not-found")); return }
    val canSave = fixedCostEvidenceCanSave(snapshot, state.attachments, state.isSaving)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp).testTag("fixed-cost-screen")) {
        item {
            Text("固定費Evidence確認", style = MaterialTheme.typography.headlineSmall)
            Text("このレシートを固定費として日報へ反映します。")
            Text("支払先：${receipt.storeName ?: "支払先未設定"}")
            Text("購入日：${receipt.purchaseDate ?: "日付未設定"}")
            Text("金額：${receipt.totalAmount.toYen()}")
            Text("確認状態：${if (receipt.isConfirmed) "確認済み" else "確認待ち"}")
            Text("対象日報：${snapshot.dailyReport?.reportDate ?: "対象日報がありません"}", modifier = Modifier.testTag("fixed-cost-report-date"))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("fixed-cost-type")) {
                listOf("electricity" to "電気代", "water" to "水道代", "communication" to "通信費", "gas" to "ガス代").forEach { (key, label) ->
                    OutlinedButton(onClick = { viewModel.selectType(key) }, enabled = !state.isSaving) { Text(if (state.selectedType == key) "✓$label" else label) }
                }
            }
            Text("支払方法：${com.warun.accounting.data.fixedcost.fixedCostPaymentMethodOrNull(state.selectedType)}（変更不可）")
            if (state.availableReportDates.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!state.isSaving) expanded = !expanded }) {
                    OutlinedButton(onClick = { expanded = true }, enabled = !state.isSaving, modifier = Modifier.menuAnchor()) {
                        Text("対象日報：${state.selectedDate ?: "選択してください"}")
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                    }
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        state.availableReportDates.forEach { date ->
                            DropdownMenuItem(text = { Text(date) }, onClick = { expanded = false; viewModel.selectDate(date) })
                        }
                    }
                }
            }
        }
        item {
            Text("既存金額：${snapshot.currentAmount?.toYen() ?: "対象日報なし"}", modifier = Modifier.testTag("fixed-cost-existing-amount"))
            Text(existingAmountMessage(snapshot, receipt.totalAmount), modifier = Modifier.testTag(if (snapshot.existingAmountState == ExistingAmountState.CONFLICT) "fixed-cost-conflict" else "fixed-cost-amount-state"), color = if (snapshot.existingAmountState == ExistingAmountState.CONFLICT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        item {
            Button(onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) }, enabled = !state.isSaving, modifier = Modifier.testTag("fixed-cost-evidence-add")) { Text("Evidenceを選択") }
        }
        items(state.attachments) { attachment ->
            Card(modifier = Modifier.testTag("fixed-cost-attachments")) { Column(Modifier.padding(10.dp)) {
                Text("${attachment.sortOrder + 1}. ${attachment.displayName}")
                Text("${attachment.mediaType} / ${if (attachment.byteSize >= 0) "${attachment.byteSize} bytes" else "サイズ不明"}")
                if (attachment.byteSize <= 0L) Text("サイズを取得できません", color = MaterialTheme.colorScheme.error)
                if (attachment.byteSize > FixedCostEvidenceFileStore.MaxBytes) Text("50 MiB超過", color = MaterialTheme.colorScheme.error)
                if (attachment.mediaType.startsWith("image/") || attachment.mediaType == "application/pdf") EvidencePreview(Uri.parse(attachment.uri), attachment.mediaType)
                Row { TextButton(onClick = { viewModel.move(attachment.sortOrder, -1) }, enabled = !state.isSaving, modifier = Modifier.testTag("fixed-cost-move-up-${attachment.sortOrder}")) { Text("上へ") }; TextButton(onClick = { viewModel.move(attachment.sortOrder, 1) }, enabled = !state.isSaving, modifier = Modifier.testTag("fixed-cost-move-down-${attachment.sortOrder}")) { Text("下へ") }; TextButton(onClick = { viewModel.remove(attachment.sortOrder, context.contentResolver) }, enabled = !state.isSaving, modifier = Modifier.testTag("fixed-cost-remove-${attachment.sortOrder}")) { Text("削除") } }
            } }
        }
        item {
            state.message?.let { Text(it, modifier = Modifier.testTag("fixed-cost-result"), color = if (state.result is FixedCostSaveResult.AmountConflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = { showConfirm = true }, enabled = canSave, modifier = Modifier.fillMaxWidth().testTag("fixed-cost-save")) { Text(if (state.isSaving) "保存中…" else "確認して日報へ反映") }
            OutlinedButton(onClick = { viewModel.cancel(context.contentResolver); onBack() }, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) { Text("戻る") }
        }
    }
    if (showConfirm) AlertDialog(
        onDismissRequest = { if (!state.isSaving) showConfirm = false },
        title = { Text("固定費を反映しますか") },
        text = { Text("支払先：${receipt.storeName ?: "未設定"}\n金額：${receipt.totalAmount.toYen()}\n固定費：${state.selectedType}\nEvidence：${state.attachments.size}件") },
        confirmButton = { Button(onClick = { showConfirm = false; viewModel.save(context.contentResolver) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("キャンセル") } }
    )
}

private fun existingAmountMessage(snapshot: FixedCostDetailSnapshot, amount: Long): String = when (snapshot.existingAmountState) {
    ExistingAmountState.EMPTY -> "日報へ${amount.toYen()}を反映します"
    ExistingAmountState.SAME -> "同額が入力済みです。金額を変更せず証憑だけ保存します"
    ExistingAmountState.CONFLICT -> "日報には${snapshot.currentAmount?.toYen()}が入力済みです。レシート金額${amount.toYen()}とは異なるため反映できません"
    null -> "対象日報がありません"
}

@Composable
private fun EvidencePreview(uri: Uri, mediaType: String) {
    val context = LocalContext.current
    if (mediaType.startsWith("image/")) {
        var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(uri) {
            bitmap = withContext(Dispatchers.IO) { decodeThumbnail(context.contentResolver, uri) }
        }
        AndroidView(factory = { ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_INSIDE } }, update = { view -> view.setImageBitmap(bitmap) }, modifier = Modifier.size(120.dp))
    }
    else AndroidView(factory = { ImageView(context) }, update = { view ->
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor -> PdfRenderer(descriptor).use { renderer -> if (renderer.pageCount > 0) renderer.openPage(0).use { page -> val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); view.setImageBitmap(bitmap) } } } }
    }, modifier = Modifier.size(120.dp))
}

private fun decodeThumbnail(resolver: ContentResolver, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 480)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<List<T>>.firstOrNull(predicate: (T) -> Boolean): T? = first().firstOrNull(predicate)
