package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.PrepaidChargeSource
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.prepaid.PrepaidAccountCreateInput
import com.warun.accounting.data.prepaid.PrepaidAdjustmentInput
import com.warun.accounting.data.prepaid.PrepaidChargeInput
import com.warun.accounting.data.prepaid.PrepaidRepository
import com.warun.accounting.data.prepaid.PrepaidReversalInput
import com.warun.accounting.data.prepaid.PrepaidValidationException
import com.warun.accounting.data.prepaid.PrepaidValidationFailure
import com.warun.accounting.ui.util.todayString
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object PrepaidInputMode {
    const val History = "HISTORY"
    const val Charge = "CHARGE"
    const val Adjustment = "ADJUSTMENT"
}

object PrepaidAdjustmentDirection {
    const val Increase = "INCREASE"
    const val Decrease = "DECREASE"
}

data class PrepaidLedgerUiState(
    val accounts: List<PrepaidAccountRecord> = emptyList(),
    val balances: Map<String, Long> = emptyMap(),
    val transactions: List<PrepaidTransactionRecord> = emptyList()
)

data class PrepaidFormState(
    val accountId: String = PrepaidAccountId.Majica,
    val transactionDate: String = todayString(),
    val amount: String = "",
    val chargeSource: String = PrepaidChargeSource.Cash,
    val memo: String = "",
    val adjustmentDirection: String = PrepaidAdjustmentDirection.Increase,
    val operationKey: String = UUID.randomUUID().toString(),
    val inputMode: String = PrepaidInputMode.History,
    val pendingReversalTargetId: String? = null,
    val reversalOperationKey: String? = null,
    val isAccountCreationOpen: Boolean = false,
    val newAccountName: String = ""
) {
    val hasUnsavedLedgerChanges: Boolean
        get() = amount.isNotBlank() ||
            memo.isNotBlank() ||
            transactionDate != todayString() ||
            chargeSource != PrepaidChargeSource.Cash ||
            adjustmentDirection != PrepaidAdjustmentDirection.Increase

    val hasUnsavedChanges: Boolean
        get() = hasUnsavedLedgerChanges || newAccountName.isNotBlank()
}

data class PrepaidFeedback(
    val message: String,
    val isError: Boolean
)

