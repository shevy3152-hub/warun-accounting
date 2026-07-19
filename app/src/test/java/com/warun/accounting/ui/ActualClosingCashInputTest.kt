package com.warun.accounting.ui

import com.warun.accounting.data.local.DailyReport
import org.junit.Assert.assertEquals
import org.junit.Test

class ActualClosingCashInputTest {
    @Test
    fun savedZeroWithoutInputMarkerRestoresAsBlank() {
        assertEquals("", report(actualClosingCash = 0L, hasActualClosingCash = false).toInput().actualClosingCash)
    }

    @Test
    fun explicitlyEnteredZeroRestoresAsZero() {
        assertEquals("0", report(actualClosingCash = 0L, hasActualClosingCash = true).toInput().actualClosingCash)
    }

    @Test
    fun enteredPositiveValueRestoresAsValue() {
        assertEquals("50000", report(actualClosingCash = 50_000L, hasActualClosingCash = true).toInput().actualClosingCash)
    }

    private fun report(actualClosingCash: Long, hasActualClosingCash: Boolean) = DailyReport(
        id = "2026-07-19",
        reportDate = "2026-07-19",
        status = "draft",
        authorName = null,
        cashSales = 0L,
        cardSales = 0L,
        qrSales = 0L,
        accountsReceivableSales = 0L,
        otherSales = 0L,
        foodPurchases = 0L,
        alcoholPurchases = 0L,
        consumablesExpense = 0L,
        utilitiesExpense = 0L,
        electricityExpense = 0L,
        gasExpense = 0L,
        waterExpense = 0L,
        communicationExpense = 0L,
        rentExpense = 0L,
        accountantFeeExpense = 0L,
        miscellaneousExpense = 0L,
        otherExpense = 0L,
        openingCash = 0L,
        actualClosingCash = actualClosingCash,
        customerCount = 0,
        groupCount = 0,
        memo = null,
        createdAt = 0L,
        updatedAt = 0L,
        hasActualClosingCash = hasActualClosingCash
    )
}