package com.warun.accounting.ui.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warun.accounting.MainActivity
import com.warun.accounting.backup.BackupContract
import com.warun.accounting.ui.viewmodel.BackupRestoreAction
import com.warun.accounting.ui.viewmodel.BackupRestoreViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun BackupRestoreSection(
    viewModel: BackupRestoreViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupContract.MimeType)
    ) { uri ->
        uri?.let(viewModel::createBackup)
    }
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::stageRestore)
    }

    LaunchedEffect(state.restartRequired) {
        if (state.restartRequired) {
            delay(500L)
            context.findMainActivity().restartAfterRestore()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "バックアップ／復元",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "RoomデータとDBが参照する正式Evidenceを1ファイルへ保存します。" +
                    "一時画像、OCR途中状態、cacheは含みません。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { createDocument.launch(viewModel.suggestedFileName()) },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("バックアップを作成")
            }
            OutlinedButton(
                onClick = {
                    openDocument.launch(
                        arrayOf(
                            BackupContract.MimeType,
                            "application/zip",
                            "application/x-zip-compressed"
                        )
                    )
                },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("バックアップから復元")
            }
            if (state.isBusy) {
                CircularProgressIndicator()
                Text(
                    when (state.action) {
                        BackupRestoreAction.Backup -> "完全性を確認してバックアップを作成中…"
                        BackupRestoreAction.ValidateRestore -> "本番データを変更せず検証中…"
                        BackupRestoreAction.Restore -> "rollback用退避後に復元中…"
                        null -> "処理中…"
                    }
                )
            }
            Text(
                "checksumは破損検出用です。暗号化されたファイルではありません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    state.restorePreview?.takeIf { !state.isBusy }?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRestorePreview,
            title = { Text("現在のデータを置き換えますか？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("バックアップ作成: ${formatBackupTime(preview.createdAtEpochMillis)}")
                    Text("app: ${preview.appVersion} / Room schema: v${preview.roomSchemaVersion}")
                    Text("日報: ${preview.summary.dailyReportCount}件")
                    Text("支出: ${preview.summary.expenseCount}件")
                    Text("Evidence: ${preview.summary.evidenceCount}件")
                    Text("取消: ${preview.summary.cancellationCount}件")
                    Text("プリペイド台帳: ${preview.summary.prepaidTransactionCount}件")
                    Text(
                        "復元すると現在のRoomデータと正式Evidenceを、この検証済み内容へ置き換えます。" +
                            "開始前に現在状態をrollback用に退避します。",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                OutlinedButton(onClick = viewModel::confirmRestore) {
                    Text("置き換えて復元")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestorePreview) { Text("キャンセル") }
            }
        )
    }

    state.message?.takeIf { !state.restartRequired }?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text(if (state.isError) "処理を完了できませんでした" else "完了") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text("確認") }
            }
        )
    }
}

private fun formatBackupTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

private tailrec fun Context.findMainActivity(): MainActivity = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> error("MainActivity is unavailable")
}
