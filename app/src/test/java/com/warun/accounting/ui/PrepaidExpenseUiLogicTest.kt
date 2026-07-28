package com.warun.accounting.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepaidExpenseUiLogicTest {
    @Test
    fun projectionShowsAvailableAndInsufficientBalances() {
        assertEquals(
            PrepaidBalanceProjection.Available(8_500L),
            prepaidBalanceProjection(10_000L, 1_500L)
        )
        assertEquals(
            PrepaidBalanceProjection.Insufficient(-1L),
            prepaidBalanceProjection(999L, 1_000L)
        )
    }

    @Test
    fun projectionRejectsMissingInvalidAndOverflowingInputs() {
        assertEquals(PrepaidBalanceProjection.Invalid, prepaidBalanceProjection(null, 1L))
        assertEquals(PrepaidBalanceProjection.Invalid, prepaidBalanceProjection(1L, null))
        assertEquals(PrepaidBalanceProjection.Invalid, prepaidBalanceProjection(1L, 0L))
        assertTrue(
            prepaidBalanceProjection(Long.MIN_VALUE, 1L) is
                PrepaidBalanceProjection.Overflow
        )
    }

    @Test
    fun editProjectionRestoresOldPurchaseBeforeApplyingChangedAmount() {
        assertEquals(
            PrepaidBalanceProjection.Available(9_300L),
            prepaidBalanceProjectionForExpenseEdit(
                currentBalance = 9_500L,
                amount = 700L,
                existingAmount = 500L,
                existingAccountId = "prepaid-majica",
                selectedAccountId = "prepaid-majica"
            )
        )
        assertEquals(
            PrepaidBalanceProjection.Insufficient(-100L),
            prepaidBalanceProjectionForExpenseEdit(
                currentBalance = 600L,
                amount = 700L,
                existingAmount = 500L,
                existingAccountId = "prepaid-majica",
                selectedAccountId = "prepaid-au-pay"
            )
        )
    }
}
