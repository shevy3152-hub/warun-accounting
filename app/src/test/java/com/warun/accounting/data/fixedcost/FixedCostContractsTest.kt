package com.warun.accounting.data.fixedcost

import org.junit.Assert.assertEquals
import org.junit.Test

class FixedCostContractsTest {
    @Test
    fun amountStateDistinguishesEmptySameAndConflict() {
        assertEquals(ExistingAmountState.EMPTY, existingAmountState(0L, 41_617L))
        assertEquals(ExistingAmountState.SAME, existingAmountState(41_617L, 41_617L))
        assertEquals(ExistingAmountState.CONFLICT, existingAmountState(41_616L, 41_617L))
    }

    @Test
    fun paymentMethodIsDerivedFromFixedCostType() {
        assertEquals("現金", fixedCostPaymentMethodOrNull("electricity"))
        assertEquals("現金", fixedCostPaymentMethodOrNull("water"))
        assertEquals("現金", fixedCostPaymentMethodOrNull("communication"))
        assertEquals("銀行振込", fixedCostPaymentMethodOrNull("gas"))
        assertEquals(null, fixedCostPaymentMethodOrNull("food"))
    }

    @Test
    fun resultTypeHasExplicitBusinessOutcomes() {
        val results = listOf<FixedCostSaveResult>(
            FixedCostSaveResult.Success,
            FixedCostSaveResult.MissingDailyReport,
            FixedCostSaveResult.MissingEvidence,
            FixedCostSaveResult.AlreadyApplied,
            FixedCostSaveResult.AmountConflict,
            FixedCostSaveResult.ReceiptNotFound,
            FixedCostSaveResult.ReceiptAlreadyConfirmed,
            FixedCostSaveResult.InvalidFixedCostType
        )
        assertEquals(8, results.size)
    }
}
