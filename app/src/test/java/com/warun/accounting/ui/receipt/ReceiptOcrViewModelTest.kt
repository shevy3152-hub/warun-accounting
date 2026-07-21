package com.warun.accounting.ui.receipt

import androidx.lifecycle.SavedStateHandle
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.future.ReceiptOcrDraft
import com.warun.accounting.future.ReceiptOcrEngine
import com.warun.accounting.future.ReceiptOcrGateway
import com.warun.accounting.future.ReceiptOcrRequest
import com.warun.accounting.ocr.ReceiptOcrRecognitionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptOcrViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val capture = ReceiptCaptureResult("capture-1", "file:/receipt.jpg", 123L)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun successPreservesRawTextAndTransitionsToSuccess() = runTest(dispatcher) {
        val gateway = FakeGateway { draft("店名\n合計 1,234円") }
        val viewModel = ReceiptOcrViewModel(SavedStateHandle(), gateway)

        assertTrue(viewModel.runOcr(capture))
        assertEquals(ReceiptOcrUiState.Processing(capture), viewModel.uiState.value)
        runCurrent()

        assertEquals(
            ReceiptOcrUiState.Success(capture, "店名\n合計 1,234円"),
            viewModel.uiState.value
        )
    }

    @Test
    fun failureTransitionsToSafeErrorState() = runTest(dispatcher) {
        val gateway = FakeGateway {
            throw ReceiptOcrRecognitionException(IllegalStateException("ML failure"))
        }
        val viewModel = ReceiptOcrViewModel(SavedStateHandle(), gateway)

        viewModel.runOcr(capture)
        runCurrent()

        assertEquals(
            ReceiptOcrUiState.Error(capture, "文字を認識できませんでした"),
            viewModel.uiState.value
        )
    }

    @Test
    fun processingPreventsDuplicateExecution() = runTest(dispatcher) {
        val gate = CompletableDeferred<ReceiptOcrDraft>()
        val gateway = FakeGateway { gate.await() }
        val viewModel = ReceiptOcrViewModel(SavedStateHandle(), gateway)

        assertTrue(viewModel.runOcr(capture))
        assertFalse(viewModel.runOcr(capture))
        runCurrent()
        assertEquals(1, gateway.callCount)

        gate.complete(draft("結果"))
        runCurrent()
        assertEquals(ReceiptOcrUiState.Success(capture, "結果"), viewModel.uiState.value)
    }

    @Test
    fun blankRecognitionTransitionsToEmpty() = runTest(dispatcher) {
        val viewModel = ReceiptOcrViewModel(SavedStateHandle(), FakeGateway { draft(" \n ") })

        viewModel.runOcr(capture)
        runCurrent()

        assertEquals(ReceiptOcrUiState.Empty(capture), viewModel.uiState.value)
    }

    @Test
    fun restoredSuccessIsNotExecutedAgain() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val firstGateway = FakeGateway { draft("復元する結果") }
        val first = ReceiptOcrViewModel(handle, firstGateway)
        first.runOcr(capture)
        runCurrent()

        val restoredGateway = FakeGateway { draft("実行されない") }
        val restored = ReceiptOcrViewModel(handle, restoredGateway)

        assertEquals(
            ReceiptOcrUiState.Success(capture, "復元する結果"),
            restored.uiState.value
        )
        assertFalse(restored.runOcr(capture))
        runCurrent()
        assertEquals(0, restoredGateway.callCount)
    }

    private fun draft(rawText: String) = ReceiptOcrDraft(
        imageId = capture.captureId,
        engine = ReceiptOcrEngine.MlKitTextRecognition,
        dateCandidates = emptyList(),
        storeNameCandidates = emptyList(),
        totalAmountCandidates = emptyList(),
        taxAmountCandidates = emptyList(),
        registrationNumberCandidates = emptyList(),
        rawText = rawText
    )

    private class FakeGateway(
        private val result: suspend () -> ReceiptOcrDraft
    ) : ReceiptOcrGateway {
        var callCount: Int = 0
            private set

        override suspend fun readReceipt(request: ReceiptOcrRequest): ReceiptOcrDraft {
            callCount += 1
            return result()
        }
    }
}
