package com.warun.accounting.ui.model

import com.warun.accounting.camera.ReceiptCaptureResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCaptureDiscardPolicyTest {
    private val capture = ReceiptCaptureResult("capture-1", "file:/pending/1.jpg", 1L)

    @Test
    fun deletesOnlyMatchingOwnedUnreferencedUnprotectedCapture() {
        assertTrue(
            canDeleteOwnedPendingCapture(
                discardedCapture = capture,
                currentCaptureId = "capture-1",
                currentSessionExpenseId = "expense-1",
                pendingOwnerExpenseId = "expense-1",
                evidenceReferencesCapture = false,
                journalAllowsDeletion = true
            )
        )
    }

    @Test
    fun rejectsStaleOwnerEvidenceReferenceAndJournalProtection() {
        assertFalse(canDeleteOwnedPendingCapture(capture, "other", "expense-1", "expense-1", false, true))
        assertFalse(canDeleteOwnedPendingCapture(capture, "capture-1", "expense-1", "expense-2", false, true))
        assertFalse(canDeleteOwnedPendingCapture(capture, "capture-1", "expense-1", "expense-1", true, true))
        assertFalse(canDeleteOwnedPendingCapture(capture, "capture-1", "expense-1", "expense-1", false, false))
    }
}
