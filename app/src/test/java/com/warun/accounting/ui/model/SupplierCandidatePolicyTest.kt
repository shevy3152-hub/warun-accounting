package com.warun.accounting.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplierCandidatePolicyTest {
    @Test
    fun candidateNameNormalizesWidthAndSurroundingWhitespace() {
        assertEquals("ABC 商店", normalizeSupplierCandidateName("　ＡＢＣ   商店　"))
    }

    @Test
    fun customSupplierIsSavedOnlyAfterOtherWasSelectedAndNameIsNew() {
        val existing = listOf("バロー", "ABC 商店", "他")

        assertTrue(
            shouldSaveCustomSupplierCandidate(
                customSupplierSelected = true,
                supplierName = "新しい商店",
                displayedCandidateNames = existing
            )
        )
        assertFalse(
            shouldSaveCustomSupplierCandidate(
                customSupplierSelected = false,
                supplierName = "新しい商店",
                displayedCandidateNames = existing
            )
        )
        assertFalse(
            shouldSaveCustomSupplierCandidate(
                customSupplierSelected = true,
                supplierName = "ＡＢＣ　商店",
                displayedCandidateNames = existing
            )
        )
    }

    @Test
    fun eligibleCandidateIsPersistedOnlyAfterExpenseSaveSucceeds() {
        assertFalse(
            shouldPersistSupplierCandidateAfterExpenseSave(
                saveSucceeded = false,
                candidateEligible = true
            )
        )
        assertFalse(
            shouldPersistSupplierCandidateAfterExpenseSave(
                saveSucceeded = true,
                candidateEligible = false
            )
        )
        assertTrue(
            shouldPersistSupplierCandidateAfterExpenseSave(
                saveSucceeded = true,
                candidateEligible = true
            )
        )
    }
}
