package com.warun.accounting.ui.prepaid

import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidChargeSource
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepaidHistoryTest {
    @Test
    fun historyIsNewestFirstWithRunningBalanceAndReversalMarker() {
        val charge = transaction(
            id = "charge",
            type = PrepaidTransactionType.Charge,
            delta = 10_000,
            createdAt = 1,
            source = PrepaidChargeSource.Cash
        )
        val adjustment = transaction(
            id = "adjustment",
            type = PrepaidTransactionType.Adjustment,
            delta = 500,
            createdAt = 2
        )
        val reversal = transaction(
            id = "reversal",
            type = PrepaidTransactionType.Reversal,
            delta = -10_000,
            createdAt = 3,
            reversalOf = charge.id
        )

        val rows = buildPrepaidHistoryRows(listOf(adjustment, reversal, charge))

        assertEquals(listOf("reversal", "adjustment", "charge"), rows.map { it.transaction.id })
        assertEquals(listOf(500L, 10_500L, 10_000L), rows.map { it.balanceAfter })
        assertTrue(rows.last().isReversed)
        assertFalse(rows.first().isReversed)
    }

    private fun transaction(
        id: String,
        type: String,
        delta: Long,
        createdAt: Long,
        source: String? = null,
        reversalOf: String? = null
    ) = PrepaidTransactionRecord(
        id = id,
        accountId = PrepaidAccountId.Majica,
        transactionDate = "2026-07-27",
        transactionType = type,
        balanceDelta = delta,
        expenseId = null,
        chargeSource = source,
        reversalOfTransactionId = reversalOf,
        operationKey = "operation-$id",
        memo = "",
        createdAt = createdAt
    )
}
