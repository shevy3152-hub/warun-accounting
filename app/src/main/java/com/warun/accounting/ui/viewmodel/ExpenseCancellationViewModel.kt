package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warun.accounting.data.cancellation.ExpenseCancellationException
import com.warun.accounting.data.cancellation.ExpenseCancellationFailure
import com.warun.accounting.data.cancellation.ExpenseCancellationRepository
import com.warun.accounting.data.cancellation.ExpenseCancellationRequest
import com.warun.accounting.data.cancellation.ExpenseCancellationRequestFingerprint
import com.warun.accounting.data.cancellation.ExpenseCancellationRequestFingerprintInput
import com.warun.accounting.data.cancellation.ExpenseCancellationResult
import com.warun.accounting.data.cancellation.ExpenseCancellationRules
import com.warun.accounting.data.cancellation.ExpenseCancellationSnapshot
import com.warun.accounting.data.cancellation.ExpenseCancellationValidationException
import com.warun.accounting.ui.util.todayString
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

const val ExpenseCancellationReasonMaxLength = ExpenseCancellationRules.MaxReasonLength

enum class CancellationUiFailure {
    InvalidRequest,
    ExpenseNotFound,
    Conflict,
    AlreadyCancelled,
    StaleState,
    PrepaidStateInconsistent,
    CancellationStateCorrupted,
    DatabaseFailure,
    UnexpectedFailure
}

sealed interface ExpenseCancellationEvent {
    data class Success(val result: ExpenseCancellationResult) : ExpenseCancellationEvent
    data object ReloadRequired : ExpenseCancellationEvent
    data class OpenAudit(val expenseId: String) : ExpenseCancellationEvent
}

data class CancellationUiState(
    val dialogVisible: Boolean = false,
    val isLoading: Boolean = false,
    val expenseId: String? = null,
    val expectedExpenseUpdatedAt: Long? = null,
    val originalPurchaseTransactionId: String? = null,
    val prepaidAccountId: String? = null,
    val expectedAmount: Long? = null,
    val expectedPurchaseDate: String? = null,
    val cancellationDate: String = "",
    /**
     * User-visible, unnormalized text. The repository's NFC/trim/blank-to-null
     * normalization is applied once when a request is finalized for saving.
     */
    val reason: String = "",
    val operationKey: String? = null,
    val reasonRevisionOpen: Boolean = false,
    val dateRevisionOpen: Boolean = false,
    val isSaving: Boolean = false,
    val result: ExpenseCancellationResult? = null,
    val failure: CancellationUiFailure? = null
)

internal interface ExpenseCancellationViewModelGateway {
    suspend fun loadSnapshot(expenseId: String): ExpenseCancellationSnapshot
    suspend fun cancel(request: ExpenseCancellationRequest): ExpenseCancellationResult
}

internal class RepositoryExpenseCancellationViewModelGateway(
    private val repository: ExpenseCancellationRepository
) : ExpenseCancellationViewModelGateway {
    override suspend fun loadSnapshot(expenseId: String): ExpenseCancellationSnapshot =
        repository.loadCancellationSnapshot(expenseId)

    override suspend fun cancel(
        request: ExpenseCancellationRequest
    ): ExpenseCancellationResult = repository.cancelExpense(request)
}

internal fun interface ExpenseCancellationDateProvider {
    fun today(): String
}

internal fun interface ExpenseCancellationOperationKeyGenerator {
    fun newOperationKey(): String
}

class SystemExpenseCancellationDateProvider @Inject constructor() :
    ExpenseCancellationDateProvider {
    override fun today(): String = todayString()
}

class UuidExpenseCancellationOperationKeyGenerator @Inject constructor() :
    ExpenseCancellationOperationKeyGenerator {
    override fun newOperationKey(): String = "expense-cancel:${UUID.randomUUID()}"
}

