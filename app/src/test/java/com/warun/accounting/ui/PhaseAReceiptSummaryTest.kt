package com.warun.accounting.ui

import com.warun.accounting.data.local.ReceiptRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

class PhaseAReceiptSummaryTest {
    @Test
    fun sidebarStartsWithMonthMode() {
        assertEquals(SidebarSummaryMode.Month, defaultSidebarSummaryMode)
    }

    @Test
    fun warningCountsKeepDatedMonthAndUndatedAllPeriodSeparate() {
        val counts = unconfirmedReceiptCounts(
            listOf(receipt("in", "2026-07-01", false), receipt("out", "2026-06-01", false), receipt("none", null, false)),
            YearMonth.of(2026, 7),
        )
        assertEquals(1, counts.datedInMonth)
        assertEquals(1, counts.undatedAllPeriod)
    }

    @Test
    fun monthListIncludesOnlyUndatedFalseReceiptsInSelectedMonth() {
        val receipts = listOf(
            receipt("in-month", "2026-07-05", false),
            receipt("outside", "2026-06-30", false),
            receipt("confirmed", "2026-07-06", true),
            receipt("undated", null, false),
        )
        assertEquals(
            listOf("in-month"),
            unconfirmedReceiptsForMonth(receipts, YearMonth.of(2026, 7)).map { it.id },
        )
    }

    @Test
    fun undatedCountIsAllPeriodAndOnlyUnconfirmed() {
        val receipts = listOf(
            receipt("undated-1", null, false),
            receipt("undated-confirmed", null, true),
            receipt("dated", "2026-07-01", false),
        )
        assertEquals(listOf("undated-1"), undatedUnconfirmedReceipts(receipts).map { it.id })
    }

    private fun receipt(id: String, purchaseDate: String?, confirmed: Boolean) = ReceiptRecord(
        id = id,
        purchaseDate = purchaseDate,
        capturedDate = null,
        registeredAt = 1L,
        storeName = "店",
        totalAmount = 1_000L,
        taxAmount = 0L,
        registrationNumber = null,
        expenseCategory = null,
        isConfirmed = confirmed,
        memo = null,
        updatedAt = 1L,
    )
}
