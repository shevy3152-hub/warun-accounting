package com.warun.accounting.ui.model

import com.warun.accounting.camera.ReceiptCaptureResult

/**
 * A pending image may be removed only when every ownership and persistence
 * check still refers to the same capture in the current input session.
 */
internal fun canDeleteOwnedPendingCapture(
    discardedCapture: ReceiptCaptureResult?,
    currentCaptureId: String?,
    currentSessionExpenseId: String?,
    pendingOwnerExpenseId: String?,
    evidenceReferencesCapture: Boolean,
    journalAllowsDeletion: Boolean
): Boolean {
    val captureId = discardedCapture?.captureId?.takeIf { it.isNotBlank() } ?: return false
    if (captureId != currentCaptureId) return false
    if (currentSessionExpenseId.isNullOrBlank() || currentSessionExpenseId != pendingOwnerExpenseId) return false
    if (evidenceReferencesCapture || !journalAllowsDeletion) return false
    return true
}
