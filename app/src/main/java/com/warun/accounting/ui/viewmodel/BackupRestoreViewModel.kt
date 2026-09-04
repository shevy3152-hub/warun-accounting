package com.warun.accounting.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warun.accounting.backup.BackupException
import com.warun.accounting.backup.BackupFailure
import com.warun.accounting.backup.BackupRestoreManager
import com.warun.accounting.backup.BackupStage
import com.warun.accounting.backup.RestoreExecutionOutcome
import com.warun.accounting.backup.RestorePreview
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BackupRestoreAction { Backup, ValidateRestore, Restore }

data class BackupRestoreUiState(
    val action: BackupRestoreAction? = null,
    val restorePreview: RestorePreview? = null,
    val message: String? = null,
    val isError: Boolean = false,
    val restartRequired: Boolean = false
) {
    val isBusy: Boolean get() = action != null
}

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val manager: BackupRestoreManager
) : ViewModel() {
    private val _state = MutableStateFlow(BackupRestoreUiState())
    val state: StateFlow<BackupRestoreUiState> = _state.asStateFlow()

    fun suggestedFileName(): String = manager.suggestedFileName()

    fun createBackup(uri: Uri) = launch(BackupRestoreAction.Backup) {
        val result = manager.createBackup(uri)
        _state.value = BackupRestoreUiState(
            message = "バックアップを作成しました（日報${result.summary.dailyReportCount}件、" +
                "支出${result.summary.expenseCount}件、Evidence${result.summary.evidenceCount}件）。"
        )
    }

    fun stageRestore(uri: Uri) = launch(BackupRestoreAction.ValidateRestore) {
        val preview = manager.stageRestore(uri)
        _state.value = BackupRestoreUiState(restorePreview = preview)
    }

    fun dismissRestorePreview() {
        if (!_state.value.isBusy) _state.value = BackupRestoreUiState()
    }

    fun confirmRestore() {
        val preview = _state.value.restorePreview ?: return
        launch(BackupRestoreAction.Restore, preservePreview = true) {
            val result = manager.restore(preview.token)
            _state.value = BackupRestoreUiState(
                message = if (result.outcome == RestoreExecutionOutcome.Applied) {
                    "復元データを配置しました。安全確認のためアプリを再起動します。"
                } else {
                    "復元に失敗したため元データへ戻しました。アプリを再起動します。"
                },
                isError = result.outcome != RestoreExecutionOutcome.Applied,
                restartRequired = result.restartRequired
            )
        }
    }

    fun dismissMessage() {
        if (!_state.value.restartRequired) _state.value = BackupRestoreUiState()
    }

    private fun launch(
        action: BackupRestoreAction,
        preservePreview: Boolean = false,
        block: suspend () -> Unit
    ) {
        if (_state.value.isBusy) return
        val preview = _state.value.restorePreview.takeIf { preservePreview }
        _state.update { BackupRestoreUiState(action = action, restorePreview = preview) }
        viewModelScope.launch {
            try {
                block()
            } catch (error: BackupException) {
                _state.value = BackupRestoreUiState(
                    message = backupFailureMessage(error.failure, error.stage),
                    isError = true,
                    restartRequired = error.failure == BackupFailure.RollbackFailure
                )
            } catch (_: Exception) {
                _state.value = BackupRestoreUiState(
                    message = "バックアップ処理を完了できませんでした。元データは変更していません。",
                    isError = true
                )
            }
        }
    }
}

internal fun backupFailureMessage(
    failure: BackupFailure,
    stage: BackupStage? = null
): String {
    val stageMessage = when (stage) {
        BackupStage.Build -> "内部バックアップの生成・検証に失敗しました。"
        BackupStage.OutputOpen -> "保存先を開けませんでした。"
        BackupStage.OutputWrite -> "保存先への書き込みに失敗しました。"
        BackupStage.OutputFlush -> "保存先への書き込み確定に失敗しました。"
        BackupStage.OutputClose -> "保存先を閉じる処理に失敗しました。"
        BackupStage.OutputVerify -> "保存後のバックアップ検証に失敗しました。"
        null -> null
    }
    val detail = when (failure) {
    BackupFailure.EvidenceMissing ->
        "DBが参照する正式Evidenceが見つからないため、バックアップを作成しませんでした。"
    BackupFailure.EvidenceMismatch ->
        "EvidenceとDBの内容が一致しないため、安全のため処理を中止しました。"
    BackupFailure.UnsupportedFormat -> "このバックアップ形式には対応していません。"
    BackupFailure.UnsupportedSchema ->
        "このバックアップのRoom schemaには対応していません。v15のファイルを選択してください。"
    BackupFailure.UnsafeArchivePath,
    BackupFailure.DuplicateArchiveEntry,
    BackupFailure.UnexpectedArchiveEntry ->
        "バックアップ内に安全でない、または重複した項目があります。"
    BackupFailure.ChecksumMismatch,
    BackupFailure.SizeMismatch -> "バックアップが破損しているため復元できません。"
    BackupFailure.CorruptArchive,
    BackupFailure.CorruptDatabase,
    BackupFailure.InvalidManifest,
    BackupFailure.MissingArchiveEntry ->
        "バックアップの完全性を確認できないため復元できません。"
    BackupFailure.ArchiveTooLarge -> "バックアップが許容サイズを超えています。"
    BackupFailure.OutputFailure -> "選択した保存先へバックアップを書き込めませんでした。"
    BackupFailure.SnapshotFailure -> "一貫したDB snapshotを作成できませんでした。"
    BackupFailure.RollbackFailure ->
        "復元を完了できませんでした。元データへの復帰を次回起動時に再試行します。"
    BackupFailure.RestoreFailure -> "復元を開始できませんでした。元データは変更していません。"
    BackupFailure.Busy -> "別のバックアップ／復元処理が進行中です。"
    }
    return if (stageMessage == null) detail else "$stageMessage\n$detail"
}
