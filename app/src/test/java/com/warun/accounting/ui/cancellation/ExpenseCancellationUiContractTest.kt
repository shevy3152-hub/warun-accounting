package com.warun.accounting.ui.cancellation

import com.warun.accounting.data.cancellation.ExpenseCancellationResult
import com.warun.accounting.ui.viewmodel.CancellationUiFailure
import com.warun.accounting.ui.viewmodel.CancellationUiState
import com.warun.accounting.ui.viewmodel.ExpenseCancellationEvent
import com.warun.accounting.ui.viewmodel.ExpenseCancellationReasonMaxLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseCancellationUiContractTest {
    @Test
    fun reasonLimitAndSavingDisableConfirmationAndDismiss() {
        val valid = cancellationDialogPolicy(
            CancellationUiState(dialogVisible = true, reason = "a".repeat(200))
        )
        val tooLong = cancellationDialogPolicy(
            CancellationUiState(dialogVisible = true, reason = "a".repeat(201))
        )
        val saving = cancellationDialogPolicy(
            CancellationUiState(dialogVisible = true, isSaving = true)
        )

        assertTrue(valid.confirmEnabled)
        assertFalse(tooLong.confirmEnabled)
        assertFalse(saving.confirmEnabled)
        assertFalse(saving.dismissEnabled)
        assertEquals("取消処理中…", saving.confirmLabel)
        assertEquals(0, cancellationReasonRemaining("a".repeat(ExpenseCancellationReasonMaxLength)))
    }

    @Test
    fun databaseFailureKeepsRetryAction() {
        val policy = cancellationDialogPolicy(
            CancellationUiState(
                dialogVisible = true,
                failure = CancellationUiFailure.DatabaseFailure
            )
        )

        assertTrue(policy.confirmEnabled)
        assertEquals("もう一度試す", policy.confirmLabel)
    }

    @Test
    fun oneShotEventsMapToSingleUiActions() {
        val result = ExpenseCancellationResult(
            expenseId = "expense-1",
            originalPurchaseTransactionId = "purchase-1",
            reversalTransactionId = "reversal-1",
            prepaidAccountId = "account-1",
            amount = 500L,
            cancellationDate = "2026-07-29",
            cancelledAt = 1L,
            reason = null,
            idempotentReplay = false
        )

        assertEquals(
            ExpenseCancellationUiAction.ShowSuccess("expense-1"),
            ExpenseCancellationEvent.Success(result).toUiAction()
        )
        assertEquals(
            ExpenseCancellationUiAction.Reload,
            ExpenseCancellationEvent.ReloadRequired.toUiAction()
        )
        assertEquals(
            ExpenseCancellationUiAction.OpenAudit("expense-1"),
            ExpenseCancellationEvent.OpenAudit("expense-1").toUiAction()
        )
    }

    @Test
    fun everyFailureHasUserFacingMessageWithoutInternalIdentifiers() {
        CancellationUiFailure.entries.forEach { failure ->
            val message = cancellationFailureMessage(failure)
            assertTrue(message.isNotBlank())
            assertFalse(message.contains("UUID", ignoreCase = true))
            assertFalse(message.contains("SQLite", ignoreCase = true))
        }
        assertEquals(
            "操作内容が一致しません。画面を更新して、もう一度やり直してください。",
            cancellationFailureMessage(CancellationUiFailure.Conflict)
        )
        assertEquals(
            "支出内容が変更されています。画面を更新して、もう一度やり直してください。",
            cancellationFailureMessage(CancellationUiFailure.StaleState)
        )
    }
}
