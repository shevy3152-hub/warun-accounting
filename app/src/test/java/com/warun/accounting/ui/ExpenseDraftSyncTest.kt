package com.warun.accounting.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseDraftSyncTest {
    @Test
    fun unchangedNewFormDoesNotCreateDraftUntilCaptureExplicitlyStoresIt() {
        assertFalse(
            shouldRetainExpenseDraft(
                formDirty = false,
                restoredDraftId = null,
                currentExpenseId = "expense-id"
            )
        )
    }

    @Test
    fun draftStoredBeforeCameraIsNotClearedByUnchangedFormRecomposition() {
        assertTrue(
            shouldRetainExpenseDraft(
                formDirty = false,
                restoredDraftId = "expense-id",
                currentExpenseId = "expense-id"
            )
        )
    }

    @Test
    fun draftFromAnotherFormIsNotAppliedToCurrentExpense() {
        assertFalse(
            shouldRetainExpenseDraft(
                formDirty = false,
                restoredDraftId = "another-expense",
                currentExpenseId = "expense-id"
            )
        )
    }
}
