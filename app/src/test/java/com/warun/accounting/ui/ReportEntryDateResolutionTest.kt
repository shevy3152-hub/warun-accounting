package com.warun.accounting.ui

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportEntryDateResolutionTest {
    @Test
    fun existingDateResolvesToItsPersistedReportId() {
        val resolved = resolveDailyReportInputForDate(
            reports = listOf(report(id = "report-23", date = "2026-08-23")),
            reportDate = "2026-08-23"
        )

        assertEquals("report-23", resolved.id)
        assertEquals("2026-08-23", resolved.reportDate)
    }

    @Test
    fun newDateUsesBlankId() {
        val resolved = resolveDailyReportInputForDate(
            reports = listOf(report(id = "report-23", date = "2026-08-23")),
            reportDate = "2026-08-24"
        )

        assertEquals("", resolved.id)
        assertEquals("2026-08-24", resolved.reportDate)
    }

    private fun report(id: String, date: String) = DailyReport(
        id = id,
        reportDate = date,
        status = DailyReportStatus.Completed,
        authorName = "本人",
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
        actualClosingCash = 0L,
        customerCount = 0,
        groupCount = 0,
        memo = null,
        createdAt = 1L,
        updatedAt = 1L
    )
}
