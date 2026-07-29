package com.warun.accounting.ui.cancellation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.warun.accounting.ui.util.toYen
import com.warun.accounting.ui.viewmodel.CancellationUiFailure
import com.warun.accounting.ui.viewmodel.CancellationUiState
import com.warun.accounting.ui.viewmodel.ExpenseCancellationEvent
import com.warun.accounting.ui.viewmodel.ExpenseCancellationReasonMaxLength

data class ExpenseCancellationDialogSummary(
    val supplierName: String,
    val expenseDate: String,
    val amount: Long,
    val prepaidAccountName: String,
    val evidenceCount: Int
)

sealed interface ExpenseCancellationUiAction {
    data class ShowSuccess(val expenseId: String) : ExpenseCancellationUiAction
    data object Reload : ExpenseCancellationUiAction
    data class OpenAudit(val expenseId: String) : ExpenseCancellationUiAction
}

enum class SavedExpenseSecondaryAction {
    Delete,
    Cancel
}

data class CancellationDialogPolicy(
    val dismissEnabled: Boolean,
    val confirmEnabled: Boolean,
    val confirmLabel: String
)

internal fun savedExpenseSecondaryAction(isPrepaidExpense: Boolean):
    SavedExpenseSecondaryAction =
    if (isPrepaidExpense) SavedExpenseSecondaryAction.Cancel
    else SavedExpenseSecondaryAction.Delete

internal fun ExpenseCancellationEvent.toUiAction(): ExpenseCancellationUiAction = when (this) {
    is ExpenseCancellationEvent.Success ->
        ExpenseCancellationUiAction.ShowSuccess(result.expenseId)
    ExpenseCancellationEvent.ReloadRequired -> ExpenseCancellationUiAction.Reload
    is ExpenseCancellationEvent.OpenAudit ->
        ExpenseCancellationUiAction.OpenAudit(expenseId)
}

internal fun cancellationFailureMessage(failure: CancellationUiFailure): String = when (failure) {
    CancellationUiFailure.InvalidRequest ->
        "入力内容を確認してください。"
    CancellationUiFailure.Conflict ->
        "操作内容が一致しません。画面を更新して、もう一度やり直してください。"
    CancellationUiFailure.AlreadyCancelled ->
        "この支出はすでに取消済みです。"
    CancellationUiFailure.StaleState ->
        "支出内容が変更されています。画面を更新して、もう一度やり直してください。"
    CancellationUiFailure.PrepaidStateInconsistent ->
        "プリペイド履歴の整合性を確認できないため、取消できません。"
    CancellationUiFailure.DatabaseFailure ->
        "保存できませんでした。端末への保存処理をもう一度お試しください。"
    CancellationUiFailure.ExpenseNotFound ->
        "対象の支出が見つかりません。"
    CancellationUiFailure.CancellationStateCorrupted ->
        "取消履歴の整合性を確認できません。自動修復は行いません。"
    CancellationUiFailure.UnexpectedFailure ->
        "取消処理中に予期しない問題が発生しました。"
}

internal fun cancellationReasonRemaining(reason: String): Int =
    ExpenseCancellationReasonMaxLength - reason.length

internal fun cancellationDialogPolicy(state: CancellationUiState): CancellationDialogPolicy {
    val reasonTooLong = cancellationReasonRemaining(state.reason) < 0
    return CancellationDialogPolicy(
        dismissEnabled = !state.isSaving,
        confirmEnabled = !state.isSaving && !reasonTooLong,
        confirmLabel = when {
            state.isSaving -> "取消処理中…"
            state.failure == CancellationUiFailure.DatabaseFailure -> "もう一度試す"
            else -> "取消を確定"
        }
    )
}

@Composable
fun ExpenseCancellationDialog(
    state: CancellationUiState,
    summary: ExpenseCancellationDialogSummary,
    onReasonChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val remaining = cancellationReasonRemaining(state.reason)
    val reasonTooLong = remaining < 0
    val policy = cancellationDialogPolicy(state)

    Dialog(
        onDismissRequest = {
            if (policy.dismissEnabled) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = policy.dismissEnabled,
            dismissOnClickOutside = policy.dismissEnabled,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .imePadding()
                .testTag("expense-cancellation-dialog")
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    "プリペイド支出を取り消しますか？",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "この支出は削除されません。\n" +
                        "支出、プリペイド履歴、レシートは取消済みの記録として保持されます。\n" +
                        "プリペイド残高は取消額分だけ戻ります。"
                )
                CancellationSummaryRow("支払先", summary.supplierName.ifBlank { "支払先未入力" })
                CancellationSummaryRow("支出日", summary.expenseDate)
                CancellationSummaryRow("金額", summary.amount.toYen())
                CancellationSummaryRow("プリペイド口座", summary.prepaidAccountName)
                CancellationSummaryRow("取消日", state.cancellationDate)
                CancellationSummaryRow("保存済みレシート", "${summary.evidenceCount}件（削除されません）")
                OutlinedTextField(
                    value = state.reason,
                    onValueChange = onReasonChanged,
                    enabled = !state.isSaving,
                    label = { Text("取消理由（任意）") },
                    supportingText = {
                        Text(
                            if (reasonTooLong) {
                                "${-remaining}文字超過しています"
                            } else {
                                "残り${remaining}文字"
                            }
                        )
                    },
                    isError = reasonTooLong,
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("expense-cancellation-reason")
                )
                state.failure?.let { failure ->
                    Text(
                        cancellationFailureMessage(failure),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("expense-cancellation-failure")
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = policy.dismissEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("キャンセル")
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = policy.confirmEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("expense-cancellation-confirm")
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        Text(policy.confirmLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun CancellationSummaryRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value)
    }
}
