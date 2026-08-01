@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.warun.accounting.ui.prepaid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.PrepaidChargeSource
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import com.warun.accounting.ui.input.DateInputTextField
import com.warun.accounting.ui.util.toYen
import com.warun.accounting.ui.viewmodel.PrepaidAdjustmentDirection
import com.warun.accounting.ui.viewmodel.PrepaidFormState
import com.warun.accounting.ui.viewmodel.PrepaidInputMode
import com.warun.accounting.ui.viewmodel.PrepaidLedgerUiState
import com.warun.accounting.ui.viewmodel.PrepaidViewModel

class PrepaidNavigationGuard {
    var isActive by mutableStateOf(false)
    var hasUnsavedChanges by mutableStateOf(false)
    var discardChanges: (() -> Unit)? = null

    fun reset() {
        isActive = false
        hasUnsavedChanges = false
        discardChanges = null
    }
}

data class PrepaidHistoryRow(
    val transaction: PrepaidTransactionRecord,
    val balanceAfter: Long,
    val isReversed: Boolean
)

fun buildPrepaidHistoryRows(
    transactions: List<PrepaidTransactionRecord>
): List<PrepaidHistoryRow> {
    val chronological = transactions.sortedWith(
        compareBy<PrepaidTransactionRecord> { it.transactionDate }
            .thenBy { it.createdAt }
            .thenBy { it.id }
    )
    val reversals = transactions.mapNotNull { it.reversalOfTransactionId }.toSet()
    var balance = 0L
    val balances = chronological.associate { transaction ->
        balance = Math.addExact(balance, transaction.balanceDelta)
        transaction.id to balance
    }
    return chronological
        .asReversed()
        .map { transaction ->
            PrepaidHistoryRow(
                transaction = transaction,
                balanceAfter = balances.getValue(transaction.id),
                isReversed = transaction.id in reversals
            )
        }
}

