package com.warun.accounting.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.ui.model.DashboardUiState
import com.warun.accounting.util.PaymentMethodCash
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase3AggregationInstrumentedTest {
    @Test
    fun explicitZeroClosingCashIsPreserved() {
        assertEquals(0, DashboardUiState(reports = listOf(report(hasActual = true))).closingCash)
    }

    @Test
    fun pastSalesDoNotAppearInTodaySummary() {
        val summary = resolveSidebarSummary(DashboardUiState(reports = listOf(report(cashSales = 50_000))), null)
        assertEquals(0, summary.salesTotal)
    }

    @Test
    fun consumablesRecordOverridesLegacyAmount() {
        val state = DashboardUiState(
            reports = listOf(report(consumables = 3_000)),
            expenses = listOf(expense(2_000))
        )
        assertEquals(2_000, state.expenseTotal)
    }

    private fun report(
        cashSales: Long = 0,
        consumables: Long = 0,
        hasActual: Boolean = false
    ) = DailyReport(
        id = "phase3",
        reportDate = "2026-07-20",
        status = DailyReportStatus.Completed,
        authorName = null,
        cashSales = cashSales,
        cardSales = 0,
        qrSales = 0,
        accountsReceivableSales = 0,
        otherSales = 0,
        foodPurchases = 0,
        alcoholPurchases = 0,
        consumablesExpense = consumables,
        utilitiesExpense = 0,
        electricityExpense = 0,
        gasExpense = 0,
        waterExpense = 0,
        communicationExpense = 0,
        rentExpense = 0,
        accountantFeeExpense = 0,
        miscellaneousExpense = 0,
        otherExpense = 0,
        openingCash = 10_000,
        actualClosingCash = 0,
        customerCount = 0,
        groupCount = 0,
        memo = null,
        createdAt = 1,
        updatedAt = 1,
        hasActualClosingCash = hasActual
    )

    private fun expense(amount: Long) = ExpenseRecord(
        id = "phase3-expense",
        expenseDate = "2026-07-20",
        category = ExpenseCategory.Consumables,
        supplierName = null,
        amount = amount,
        paymentMethod = PaymentMethodCash,
        memo = null,
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1,
        updatedAt = 1
    )
}
