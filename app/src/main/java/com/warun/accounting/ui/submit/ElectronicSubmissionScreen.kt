package com.warun.accounting.ui.submit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warun.accounting.data.local.ElectronicSubmissionRecord
import com.warun.accounting.data.local.ElectronicSubmissionStatus
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.MonthlySubmissionStatus
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.export.MonthlyExportShareGateway
import com.warun.accounting.export.ExportCacheContract
import com.warun.accounting.ui.viewmodel.ElectronicSubmissionUiEffect
import com.warun.accounting.ui.viewmodel.ElectronicSubmissionViewModel
import com.warun.accounting.ui.viewmodel.SubmissionArtifact
import com.warun.accounting.ui.unconfirmedReceiptCounts
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ElectronicSubmissionScreen(
    receipts: List<ReceiptRecord> = emptyList(),
    onOpenUnconfirmedReceipts: (String) -> Unit = {},
    viewModel: ElectronicSubmissionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingSave by remember { mutableStateOf<SubmissionArtifact?>(null) }
    var confirmSubmitted by remember { mutableStateOf<ElectronicSubmissionRecord?>(null) }
    var showUnconfirmedWarning by remember { mutableStateOf(false) }
    val xlsxSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ElectronicSubmissionViewModel.XlsxMimeType)
    ) { destination ->
        val artifact = pendingSave
        pendingSave = null
        if (destination != null && artifact != null) viewModel.save(artifact, destination)
    }
    val pdfSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ElectronicSubmissionViewModel.PdfMimeType)
    ) { destination ->
        val artifact = pendingSave
        pendingSave = null
        if (destination != null && artifact != null) viewModel.save(artifact, destination)
    }

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ElectronicSubmissionUiEffect.ChooseSaveDestination -> {
                    pendingSave = effect.artifact
                    if (effect.artifact.mimeType == ElectronicSubmissionViewModel.PdfMimeType) {
                        pdfSaver.launch(effect.artifact.fileName)
                    } else {
                        xlsxSaver.launch(effect.artifact.fileName)
                    }
                }
                is ElectronicSubmissionUiEffect.ShareFiles -> {
                    val gateway = MonthlyExportShareGateway(context.applicationContext)
                    val shareIntent = gateway.createShareIntent(
                        files = effect.files,
                        targetMonth = effect.targetMonth,
                        storeName = effect.storeName
                    )
                    context.startActivity(
                        Intent.createChooser(
                            shareIntent,
                            "提出ファイルを共有"
                        )
                    )
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "税理士向け電子提出",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "アプリは提出ファイルの作成・保存・共有だけを行います。" +
                    "MyKomonへの自動送信は行いません。対応アプリがある場合だけ共有先として優先表示します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            MonthSelector(
                month = state.selectedMonth,
                enabled = state.canGenerate,
                onPrevious = { viewModel.selectMonth(state.selectedMonth.minusMonths(1)) },
                onNext = { viewModel.selectMonth(state.selectedMonth.plusMonths(1)) },
                onCurrent = { viewModel.selectMonth(YearMonth.now()) }
            )
        }
        item {
            SubmissionContentsCard(state.activeGeneration?.hasReceiptPdf)
        }
        item {
            Button(
                onClick = {
                    val counts = unconfirmedReceiptCounts(receipts, state.selectedMonth)
                    if (counts.datedInMonth > 0 || counts.undatedAllPeriod > 0) showUnconfirmedWarning = true
                    else viewModel.generate()
                },
                enabled = state.canGenerate,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Text("  作成中…")
                } else {
                    Text("提出ファイルを作成")
                }
            }
        }
        state.activeGeneration?.let { active ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("提出ファイルを端末に保存", fontWeight = FontWeight.Bold)
                        SubmissionSizeSummary(active.artifacts.totalBytes)
                        active.files.forEach { artifact ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(artifact.fileName, modifier = Modifier.weight(1f))
                                OutlinedButton(
                                    onClick = { viewModel.requestSave(artifact.fileName) },
                                    enabled = state.savingFileName == null
                                ) {
                                    Text(if (state.savingFileName == artifact.fileName) "保存中…" else "保存")
                                }
                            }
                        }
                        if (!active.hasReceiptPdf) {
                            Text(
                                "保存済みEvidenceがないため、レシートPDFは未作成です。",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        OutlinedButton(
                            onClick = viewModel::requestShare,
                            enabled = state.savingFileName == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("その他の方法で共有")
                        }
                    }
                }
            }
        }
        item { ManualUploadCard() }
        item {
            Text(
                "電子提出履歴",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        if (state.electronicHistory.isEmpty()) {
            item { Text("電子提出ファイルの作成履歴はありません。") }
        } else {
            itemsIndexed(state.electronicHistory, key = { _, record -> record.id }) { index, record ->
                ElectronicHistoryCard(
                    record = record,
                    note = state.noteDrafts[record.id].orEmpty(),
                    noteSaving = record.id in state.noteSavingIds,
                    submitting = record.id in state.submittingIds,
                    onNoteChange = { viewModel.updateNoteDraft(record.id, it) },
                    onSaveNote = { viewModel.saveNote(record.id) },
                    onConfirmSubmitted = { confirmSubmitted = record },
                    isLastHistoryRecord = index == state.electronicHistory.lastIndex
                )
            }
        }
        item {
            Text(
                "過去の紙提出記録",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        if (state.paperHistory.isEmpty()) {
            item { Text("過去の紙提出記録はありません。") }
        } else {
            items(state.paperHistory, key = { it.targetMonth }) { record ->
                PaperHistoryCard(record)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    confirmSubmitted?.let { record ->
        AlertDialog(
            onDismissRequest = { confirmSubmitted = null },
            title = { Text("MyKomonへの提出完了を記録しますか？") },
            text = {
                Text(
                    "利用者がMyKomonへ手動アップロードしたことを確認してから記録してください。" +
                        "この操作自体はMyKomonへファイルを送信しません。記録後は未提出へ戻せません。"
                )
            },
            confirmButton = {
                OutlinedButton(onClick = {
                    confirmSubmitted = null
                    viewModel.markSubmitted(record.id)
                }) { Text("MyKomonへの提出完了を記録") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSubmitted = null }) { Text("キャンセル") }
            }
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text(if (state.isError) "処理を完了できませんでした" else "確認") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text("閉じる") }
            }
        )
    }

    if (showUnconfirmedWarning) {
        val month = state.selectedMonth.toString()
        val counts = unconfirmedReceiptCounts(receipts, state.selectedMonth)
        val dated = counts.datedInMonth
        val undated = counts.undatedAllPeriod
        AlertDialog(
            onDismissRequest = { showUnconfirmedWarning = false },
            title = { Text("要確認レシートがあります") },
            text = {
                Text(
                    "対象月: $month\n" +
                        "対象月の要確認: ${dated}件\n" +
                        "日付未設定（全期間）: ${undated}件\n\n" +
                        "要確認Receiptは支出明細XLSXとEvidence PDFへ含まれません。提出前に確認することを推奨します。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    showUnconfirmedWarning = false
                    onOpenUnconfirmedReceipts(month)
                }) { Text("レシートを確認する") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showUnconfirmedWarning = false }) { Text("キャンセル") }
                    OutlinedButton(onClick = {
                        showUnconfirmedWarning = false
                        viewModel.generate()
                    }) { Text("今回は除外して作成") }
                }
            }
        )
    }
}