@Composable
fun PrepaidManagementScreen(
    viewModel: PrepaidViewModel,
    navigationGuard: PrepaidNavigationGuard,
    onRequestBack: () -> Unit
) {
    val ledger by viewModel.ledger.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(form.hasUnsavedChanges) {
        navigationGuard.isActive = true
        navigationGuard.hasUnsavedChanges = form.hasUnsavedChanges
        navigationGuard.discardChanges = viewModel::discardInput
        onDispose { navigationGuard.reset() }
    }
    BackHandler(onBack = onRequestBack)

    LaunchedEffect(feedback) {
        feedback?.let {
            snackbarHostState.showSnackbar(it.message)
            viewModel.consumeFeedback()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "プリペイド管理",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "口座を初期残高0円で追加し、残高はチャージと不変台帳で管理します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AccountList(
                ledger = ledger,
                selectedAccountId = form.accountId,
                onSelect = viewModel::selectAccount,
                onAdd = viewModel::beginAccountCreation
            )
            ActionSelector(
                mode = form.inputMode,
                onHistory = viewModel::showHistory,
                onCharge = viewModel::showCharge,
                onAdjustment = viewModel::showAdjustment
            )
            when (form.inputMode) {
                PrepaidInputMode.Charge -> ChargeForm(
                    form = form,
                    isSaving = isSaving,
                    viewModel = viewModel
                )
                PrepaidInputMode.Adjustment -> AdjustmentForm(
                    form = form,
                    balance = ledger.balances[form.accountId] ?: 0L,
                    isSaving = isSaving,
                    viewModel = viewModel
                )
                else -> AccountHistory(
                    account = ledger.accounts.firstOrNull { it.id == form.accountId },
                    transactions = ledger.transactions.filter { it.accountId == form.accountId },
                    onReverse = viewModel::beginReversal
                )
            }
            Spacer(Modifier.height(72.dp))
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    form.pendingReversalTargetId?.let { targetId ->
        val target = ledger.transactions.firstOrNull { it.id == targetId }
        AlertDialog(
            onDismissRequest = viewModel::cancelReversal,
            title = { Text("取引を取消しますか") },
            text = {
                Text(
                    if (target == null) {
                        "対象取引を確認できません。"
                    } else {
                        "${target.transactionDate} ${target.balanceDelta.toYen()} の取引を、削除せず取消仕訳で相殺します。"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmReversal,
                    enabled = target != null && !isSaving
                ) {
                    Text(if (isSaving) "処理中…" else "取消を記録")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelReversal, enabled = !isSaving) {
                    Text("戻る")
                }
            }
        )
    }

    if (form.isAccountCreationOpen) {
        AlertDialog(
            onDismissRequest = viewModel::cancelAccountCreation,
            title = { Text("プリペイド口座を追加") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = form.newAccountName,
                        onValueChange = viewModel::setNewAccountName,
                        label = { Text("口座名（必須）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        "初期残高は0円です。残高は口座追加後にチャージから登録してください。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::saveAccount,
                    enabled = form.newAccountName.isNotBlank() && !isSaving
                ) {
                    Text(if (isSaving) "追加中…" else "追加")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::cancelAccountCreation,
                    enabled = !isSaving
                ) {
                    Text("キャンセル")
                }
            }
        )
    }
}

@Composable
private fun AccountList(
    ledger: PrepaidLedgerUiState,
    selectedAccountId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("口座", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ledger.accounts.forEach { account ->
            val lastDate = ledger.transactions
                .firstOrNull { it.accountId == account.id }
                ?.transactionDate
                ?: "取引なし"
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(account.id) }
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(account.name, fontWeight = FontWeight.Bold)
                        Text(if (account.isActive) "有効" else "無効")
                    }
                    Text(
                        "残高 ${(ledger.balances[account.id] ?: 0L).toYen()}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text("最終取引日 $lastDate")
                    if (account.id == selectedAccountId) {
                        Text("選択中", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("新しい口座を追加")
        }
    }
}

@Composable
private fun ActionSelector(
    mode: String,
    onHistory: () -> Unit,
    onCharge: () -> Unit,
    onAdjustment: () -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModeButton("履歴", mode == PrepaidInputMode.History, onHistory)
        ModeButton("チャージ", mode == PrepaidInputMode.Charge, onCharge)
        ModeButton("残高調整", mode == PrepaidInputMode.Adjustment, onAdjustment)
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun ChargeForm(
    form: PrepaidFormState,
    isSaving: Boolean,
    viewModel: PrepaidViewModel
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("チャージ登録", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            DateInputTextField(
                label = "日付",
                value = form.transactionDate,
                modifier = Modifier.fillMaxWidth(),
                onValueChange = viewModel::setDate
            )
            MoneyField("金額", form.amount, viewModel::setAmount)
            Text("チャージ元", fontWeight = FontWeight.Bold)
            if (form.accountId == PrepaidAccountId.Majica) {
                Text("現金（majicaは現金チャージのみ）")
            } else {
                ChargeSourceSelector(form.chargeSource, viewModel::setChargeSource)
            }
            OutlinedTextField(
                value = form.memo,
                onValueChange = viewModel::setMemo,
                label = { Text("メモ（任意）") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = viewModel::saveCharge,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSaving) "保存中…" else "チャージを保存")
            }
            OutlinedButton(
                onClick = viewModel::discardInput,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("入力を破棄")
            }
        }
    }
}

@Composable
private fun ChargeSourceSelector(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        PrepaidChargeSource.Cash to "現金",
        PrepaidChargeSource.BankAccount to "銀行口座",
        PrepaidChargeSource.CreditCard to "クレジットカード",
        PrepaidChargeSource.OtherNonCash to "その他の非現金"
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun AdjustmentForm(
    form: PrepaidFormState,
    balance: Long,
    isSaving: Boolean,
    viewModel: PrepaidViewModel
) {
    val amount = form.amount.filter { it.isDigit() }.toLongOrNull() ?: 0L
    val insufficient = form.adjustmentDirection == PrepaidAdjustmentDirection.Decrease &&
        amount > balance
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("残高調整", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("現在残高 ${balance.toYen()}")
            DateInputTextField(
                label = "日付",
                value = form.transactionDate,
                modifier = Modifier.fillMaxWidth(),
                onValueChange = viewModel::setDate
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = form.adjustmentDirection == PrepaidAdjustmentDirection.Increase,
                    onClick = { viewModel.setAdjustmentDirection(PrepaidAdjustmentDirection.Increase) },
                    label = { Text("増額") }
                )
                FilterChip(
                    selected = form.adjustmentDirection == PrepaidAdjustmentDirection.Decrease,
                    onClick = { viewModel.setAdjustmentDirection(PrepaidAdjustmentDirection.Decrease) },
                    label = { Text("減額") }
                )
            }
            MoneyField("調整額（正数）", form.amount, viewModel::setAmount)
            OutlinedTextField(
                value = form.memo,
                onValueChange = viewModel::setMemo,
                label = { Text("調整理由（必須）") },
                modifier = Modifier.fillMaxWidth(),
                isError = form.memo.isBlank() && form.amount.isNotBlank()
            )
            if (insufficient) {
                Text(
                    "調整後の残高が0円未満になるため保存できません。",
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = viewModel::saveAdjustment,
                enabled = !isSaving && !insufficient,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSaving) "保存中…" else "調整を保存")
            }
            OutlinedButton(
                onClick = viewModel::discardInput,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("入力を破棄")
            }
        }
    }
}

@Composable
private fun MoneyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun AccountHistory(
    account: PrepaidAccountRecord?,
    transactions: List<PrepaidTransactionRecord>,
    onReverse: (String) -> Unit
) {
    val rows = remember(transactions) { buildPrepaidHistoryRows(transactions) }
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "${account?.name ?: "口座"}の履歴",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (rows.isEmpty()) {
                Text("取引はまだありません。")
            }
            rows.forEach { row ->
                val transaction = row.transaction
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${transaction.transactionDate} ${transactionTypeLabel(transaction)}")
                        Text(transaction.balanceDelta.toYen(), fontWeight = FontWeight.Bold)
                    }
                    Text("取引後残高 ${row.balanceAfter.toYen()}")
                    transaction.chargeSource?.let { Text("チャージ元 ${chargeSourceLabel(it)}") }
                    if (transaction.memo.isNotBlank()) Text(transaction.memo)
                    if (row.isReversed) {
                        Text("取消済み", color = MaterialTheme.colorScheme.error)
                    } else if (
                        transaction.transactionType in setOf(
                            PrepaidTransactionType.Charge,
                            PrepaidTransactionType.Adjustment
                        )
                    ) {
                        TextButton(onClick = { onReverse(transaction.id) }) {
                            Text("この取引を取消")
                        }
                    }
                }
            }
        }
    }
}

private fun transactionTypeLabel(transaction: PrepaidTransactionRecord): String = when (
    transaction.transactionType
) {
    PrepaidTransactionType.Charge -> "チャージ"
    PrepaidTransactionType.Adjustment -> "残高調整"
    PrepaidTransactionType.Reversal -> "取消"
    PrepaidTransactionType.Purchase -> "利用"
    PrepaidTransactionType.Refund -> "返金"
    else -> transaction.transactionType
}

private fun chargeSourceLabel(value: String): String = when (value) {
    PrepaidChargeSource.Cash -> "現金"
    PrepaidChargeSource.BankAccount -> "銀行口座"
    PrepaidChargeSource.CreditCard -> "クレジットカード"
    PrepaidChargeSource.OtherNonCash -> "その他の非現金"
    else -> value
}
