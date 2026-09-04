package com.warun.accounting.ui.balance

import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.util.PaymentMethodCreditPurchase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

class CreditPurchaseTaxTest {
    @Test
    fun calculatesMonthlyTaxAfterSupplierAggregation() {
        val result = creditPurchaseTaxSummaries(
            listOf(
                expense("t1", "トキノ屋", 10_000, "2026-08-01"),
                expense("t2", "トキノ屋", 6_260, "2026-08-20"),
                expense("s1", "サカツ", 19_500, "2026-08-02"),
                expense("outside", "トキノ屋", 99_000, "2026-07-31")
            ),
            YearMonth.of(2026, 8)
        )

        assertEquals(17_560L, result[0].grossAmount)
        assertEquals(1_300L, result[0].taxAmount)
        assertEquals(21_450L, result[1].grossAmount)
        assertEquals(1_950L, result[1].taxAmount)
    }

    @Test
    fun truncatesTaxAndExcludesCancelledInput() {
        val result = creditPurchaseTaxSummaries(
            listOf(
                expense("active", "トキノ屋", 16_261, "2026-08-01"),
                expense("cancelled", "トキノ屋", 999, "2026-08-01")
            ),
            YearMonth.of(2026, 8),
            excludedExpenseIds = setOf("cancelled")
        )

        assertEquals(16_261L, result[0].netAmount)
        assertEquals(1_300L, result[0].taxAmount)
        assertEquals(17_561L, result[0].grossAmount)
    }

    @Test
    fun separatesSuppliersAndDoesNotIncludeCashPurchases() {
        val result = creditPurchaseTaxSummaries(
            listOf(
                expense("cash", "トキノ屋", 99_000, "2026-08-01", paymentMethod = "現金"),
                expense("credit", "サカツ", 19_500, "2026-08-01")
            ),
            YearMonth.of(2026, 8)
        )

        assertEquals(0L, result[0].netAmount)
        assertEquals(19_500L, result[1].netAmount)
    }

    private fun expense(
        id: String,
        supplier: String,
        amount: Long,
        date: String,
        paymentMethod: String = PaymentMethodCreditPurchase
    ) = ExpenseRecord(id, date, "food_purchase", supplier, amount, paymentMethod, null, null, "manual", 1L, 1L)
}