@Composable
private fun MonthSelector(
    month: YearMonth,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCurrent: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("対象月：${month.year}年${month.monthValue}月", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPrevious, enabled = enabled) { Text("前月") }
                OutlinedButton(onClick = onCurrent, enabled = enabled) { Text("今月") }
                OutlinedButton(onClick = onNext, enabled = enabled) { Text("翌月") }
            }
        }
    }
}

@Composable
private fun SubmissionContentsCard(hasReceiptPdf: Boolean?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("作成するファイル", fontWeight = FontWeight.Bold)
            ReadOnlyCheck("日報Excel", checked = true)
            ReadOnlyCheck("支出明細Excel", checked = true)
            ReadOnlyCheck(
                if (hasReceiptPdf == false) "レシートPDF（Evidenceなし・未作成）" else "レシートPDF",
                checked = hasReceiptPdf != false
            )
            ReadOnlyCheck("銀行明細PDF（ネットバンキングからアプリ外で取得）", checked = false)
        }
    }
}

@Composable
private fun ReadOnlyCheck(label: String, checked: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

@Composable
private fun ManualUploadCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("MyKomonへの提出手順", fontWeight = FontWeight.Bold)
            Text("1. 作成した各ファイルを開いて内容を確認する")
            Text("2. 端末へ保存、または共有で取り出す")
            Text("3. 銀行明細PDFをネットバンキングから別途用意する")
            Text("4. 利用者がMyKomonへ手動アップロードする")
            Text("5. 完了後、この画面でMyKomonへの提出完了を記録する")
            Button(
                onClick = { openMyKomon(context) },
                modifier = Modifier.fillMaxWidth().testTag("electronic-submission-open-mykomon")
            ) { Text("MyKomonを開く") }
            Text(
                "公式アプリが起動できない場合は、公式ログインページをブラウザで開きます。",
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                "アプリはMyKomonへのログイン・アップロード・認証情報保存を行いません。",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SubmissionSizeSummary(totalBytes: Long) {
    val label = "提出ファイル合計：${formatBytes(totalBytes)}"
    when {
        totalBytes > ExportCacheContract.MyKomonHardTotalBytesLimit -> {
            Text(
                "$label（MyKomonの100MB上限を超過。共有前に受け渡し方法を確認してください）",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
        totalBytes > ExportCacheContract.MyKomonSoftTotalBytesLimit -> {
            Text(
                "$label（90MBを超えています。MyKomonの100MB上限に注意してください）",
                color = MaterialTheme.colorScheme.error
            )
        }
        else -> Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1_000_000L -> String.format(java.util.Locale.JAPAN, "%.1fMB", value / 1_000_000.0)
    value >= 1_000L -> String.format(java.util.Locale.JAPAN, "%.1fKB", value / 1_000.0)
    else -> "${value}B"
}

private fun openMyKomon(context: Context) {
    val launchIntent = context.packageManager
        .getLaunchIntentForPackage(MonthlyExportShareGateway.OfficialMyKomonPackage)
    if (launchIntent != null) {
        try {
            context.startActivity(launchIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to the official browser login page.
        } catch (_: SecurityException) {
            // Fall through when the installed app does not permit external launches.
        }
    }
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(MonthlyExportShareGateway.MyKomonLoginUrl))
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "ブラウザを起動できませんでした。", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "ブラウザを起動できませんでした。", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ElectronicHistoryCard(
    record: ElectronicSubmissionRecord,
    note: String,
    noteSaving: Boolean,
    submitting: Boolean,
    onNoteChange: (String) -> Unit,
    onSaveNote: () -> Unit,
    onConfirmSubmitted: () -> Unit,
    isLastHistoryRecord: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(formatMonth(record.targetMonth), fontWeight = FontWeight.Bold)
            Text("作成日時：${formatTime(record.generatedAt)}")
            listOfNotNull(
                record.dailyReportFileName,
                record.expenseDetailFileName,
                record.receiptPdfFileName
            ).forEach { Text("・$it") }
            if (record.receiptPdfFileName == null) Text("・レシートPDF：Evidenceなしのため未作成")
            Text(
                if (record.status == ElectronicSubmissionStatus.Submitted) {
                    "手動提出記録済み（MyKomon）：${record.submittedAt?.let(::formatTime) ?: "日時不明"}"
                } else {
                    "手動提出未記録"
                },
                fontWeight = FontWeight.Bold
            )
            Text(
                "履歴にはファイル名だけを保存しています。再起動後に保存・共有する場合は再作成してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = note,
                onValueChange = onNoteChange,
                label = { Text("備考") },
                supportingText = { Text("${note.length}/${ElectronicSubmissionViewModel.NoteMaxLength}") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = onSaveNote,
                enabled = !noteSaving,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (noteSaving) "備考を保存中…" else "備考を保存") }
            if (record.status == ElectronicSubmissionStatus.NotSubmitted) {
                Button(
                    onClick = onConfirmSubmitted,
                    enabled = !submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isLastHistoryRecord) {
                                Modifier.testTag("electronic-submission-last-action")
                            } else {
                                Modifier
                            }
                        )
                ) { Text(if (submitting) "記録中…" else "MyKomonへの提出完了を記録") }
            }
        }
    }
}

@Composable
private fun PaperHistoryCard(record: MonthlySubmission) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(formatMonth(record.targetMonth), fontWeight = FontWeight.Bold)
            Text(
                if (record.status == MonthlySubmissionStatus.Submitted) {
                    "紙提出済み（過去記録）${record.submittedAt?.let { "：${formatTime(it)}" }.orEmpty()}"
                } else {
                    "紙提出未記録（過去データ）"
                }
            )
        }
    }
}

private fun formatMonth(value: String): String = runCatching {
    val month = YearMonth.parse(value)
    "${month.year}年${month.monthValue}月"
}.getOrDefault(value)

private fun formatTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