@HiltViewModel
class PrepaidViewModel @Inject constructor(
    private val repository: PrepaidRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _form = MutableStateFlow(restoreForm())
    val form: StateFlow<PrepaidFormState> = _form.asStateFlow()

    val ledger: StateFlow<PrepaidLedgerUiState> = combine(
        repository.observeAllAccounts(),
        repository.observeAllAccountBalances(),
        repository.observeAllTransactions()
    ) { accounts, balances, transactions ->
        PrepaidLedgerUiState(
            accounts = accounts,
            balances = balances.associate { it.accountId to it.balance },
            transactions = transactions
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PrepaidLedgerUiState()
    )

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _feedback = MutableStateFlow<PrepaidFeedback?>(null)
    val feedback: StateFlow<PrepaidFeedback?> = _feedback.asStateFlow()

    fun selectAccount(accountId: String) {
        if (_form.value.hasUnsavedChanges && accountId != _form.value.accountId) {
            _feedback.value = PrepaidFeedback(
                "入力中の内容があります。保存するか破棄してから口座を切り替えてください。",
                true
            )
            return
        }
        updateForm {
            copy(
                accountId = accountId,
                chargeSource = if (accountId == PrepaidAccountId.Majica) {
                    PrepaidChargeSource.Cash
                } else {
                    chargeSource
                }
            )
        }
    }

    fun showHistory() = updateForm { copy(inputMode = PrepaidInputMode.History) }

    fun showCharge() = updateForm {
        copy(
            inputMode = PrepaidInputMode.Charge,
            chargeSource = if (accountId == PrepaidAccountId.Majica) {
                PrepaidChargeSource.Cash
            } else {
                chargeSource
            }
        )
    }

    fun showAdjustment() = updateForm { copy(inputMode = PrepaidInputMode.Adjustment) }
    fun setDate(value: String) = updateForm { copy(transactionDate = value) }
    fun setAmount(value: String) = updateForm { copy(amount = value) }
    fun setMemo(value: String) = updateForm { copy(memo = value) }
    fun setChargeSource(value: String) = updateForm {
        copy(
            chargeSource = if (accountId == PrepaidAccountId.Majica) {
                PrepaidChargeSource.Cash
            } else {
                value
            }
        )
    }
    fun setAdjustmentDirection(value: String) = updateForm {
        copy(adjustmentDirection = value)
    }

    fun beginAccountCreation() {
        if (_form.value.hasUnsavedLedgerChanges) {
            _feedback.value = PrepaidFeedback(
                "入力中の台帳内容があります。保存するか破棄してから口座を追加してください。",
                true
            )
            return
        }
        updateForm { copy(isAccountCreationOpen = true) }
    }

    fun setNewAccountName(value: String) = updateForm { copy(newAccountName = value) }

    fun cancelAccountCreation() = updateForm {
        copy(isAccountCreationOpen = false, newAccountName = "")
    }

    fun saveAccount() {
        if (_isSaving.value) return
        val name = _form.value.newAccountName
        _isSaving.value = true
        viewModelScope.launch {
            runCatching { repository.createAccount(PrepaidAccountCreateInput(name)) }
                .onSuccess { account ->
                    setForm(PrepaidFormState(accountId = account.id))
                    _feedback.value = PrepaidFeedback(
                        "${account.name}を初期残高0円で追加しました。",
                        false
                    )
                }
                .onFailure { error ->
                    _feedback.value = PrepaidFeedback(error.toUserMessage(), true)
                }
            _isSaving.value = false
        }
    }

    fun saveCharge() {
        if (_isSaving.value) return
        val state = _form.value
        val amount = parsePositiveAmount(state.amount) ?: return
        launchWrite {
            repository.createCharge(
                PrepaidChargeInput(
                    accountId = state.accountId,
                    transactionDate = state.transactionDate,
                    amount = amount,
                    chargeSource = state.chargeSource,
                    memo = state.memo,
                    operationKey = state.operationKey
                )
            )
        }
    }

    fun saveAdjustment() {
        if (_isSaving.value) return
        val state = _form.value
        val amount = parsePositiveAmount(state.amount) ?: return
        if (state.memo.isBlank()) {
            _feedback.value = PrepaidFeedback("調整理由を入力してください。", true)
            return
        }
        val delta = if (state.adjustmentDirection == PrepaidAdjustmentDirection.Decrease) {
            -amount
        } else {
            amount
        }
        launchWrite {
            repository.createAdjustment(
                PrepaidAdjustmentInput(
                    accountId = state.accountId,
                    transactionDate = state.transactionDate,
                    balanceDelta = delta,
                    memo = state.memo,
                    operationKey = state.operationKey
                )
            )
        }
    }

    fun beginReversal(transactionId: String) {
        updateForm {
            copy(
                pendingReversalTargetId = transactionId,
                reversalOperationKey = UUID.randomUUID().toString()
            )
        }
    }

    fun cancelReversal() {
        updateForm {
            copy(pendingReversalTargetId = null, reversalOperationKey = null)
        }
    }

    fun confirmReversal() {
        if (_isSaving.value) return
        val state = _form.value
        val targetId = state.pendingReversalTargetId ?: return
        val operationKey = state.reversalOperationKey ?: return
        launchWrite(
            onSuccess = {
                updateForm {
                    copy(pendingReversalTargetId = null, reversalOperationKey = null)
                }
            }
        ) {
            repository.reverseTransaction(
                PrepaidReversalInput(
                    accountId = state.accountId,
                    targetTransactionId = targetId,
                    transactionDate = todayString(),
                    memo = "取消",
                    operationKey = operationKey
                )
            )
        }
    }

    fun discardInput() {
        val current = _form.value
        setForm(
            PrepaidFormState(
                accountId = current.accountId,
                inputMode = PrepaidInputMode.History
            )
        )
        _feedback.value = null
    }

    fun consumeFeedback() {
        _feedback.value = null
    }

    private fun parsePositiveAmount(value: String): Long? {
        val amount = parsePrepaidPositiveAmount(value)
        if (amount == null || amount <= 0L) {
            _feedback.value = PrepaidFeedback("1円以上の金額を入力してください。", true)
            return null
        }
        return amount
    }

    private fun launchWrite(
        onSuccess: () -> Unit = {},
        operation: suspend () -> Any
    ) {
        _isSaving.value = true
        viewModelScope.launch {
            runCatching { operation() }
                .onSuccess {
                    onSuccess()
                    val accountId = _form.value.accountId
                    setForm(
                        PrepaidFormState(
                            accountId = accountId,
                            inputMode = PrepaidInputMode.History
                        )
                    )
                    _feedback.value = PrepaidFeedback("プリペイド台帳へ保存しました。", false)
                }
                .onFailure { error ->
                    _feedback.value = PrepaidFeedback(error.toUserMessage(), true)
                }
            _isSaving.value = false
        }
    }

    private fun Throwable.toUserMessage(): String = when ((this as? PrepaidValidationException)?.failure) {
        PrepaidValidationFailure.AccountNotFound -> "口座が見つかりません。"
        PrepaidValidationFailure.AccountInactive -> "この口座は現在利用できません。"
        PrepaidValidationFailure.ZeroBalanceDelta,
        PrepaidValidationFailure.InvalidBalanceDelta -> "1円以上の正しい金額を入力してください。"
        PrepaidValidationFailure.InsufficientBalance -> "残高が不足するため保存できません。"
        PrepaidValidationFailure.DuplicateOperationKey -> "同じ操作が既に処理されています。"
        PrepaidValidationFailure.DuplicateReversal -> "この取引は既に取消済みです。"
        PrepaidValidationFailure.UnknownChargeSource,
        PrepaidValidationFailure.ChargeSourceRequired -> "この口座では選択したチャージ元を利用できません。"
        PrepaidValidationFailure.InvalidDate -> "正しい日付を入力してください。"
        PrepaidValidationFailure.ArithmeticOverflow -> "金額が大きすぎるため保存できません。"
        PrepaidValidationFailure.ReversalOfReversal -> "取消取引を再度取消すことはできません。"
        PrepaidValidationFailure.AccountNameRequired -> "口座名を入力してください。"
        PrepaidValidationFailure.DuplicateAccountName -> "同じ名前のプリペイド口座が既にあります。"
        else -> "保存できませんでした。入力内容を確認して、もう一度お試しください。"
    }

    private fun updateForm(transform: PrepaidFormState.() -> PrepaidFormState) {
        setForm(_form.value.transform())
    }

    private fun setForm(value: PrepaidFormState) {
        _form.value = value
        savedStateHandle[AccountIdKey] = value.accountId
        savedStateHandle[DateKey] = value.transactionDate
        savedStateHandle[AmountKey] = value.amount
        savedStateHandle[ChargeSourceKey] = value.chargeSource
        savedStateHandle[MemoKey] = value.memo
        savedStateHandle[AdjustmentDirectionKey] = value.adjustmentDirection
        savedStateHandle[OperationKey] = value.operationKey
        savedStateHandle[InputModeKey] = value.inputMode
        savedStateHandle[ReversalTargetKey] = value.pendingReversalTargetId
        savedStateHandle[ReversalOperationKey] = value.reversalOperationKey
        savedStateHandle[AccountCreationOpenKey] = value.isAccountCreationOpen
        savedStateHandle[NewAccountNameKey] = value.newAccountName
    }

    private fun restoreForm(): PrepaidFormState = PrepaidFormState(
        accountId = savedStateHandle[AccountIdKey] ?: PrepaidAccountId.Majica,
        transactionDate = savedStateHandle[DateKey] ?: todayString(),
        amount = savedStateHandle[AmountKey] ?: "",
        chargeSource = savedStateHandle[ChargeSourceKey] ?: PrepaidChargeSource.Cash,
        memo = savedStateHandle[MemoKey] ?: "",
        adjustmentDirection = savedStateHandle[AdjustmentDirectionKey]
            ?: PrepaidAdjustmentDirection.Increase,
        operationKey = savedStateHandle[OperationKey] ?: UUID.randomUUID().toString(),
        inputMode = savedStateHandle[InputModeKey] ?: PrepaidInputMode.History,
        pendingReversalTargetId = savedStateHandle[ReversalTargetKey],
        reversalOperationKey = savedStateHandle[ReversalOperationKey],
        isAccountCreationOpen = savedStateHandle[AccountCreationOpenKey] ?: false,
        newAccountName = savedStateHandle[NewAccountNameKey] ?: ""
    )

    private companion object {
        const val AccountIdKey = "prepaid.accountId"
        const val DateKey = "prepaid.transactionDate"
        const val AmountKey = "prepaid.amount"
        const val ChargeSourceKey = "prepaid.chargeSource"
        const val MemoKey = "prepaid.memo"
        const val AdjustmentDirectionKey = "prepaid.adjustmentDirection"
        const val OperationKey = "prepaid.operationKey"
        const val InputModeKey = "prepaid.inputMode"
        const val ReversalTargetKey = "prepaid.reversalTarget"
        const val ReversalOperationKey = "prepaid.reversalOperationKey"
        const val AccountCreationOpenKey = "prepaid.accountCreationOpen"
        const val NewAccountNameKey = "prepaid.newAccountName"
    }
}

internal fun parsePrepaidPositiveAmount(value: String): Long? {
    val normalized = buildString {
        value.trim().forEach { character ->
            when {
                character in '0'..'9' -> append(character)
                character in '０'..'９' -> append('0' + (character - '０'))
                character == ',' || character == '，' ||
                    character == '円' || character == '￥' ||
                    character == '¥' || character.isWhitespace() -> Unit
                else -> return null
            }
        }
    }
    return normalized.toLongOrNull()?.takeIf { it > 0L }
}
