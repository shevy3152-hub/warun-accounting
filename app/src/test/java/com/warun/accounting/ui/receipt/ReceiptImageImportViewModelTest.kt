package com.warun.accounting.ui.receipt

import androidx.lifecycle.SavedStateHandle
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.camera.ReceiptImageImportError
import com.warun.accounting.camera.ReceiptImageImportException
import com.warun.accounting.camera.ReceiptImageImportGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptImageImportViewModelTest {
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
    fun successfulImportRemainsUntilConsumedExactlyOnce() = runTest(dispatcher) {
        val importer = FakeImporter(result = capture("capture-1"))
        val viewModel = ReceiptImageImportViewModel(SavedStateHandle(), importer)

        assertTrue(viewModel.importImage("content://receipt/1"))
        advanceUntilIdle()

        assertEquals(
            "capture-1",
            (viewModel.uiState.value as ReceiptImageImportUiState.Imported).result.captureId
        )
        assertNull(viewModel.consumeImported("different"))
        assertEquals("capture-1", viewModel.consumeImported("capture-1")?.captureId)
        assertNull(viewModel.consumeImported("capture-1"))
        assertEquals(ReceiptImageImportUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun pickerCancellationDoesNotChangeStateOrStartImport() = runTest(dispatcher) {
        val importer = FakeImporter(result = capture("unused"))
        val viewModel = ReceiptImageImportViewModel(SavedStateHandle(), importer)

        assertFalse(viewModel.handlePickerResult(null))
        advanceUntilIdle()

        assertEquals(ReceiptImageImportUiState.Idle, viewModel.uiState.value)
        assertEquals(0, importer.importCount)
    }

    @Test
    fun importingPreventsDuplicateExecution() = runTest(dispatcher) {
        val gate = CompletableDeferred<ReceiptCaptureResult>()
        val importer = FakeImporter(gate = gate)
        val viewModel = ReceiptImageImportViewModel(SavedStateHandle(), importer)

        assertTrue(viewModel.importImage("content://receipt/1"))
        assertFalse(viewModel.importImage("content://receipt/2"))
        assertTrue(viewModel.uiState.value is ReceiptImageImportUiState.Importing)
        gate.complete(capture("capture-1"))
        advanceUntilIdle()

        assertEquals(1, importer.importCount)
    }

    @Test
    fun failedImportKeepsErrorUntilDismissedAndCanRetry() = runTest(dispatcher) {
        val importer = FakeImporter(
            failure = ReceiptImageImportException(ReceiptImageImportError.UnsupportedFormat)
        )
        val viewModel = ReceiptImageImportViewModel(SavedStateHandle(), importer)

        assertTrue(viewModel.importImage("content://receipt/unsupported"))
        advanceUntilIdle()

        assertEquals(
            ReceiptImageImportError.UnsupportedFormat,
            (viewModel.uiState.value as ReceiptImageImportUiState.Error).error
        )
        viewModel.clearError()
        assertEquals(ReceiptImageImportUiState.Idle, viewModel.uiState.value)
        assertTrue(viewModel.importImage("content://receipt/retry"))
    }

    @Test
    fun resetMakesLateResultStaleAndItIsNotAdopted() = runTest(dispatcher) {
        val gate = CompletableDeferred<ReceiptCaptureResult>()
        val importer = FakeImporter(gate = gate)
        val viewModel = ReceiptImageImportViewModel(SavedStateHandle(), importer)

        assertTrue(viewModel.importImage("content://receipt/1"))
        viewModel.reset()
        gate.complete(capture("stale"))
        advanceUntilIdle()

        assertEquals(ReceiptImageImportUiState.Idle, viewModel.uiState.value)
        assertNull(viewModel.consumeImported("stale"))
    }

    @Test
    fun importedResultSurvivesSavedStateRecreation() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val first = ReceiptImageImportViewModel(handle, FakeImporter(result = capture("restored")))
        assertTrue(first.importImage("content://receipt/1"))
        advanceUntilIdle()

        val restored = ReceiptImageImportViewModel(handle, FakeImporter(result = capture("unused")))
        advanceUntilIdle()

        assertEquals(
            "restored",
            (restored.uiState.value as ReceiptImageImportUiState.Imported).result.captureId
        )
    }

    @Test
    fun processRecreationDuringCopyBecomesSafeRetryableError() {
        val handle = SavedStateHandle(
            mapOf("receipt.import.status" to "importing")
        )

        val restored = ReceiptImageImportViewModel(handle, FakeImporter(result = capture("unused")))

        assertEquals(
            ReceiptImageImportError.ImportFailed,
            (restored.uiState.value as ReceiptImageImportUiState.Error).error
        )
    }

    @Test
    fun initializationCleansOnlyImporterTemporaryFiles() = runTest(dispatcher) {
        val importer = FakeImporter(result = capture("unused"))

        ReceiptImageImportViewModel(SavedStateHandle(), importer)
        advanceUntilIdle()

        assertEquals(1, importer.cleanupCount)
    }

    private class FakeImporter(
        private val result: ReceiptCaptureResult? = null,
        private val failure: Throwable? = null,
        private val gate: CompletableDeferred<ReceiptCaptureResult>? = null
    ) : ReceiptImageImportGateway {
        var importCount: Int = 0
        var cleanupCount: Int = 0

        override suspend fun importImage(externalUri: String): ReceiptCaptureResult {
            importCount++
            failure?.let { throw it }
            return gate?.await() ?: requireNotNull(result)
        }

        override suspend fun cleanupOrphanedImports(): Int {
            cleanupCount++
            return 0
        }
    }

    private fun capture(id: String) = ReceiptCaptureResult(
        captureId = id,
        localUri = "file:/pending/receipt_$id.jpg",
        capturedAt = 1L
    )
}
