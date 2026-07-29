package com.warun.accounting.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.warun.accounting.data.cancellation.ExpenseCancellationException
import com.warun.accounting.data.cancellation.ExpenseCancellationFailure
import com.warun.accounting.data.cancellation.ExpenseCancellationRequest
import com.warun.accounting.data.cancellation.ExpenseCancellationResult
import com.warun.accounting.data.cancellation.ExpenseCancellationSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseCancellationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startLoadsSnapshotAndCreatesOperationKey() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.startCancellation("expense-1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.dialogVisible)
        assertEquals("expense-1", state.expenseId)
        assertEquals(10L, state.expectedExpenseUpdatedAt)
        assertEquals("purchase-1", state.originalPurchaseTransactionId)
        assertEquals("prepaid-majica", state.prepaidAccountId)
        assertEquals(101L, state.expectedAmount)
        assertEquals("2026-07-27", state.expectedPurchaseDate)
        assertEquals("2026-07-29", state.cancellationDate)
        assertTrue(state.operationKey!!.startsWith("expense-cancel:"))
    }

    @Test
    fun repeatedStartForSameTargetKeepsOperationKey() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()
        val firstKey = viewModel.state.value.operationKey

        viewModel.startCancellation("expense-1")
        advanceUntilIdle()

        assertEquals(firstKey, viewModel.state.value.operationKey)
        assertEquals(1, gateway.snapshotCalls.size)
    }

    @Test
    fun savedStateRestoresAllDialogInputs() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val first = viewModel(handle = handle)
        first.startCancellation("expense-1")
        advanceUntilIdle()
        first.updateCancellationDate("2026-07-30")
        first.updateReason("  仕入取消  ")
        first.saveCancellation()
        val savedKey = first.state.value.operationKey

        val restored = viewModel(handle = handle)

        assertTrue(restored.state.value.dialogVisible)
        assertEquals("expense-1", restored.state.value.expenseId)
        assertEquals("2026-07-30", restored.state.value.cancellationDate)
        assertEquals("  仕入取消  ", restored.state.value.reason)
        assertEquals(savedKey, restored.state.value.operationKey)
    }

    @Test
    fun recreationNeverRestoresSavingFlag() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = FakeGateway(cancelAction = { kotlinx.coroutines.awaitCancellation() })
        val first = viewModel(gateway = gateway, handle = handle)
        first.startCancellation("expense-1")
        advanceUntilIdle()
        first.saveCancellation()
        dispatcher.scheduler.runCurrent()
        assertTrue(first.state.value.isSaving)

        val restored = viewModel(handle = handle)

        assertFalse(restored.state.value.isSaving)
        assertTrue(restored.state.value.dialogVisible)
    }

    @Test
    fun reasonChangeInvalidatesAndFinalizesNewOperationKeyOnce() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val keys = FakeKeyGenerator()
        val viewModel = viewModel(gateway, keys = keys)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()
        val firstKey = viewModel.state.value.operationKey

        viewModel.updateReason("取")
        val changedKey = viewModel.state.value.operationKey
        viewModel.updateReason("取消")
        assertEquals(changedKey, viewModel.state.value.operationKey)
        viewModel.saveCancellation()
        advanceUntilIdle()

        assertNotEquals(firstKey, gateway.cancelCalls.single().operationKey)
        assertEquals(2, keys.count)
    }

    @Test
    fun cancellationDateChangeUsesNewOperationKey() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()
        val firstKey = viewModel.state.value.operationKey

        viewModel.updateCancellationDate("2026-07-30")
        viewModel.saveCancellation()
        advanceUntilIdle()

        assertNotEquals(firstKey, gateway.cancelCalls.single().operationKey)
        assertEquals("2026-07-30", gateway.cancelCalls.single().cancellationDate)
    }

    @Test
    fun targetChangeLoadsNewSnapshotAndOperationKey() = runTest(dispatcher) {
        val gateway = FakeGateway(
            snapshots = mapOf(
                "expense-1" to snapshot(),
                "expense-2" to snapshot().copy(
                    expenseId = "expense-2",
                    originalPurchaseTransactionId = "purchase-2"
                )
            )
        )
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()
        val firstKey = viewModel.state.value.operationKey

        viewModel.startCancellation("expense-2")
        advanceUntilIdle()

        assertEquals("expense-2", viewModel.state.value.expenseId)
        assertNotEquals(firstKey, viewModel.state.value.operationKey)
    }

    @Test
    fun cancelDialogClearsStateAndSavedOperationKey() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle = handle)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()

        viewModel.cancelDialog()
        val restored = viewModel(handle = handle)

        assertEquals(CancellationUiState(), viewModel.state.value)
        assertEquals(CancellationUiState(), restored.state.value)
    }

    @Test
    fun doubleSaveCallsRepositoryOnce() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()

        viewModel.saveCancellation()
        viewModel.saveCancellation()
        advanceUntilIdle()

        assertEquals(1, gateway.cancelCalls.size)
    }

    @Test
    fun successClearsDialogInputsAndOperationKey() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.dialogVisible)
        assertNull(viewModel.state.value.operationKey)
        assertNull(viewModel.state.value.expenseId)
        assertNotNull(viewModel.state.value.result)
    }

    @Test
    fun databaseFailurePreservesInputsAndOperationKeyForRetry() = runTest(dispatcher) {
        val gateway = FakeGateway(
            cancelAction = {
                throw ExpenseCancellationException(ExpenseCancellationFailure.DatabaseFailure)
            }
        )
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()
        viewModel.updateReason("取消理由")
        viewModel.saveCancellation()
        advanceUntilIdle()
        val failedKey = viewModel.state.value.operationKey

        assertTrue(viewModel.state.value.dialogVisible)
        assertEquals("取消理由", viewModel.state.value.reason)
        assertEquals(CancellationUiFailure.DatabaseFailure, viewModel.state.value.failure)
        assertNotNull(failedKey)

        viewModel.updateReason("変更後の取消理由")

        assertNotEquals(failedKey, viewModel.state.value.operationKey)
    }

    @Test
    fun invalidReasonPreservesInputsAndDoesNotCallRepository() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()
        val tooLong = "あ".repeat(ExpenseCancellationReasonMaxLength + 1)
        viewModel.updateReason(tooLong)

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertEquals(CancellationUiFailure.InvalidRequest, viewModel.state.value.failure)
        assertEquals(tooLong, viewModel.state.value.reason)
        assertTrue(gateway.cancelCalls.isEmpty())
    }

    @Test
    fun conflictClosesDialogAndRequestsReload() = runTest(dispatcher) {
        val viewModel = failingViewModel(ExpenseCancellationFailure.Conflict)
        val event = async { viewModel.events.first() }

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertEquals(ExpenseCancellationEvent.ReloadRequired, event.await())
        assertFalse(viewModel.state.value.dialogVisible)
        assertEquals(CancellationUiFailure.Conflict, viewModel.state.value.failure)
    }

    @Test
    fun alreadyCancelledClosesDialogAndOpensAudit() = runTest(dispatcher) {
        val viewModel = failingViewModel(ExpenseCancellationFailure.AlreadyCancelled)
        val event = async { viewModel.events.first() }

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertEquals(ExpenseCancellationEvent.OpenAudit("expense-1"), event.await())
        assertEquals(CancellationUiFailure.AlreadyCancelled, viewModel.state.value.failure)
    }

    @Test
    fun staleStateClosesDialogAndRequestsReload() = runTest(dispatcher) {
        val viewModel = failingViewModel(ExpenseCancellationFailure.StaleState)
        val event = async { viewModel.events.first() }

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertEquals(ExpenseCancellationEvent.ReloadRequired, event.await())
        assertEquals(CancellationUiFailure.StaleState, viewModel.state.value.failure)
    }

    @Test
    fun prepaidStateInconsistentClosesDialogWithoutRetryState() = runTest(dispatcher) {
        val viewModel = failingViewModel(ExpenseCancellationFailure.PrepaidStateInconsistent)

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.dialogVisible)
        assertNull(viewModel.state.value.operationKey)
        assertEquals(
            CancellationUiFailure.PrepaidStateInconsistent,
            viewModel.state.value.failure
        )
    }

    @Test
    fun cancellationStateCorruptedDoesNotAttemptRepair() = runTest(dispatcher) {
        val gateway = FakeGateway(
            cancelAction = {
                throw ExpenseCancellationException(
                    ExpenseCancellationFailure.CancellationStateCorrupted
                )
            }
        )
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertEquals(1, gateway.cancelCalls.size)
        assertFalse(viewModel.state.value.dialogVisible)
        assertEquals(
            CancellationUiFailure.CancellationStateCorrupted,
            viewModel.state.value.failure
        )
    }

    @Test
    fun coroutineCancellationIsNotMappedToUiFailure() = runTest(dispatcher) {
        val gateway = FakeGateway(cancelAction = { throw CancellationException("cancelled") })
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertNull(viewModel.state.value.failure)
        assertFalse(viewModel.state.value.isSaving)
    }

    @Test
    fun blankReasonIsSentAsNull() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()
        viewModel.updateReason(" \t ")

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertNull(gateway.cancelCalls.single().reason)
    }

    @Test
    fun maximumLengthReasonIsAcceptedAndNormalized() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()
        val reason = "あ".repeat(ExpenseCancellationReasonMaxLength)
        viewModel.updateReason(" $reason ")

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertEquals(reason, gateway.cancelCalls.single().reason)
    }

    @Test
    fun retryOfSameFailedRequestKeepsOperationKey() = runTest(dispatcher) {
        var attempts = 0
        val gateway = FakeGateway(
            cancelAction = { request ->
                attempts += 1
                if (attempts == 1) {
                    throw ExpenseCancellationException(
                        ExpenseCancellationFailure.DatabaseFailure
                    )
                }
                result(request)
            }
        )
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()

        viewModel.saveCancellation()
        advanceUntilIdle()
        val failedKey = viewModel.state.value.operationKey
        viewModel.saveCancellation()
        advanceUntilIdle()

        assertEquals(2, gateway.cancelCalls.size)
        assertEquals(failedKey, gateway.cancelCalls[0].operationKey)
        assertEquals(failedKey, gateway.cancelCalls[1].operationKey)
    }

    @Test
    fun successEventIsDeliveredOnlyOnce() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()
        val firstEvent = async { viewModel.events.first() }

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertTrue(firstEvent.await() is ExpenseCancellationEvent.Success)
        assertNotNull(viewModel.state.value.result)
        viewModel.clearOutcome()
        assertNull(viewModel.state.value.result)
    }

    @Test
    fun expenseNotFoundDuringStartDoesNotOpenDialog() = runTest(dispatcher) {
        val gateway = FakeGateway(
            snapshotAction = {
                throw ExpenseCancellationException(ExpenseCancellationFailure.ExpenseNotFound)
            }
        )
        val viewModel = viewModel(gateway)
        val event = async { viewModel.events.first() }

        viewModel.startCancellation("missing")
        advanceUntilIdle()

        assertEquals(ExpenseCancellationEvent.ReloadRequired, event.await())
        assertFalse(viewModel.state.value.dialogVisible)
        assertEquals(CancellationUiFailure.ExpenseNotFound, viewModel.state.value.failure)
    }

    @Test
    fun unexpectedFailureIsExplicitAndClearsRetryKey() = runTest(dispatcher) {
        val gateway = FakeGateway(cancelAction = { error("unexpected") })
        val viewModel = viewModel(gateway)
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()

        viewModel.saveCancellation()
        advanceUntilIdle()

        assertEquals(CancellationUiFailure.UnexpectedFailure, viewModel.state.value.failure)
        assertFalse(viewModel.state.value.dialogVisible)
        assertNull(viewModel.state.value.operationKey)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.failingViewModel(
        failure: ExpenseCancellationFailure
    ): ExpenseCancellationViewModel {
        val viewModel = viewModel(
            FakeGateway(cancelAction = { throw ExpenseCancellationException(failure) })
        )
        viewModel.startCancellation("expense-1")
        advanceUntilIdle()
        return viewModel
    }

    private fun viewModel(
        gateway: FakeGateway = FakeGateway(),
        handle: SavedStateHandle = SavedStateHandle(),
        keys: FakeKeyGenerator = FakeKeyGenerator()
    ) = ExpenseCancellationViewModel(
        gateway = gateway,
        savedStateHandle = handle,
        dateProvider = ExpenseCancellationDateProvider { "2026-07-29" },
        operationKeyGenerator = keys
    )

    private class FakeKeyGenerator : ExpenseCancellationOperationKeyGenerator {
        var count = 0
            private set

        override fun newOperationKey(): String {
            count += 1
            return "expense-cancel:00000000-0000-4000-8000-${count.toString().padStart(12, '0')}"
        }
    }

    private class FakeGateway(
        private val snapshots: Map<String, ExpenseCancellationSnapshot> =
            mapOf("expense-1" to snapshot()),
        private val snapshotAction: (suspend (String) -> ExpenseCancellationSnapshot)? = null,
        private val cancelAction: suspend (ExpenseCancellationRequest) ->
            ExpenseCancellationResult = ::result
    ) : ExpenseCancellationViewModelGateway {
        val snapshotCalls = mutableListOf<String>()
        val cancelCalls = mutableListOf<ExpenseCancellationRequest>()

        override suspend fun loadSnapshot(expenseId: String): ExpenseCancellationSnapshot {
            snapshotCalls += expenseId
            return snapshotAction?.invoke(expenseId)
                ?: snapshots[expenseId]
                ?: throw ExpenseCancellationException(
                    ExpenseCancellationFailure.ExpenseNotFound
                )
        }

        override suspend fun cancel(
            request: ExpenseCancellationRequest
        ): ExpenseCancellationResult {
            cancelCalls += request
            return cancelAction(request)
        }
    }

    private companion object {
        fun snapshot() = ExpenseCancellationSnapshot(
            expenseId = "expense-1",
            expectedExpenseUpdatedAt = 10L,
            originalPurchaseTransactionId = "purchase-1",
            prepaidAccountId = "prepaid-majica",
            amount = 101L,
            purchaseDate = "2026-07-27"
        )

        fun result(request: ExpenseCancellationRequest) = ExpenseCancellationResult(
            expenseId = request.expenseId,
            originalPurchaseTransactionId = request.expectedOriginalPurchaseTransactionId,
            reversalTransactionId = "reversal-1",
            prepaidAccountId = request.expectedPrepaidAccountId,
            amount = request.expectedAmount,
            cancellationDate = request.cancellationDate,
            cancelledAt = 20L,
            reason = request.reason,
            idempotentReplay = false
        )
    }
}