@HiltViewModel
class ExpenseCancellationViewModel internal constructor(
    private val gateway: ExpenseCancellationViewModelGateway,
    private val savedStateHandle: SavedStateHandle,
    private val dateProvider: ExpenseCancellationDateProvider,
    private val operationKeyGenerator: ExpenseCancellationOperationKeyGenerator
) : ViewModel() {
    @Inject
    constructor(
        repository: ExpenseCancellationRepository,
        savedStateHandle: SavedStateHandle,
        dateProvider: SystemExpenseCancellationDateProvider,
        operationKeyGenerator: UuidExpenseCancellationOperationKeyGenerator
    ) : this(
        gateway = RepositoryExpenseCancellationViewModelGateway(repository),
        savedStateHandle = savedStateHandle,
        dateProvider = dateProvider,
        operationKeyGenerator = operationKeyGenerator
    )

    private val _state = MutableStateFlow(restoreState())
    val state: StateFlow<CancellationUiState> = _state.asStateFlow()

    private val eventChannel = Channel<ExpenseCancellationEvent>(Channel.BUFFERED)
    val events: Flow<ExpenseCancellationEvent> = eventChannel.receiveAsFlow()

    fun startCancellation(expenseId: String) {
        val targetId = expenseId.trim()
        if (targetId.isEmpty()) {
            setTerminalFailure(CancellationUiFailure.InvalidRequest)
            return
        }
        val current = _state.value
        if (
            current.expenseId == targetId &&
            (current.dialogVisible || current.isLoading)
        ) {
            return
        }
        clearPersistedState()
        _state.value = CancellationUiState(
            isLoading = true,
            expenseId = targetId
        )
        viewModelScope.launch {
            try {
                val snapshot = gateway.loadSnapshot(targetId)
                if (!_state.value.isLoading || _state.value.expenseId != targetId) return@launch
                val next = CancellationUiState(
                    dialogVisible = true,
                    expenseId = snapshot.expenseId,
                    expectedExpenseUpdatedAt = snapshot.expectedExpenseUpdatedAt,
                    originalPurchaseTransactionId = snapshot.originalPurchaseTransactionId,
                    prepaidAccountId = snapshot.prepaidAccountId,
                    expectedAmount = snapshot.amount,
                    expectedPurchaseDate = snapshot.purchaseDate,
                    cancellationDate = dateProvider.today(),
                    operationKey = operationKeyGenerator.newOperationKey()
                )
                setState(next)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ExpenseCancellationException) {
                if (_state.value.expenseId == targetId) {
                    handleFailure(error.failure, duringSave = false)
                }
            } catch (_: Throwable) {
                if (_state.value.expenseId == targetId) {
                    setTerminalFailure(CancellationUiFailure.UnexpectedFailure)
                }
            }
        }
    }

    fun updateCancellationDate(value: String) {
        val current = _state.value
        if (!current.dialogVisible || current.isSaving || current.cancellationDate == value) return
        setState(
            current.copy(
                cancellationDate = value,
                operationKey = operationKeyForChangedInput(
                    current,
                    revisionOpen = current.dateRevisionOpen
                ),
                dateRevisionOpen = true,
                result = null,
                failure = null
            )
        )
    }

    fun updateReason(value: String) {
        val current = _state.value
        if (!current.dialogVisible || current.isSaving || current.reason == value) return
        setState(
            current.copy(
                reason = value,
                operationKey = operationKeyForChangedInput(
                    current,
                    revisionOpen = current.reasonRevisionOpen
                ),
                reasonRevisionOpen = true,
                result = null,
                failure = null
            )
        )
    }

    fun cancelDialog() {
        if (_state.value.isSaving) return
        clearPersistedState()
        _state.value = CancellationUiState()
    }

    fun clearOutcome() {
        _state.value = _state.value.copy(result = null, failure = null)
    }

    fun saveCancellation() {
        val current = _state.value
        if (!current.dialogVisible || current.isSaving) return
        val operationKey = current.operationKey ?: operationKeyGenerator.newOperationKey()
        val request = buildRequest(current, operationKey)
        if (request == null) {
            setState(
                current.copy(
                    operationKey = operationKey,
                    failure = CancellationUiFailure.InvalidRequest
                )
            )
            return
        }
        setState(
            current.copy(
                operationKey = operationKey,
                reasonRevisionOpen = false,
                dateRevisionOpen = false,
                isSaving = true,
                result = null,
                failure = null
            )
        )
        viewModelScope.launch {
            try {
                val result = gateway.cancel(request)
                clearPersistedState()
                _state.value = CancellationUiState(result = result)
                eventChannel.send(ExpenseCancellationEvent.Success(result))
            } catch (error: CancellationException) {
                _state.value = _state.value.copy(isSaving = false)
                throw error
            } catch (error: ExpenseCancellationException) {
                handleFailure(error.failure, duringSave = true)
            } catch (_: Throwable) {
                setTerminalFailure(CancellationUiFailure.UnexpectedFailure)
            }
        }
    }

    fun confirmCancellation() {
        saveCancellation()
    }

    private fun buildRequest(
        state: CancellationUiState,
        operationKey: String
    ): ExpenseCancellationRequest? {
        val expenseId = state.expenseId ?: return null
        val updatedAt = state.expectedExpenseUpdatedAt ?: return null
        val purchaseId = state.originalPurchaseTransactionId ?: return null
        val accountId = state.prepaidAccountId ?: return null
        val amount = state.expectedAmount ?: return null
        val purchaseDate = state.expectedPurchaseDate ?: return null
        val normalizedReason = ExpenseCancellationRules.normalizeReason(state.reason)
        if ((normalizedReason?.length ?: 0) > ExpenseCancellationReasonMaxLength) return null
        return try {
            ExpenseCancellationRules.validateOperationKey(operationKey)
            ExpenseCancellationRequestFingerprint.create(
                ExpenseCancellationRequestFingerprintInput(
                    expenseId = expenseId,
                    expectedExpenseUpdatedAt = updatedAt,
                    originalPurchaseTransactionId = purchaseId,
                    prepaidAccountId = accountId,
                    amount = amount,
                    purchaseDate = purchaseDate,
                    cancellationDate = state.cancellationDate,
                    reason = normalizedReason
                )
            )
            ExpenseCancellationRequest(
                operationKey = operationKey,
                expenseId = expenseId,
                expectedExpenseUpdatedAt = updatedAt,
                expectedOriginalPurchaseTransactionId = purchaseId,
                expectedPrepaidAccountId = accountId,
                expectedAmount = amount,
                expectedPurchaseDate = purchaseDate,
                cancellationDate = state.cancellationDate,
                reason = normalizedReason
            )
        } catch (_: ExpenseCancellationValidationException) {
            null
        }
    }

    private fun operationKeyForChangedInput(
        state: CancellationUiState,
        revisionOpen: Boolean
    ): String =
        if (!revisionOpen) {
            operationKeyGenerator.newOperationKey()
        } else {
            state.operationKey ?: operationKeyGenerator.newOperationKey()
        }

    private suspend fun handleFailure(
        failure: ExpenseCancellationFailure,
        duringSave: Boolean
    ) {
        val mapped = failure.toUiFailure()
        when (failure) {
            ExpenseCancellationFailure.InvalidRequest,
            ExpenseCancellationFailure.DatabaseFailure -> {
                val current = _state.value
                setState(
                    current.copy(
                        isLoading = false,
                        isSaving = false,
                        failure = mapped
                    )
                )
            }

            ExpenseCancellationFailure.Conflict,
            ExpenseCancellationFailure.StaleState,
            ExpenseCancellationFailure.ExpenseNotFound -> {
                setTerminalFailure(mapped)
                eventChannel.send(ExpenseCancellationEvent.ReloadRequired)
            }

            ExpenseCancellationFailure.AlreadyCancelled -> {
                val expenseId = _state.value.expenseId
                setTerminalFailure(mapped)
                if (expenseId != null) {
                    eventChannel.send(ExpenseCancellationEvent.OpenAudit(expenseId))
                }
            }

            ExpenseCancellationFailure.PrepaidStateInconsistent,
            ExpenseCancellationFailure.CancellationStateCorrupted -> {
                setTerminalFailure(mapped)
            }
        }
        if (!duringSave && failure == ExpenseCancellationFailure.InvalidRequest) {
            setTerminalFailure(mapped)
        }
    }

    private fun setTerminalFailure(failure: CancellationUiFailure) {
        clearPersistedState()
        _state.value = CancellationUiState(failure = failure)
    }

    private fun setState(value: CancellationUiState) {
        _state.value = value
        if (value.dialogVisible) {
            savedStateHandle[DialogVisibleKey] = true
            savedStateHandle[ExpenseIdKey] = value.expenseId
            savedStateHandle[ExpectedUpdatedAtKey] = value.expectedExpenseUpdatedAt
            savedStateHandle[OriginalPurchaseIdKey] = value.originalPurchaseTransactionId
            savedStateHandle[PrepaidAccountIdKey] = value.prepaidAccountId
            savedStateHandle[ExpectedAmountKey] = value.expectedAmount
            savedStateHandle[ExpectedPurchaseDateKey] = value.expectedPurchaseDate
            savedStateHandle[CancellationDateKey] = value.cancellationDate
            savedStateHandle[ReasonKey] = value.reason
            savedStateHandle[OperationKey] = value.operationKey
            savedStateHandle[ReasonRevisionOpenKey] = value.reasonRevisionOpen
            savedStateHandle[DateRevisionOpenKey] = value.dateRevisionOpen
        }
    }

    private fun restoreState(): CancellationUiState {
        val dialogVisible: Boolean = savedStateHandle[DialogVisibleKey] ?: false
        if (!dialogVisible) return CancellationUiState()
        val expenseId: String = savedStateHandle[ExpenseIdKey] ?: return invalidRestoredState()
        val updatedAt: Long = savedStateHandle[ExpectedUpdatedAtKey]
            ?: return invalidRestoredState()
        val purchaseId: String = savedStateHandle[OriginalPurchaseIdKey]
            ?: return invalidRestoredState()
        val accountId: String = savedStateHandle[PrepaidAccountIdKey]
            ?: return invalidRestoredState()
        val amount: Long = savedStateHandle[ExpectedAmountKey] ?: return invalidRestoredState()
        val purchaseDate: String = savedStateHandle[ExpectedPurchaseDateKey]
            ?: return invalidRestoredState()
        val cancellationDate: String = savedStateHandle[CancellationDateKey]
            ?: return invalidRestoredState()
        val operationKey: String = savedStateHandle[OperationKey]
            ?: return invalidRestoredState()
        return CancellationUiState(
            dialogVisible = true,
            expenseId = expenseId,
            expectedExpenseUpdatedAt = updatedAt,
            originalPurchaseTransactionId = purchaseId,
            prepaidAccountId = accountId,
            expectedAmount = amount,
            expectedPurchaseDate = purchaseDate,
            cancellationDate = cancellationDate,
            reason = savedStateHandle[ReasonKey] ?: "",
            operationKey = operationKey,
            reasonRevisionOpen = savedStateHandle[ReasonRevisionOpenKey] ?: false,
            dateRevisionOpen = savedStateHandle[DateRevisionOpenKey] ?: false,
            isSaving = false
        )
    }

    private fun invalidRestoredState(): CancellationUiState {
        clearPersistedState()
        return CancellationUiState(failure = CancellationUiFailure.InvalidRequest)
    }

    private fun clearPersistedState() {
        PersistedKeys.forEach { savedStateHandle.remove<Any?>(it) }
    }

    private fun ExpenseCancellationFailure.toUiFailure(): CancellationUiFailure = when (this) {
        ExpenseCancellationFailure.InvalidRequest -> CancellationUiFailure.InvalidRequest
        ExpenseCancellationFailure.ExpenseNotFound -> CancellationUiFailure.ExpenseNotFound
        ExpenseCancellationFailure.Conflict -> CancellationUiFailure.Conflict
        ExpenseCancellationFailure.AlreadyCancelled -> CancellationUiFailure.AlreadyCancelled
        ExpenseCancellationFailure.StaleState -> CancellationUiFailure.StaleState
        ExpenseCancellationFailure.PrepaidStateInconsistent ->
            CancellationUiFailure.PrepaidStateInconsistent
        ExpenseCancellationFailure.CancellationStateCorrupted ->
            CancellationUiFailure.CancellationStateCorrupted
        ExpenseCancellationFailure.DatabaseFailure -> CancellationUiFailure.DatabaseFailure
    }

    private companion object {
        const val DialogVisibleKey = "expenseCancellation.dialogVisible"
        const val ExpenseIdKey = "expenseCancellation.expenseId"
        const val ExpectedUpdatedAtKey = "expenseCancellation.expectedExpenseUpdatedAt"
        const val OriginalPurchaseIdKey =
            "expenseCancellation.originalPurchaseTransactionId"
        const val PrepaidAccountIdKey = "expenseCancellation.prepaidAccountId"
        const val ExpectedAmountKey = "expenseCancellation.expectedAmount"
        const val ExpectedPurchaseDateKey = "expenseCancellation.expectedPurchaseDate"
        const val CancellationDateKey = "expenseCancellation.cancellationDate"
        const val ReasonKey = "expenseCancellation.reason"
        const val OperationKey = "expenseCancellation.operationKey"
        const val ReasonRevisionOpenKey = "expenseCancellation.reasonRevisionOpen"
        const val DateRevisionOpenKey = "expenseCancellation.dateRevisionOpen"

        val PersistedKeys = listOf(
            DialogVisibleKey,
            ExpenseIdKey,
            ExpectedUpdatedAtKey,
            OriginalPurchaseIdKey,
            PrepaidAccountIdKey,
            ExpectedAmountKey,
            ExpectedPurchaseDateKey,
            CancellationDateKey,
            ReasonKey,
            OperationKey,
            ReasonRevisionOpenKey,
            DateRevisionOpenKey
        )
    }
}
