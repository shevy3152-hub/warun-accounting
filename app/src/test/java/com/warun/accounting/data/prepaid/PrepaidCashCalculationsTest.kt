package com.warun.accounting.data.prepaid

import com.warun.accounting.data.local.PrepaidAccountId
import com.warun.accounting.data.local.PrepaidChargeSource
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class PrepaidCashCalculationsTest {
    @Test
    fun onlyCashChargesAndTheirReversalsAffectCashOutflow() {
        val cash = transaction("cash", PrepaidTransactionType.Charge, 10_000, PrepaidChargeSource.Cash)
        val bank = transaction("bank", PrepaidTransactionType.Charge, 5_000, PrepaidChargeSource.BankAccount)
        val credit = transaction("credit", PrepaidTransactionType.Charge, 4_000, PrepaidChargeSource.CreditCard)
        val other = transaction("other", PrepaidTransactionType.Charge, 3_000, PrepaidChargeSource.OtherNonCash)
        val adjustment = transaction("adjustment", PrepaidTransactionType.Adjustment, 500)
        val reversal = transaction(
            "reversal",
            PrepaidTransactionType.Reversal,
            -10_000,
            reversalOf = cash.id
        )

        assertEquals(
            0L,
            netCashChargeAmount(listOf(cash, bank, credit, other, adjustment, reversal))
        )
        assertEquals(10_000L, netCashChargeAmount(listOf(cash, bank, credit, other)))
    }

    @Test
    fun periodUsesEachLedgerEntryDateAndStillResolvesReversalTarget() {
        val charge = transaction(
            id = "charge",
            type = PrepaidTransactionType.Charge,
            delta = 10_000,
            chargeSource = PrepaidChargeSource.Cash,
            date = "2026-07-26"
        )
        val reversal = transaction(
            id = "reversal",
            type = PrepaidTransactionType.Reversal,
            delta = -10_000,
            reversalOf = charge.id,
            date = "2026-07-27"
        )

        assertEquals(
            10_000L,
            netCashChargeAmount(listOf(charge, reversal)) { it == "2026-07-26" }
        )
        assertEquals(
            -10_000L,
            netCashChargeAmount(listOf(charge, reversal)) { it == "2026-07-27" }
        )
    }

    private fun transaction(
        id: String,
        type: String,
        delta: Long,
        chargeSource: String? = null,
        reversalOf: String? = null,
        date: String = "2026-07-27"
    ) = PrepaidTransactionRecord(
        id = id,
        accountId = PrepaidAccountId.Majica,
        transactionDate = date,
        transactionType = type,
        balanceDelta = delta,
        expenseId = null,
        chargeSource = chargeSource,
        reversalOfTransactionId = reversalOf,
        operationKey = "operation-$id",
        memo = "",
        createdAt = 1
    )
}
