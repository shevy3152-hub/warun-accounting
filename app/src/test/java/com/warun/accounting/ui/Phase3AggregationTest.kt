package com.warun.accounting.ui

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.ui.model.DashboardUiState
import com.warun.accounting.ui.util.currentMonthString
import com.warun.accounting.ui.util.todayString
import com.warun.accounting.util.PaymentMethodCash
import com.warun.accounting.util.PaymentMethodCreditPurchase
import com.warun.accounting.util.ExpenseDateCategoryKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

class Phase3AggregationTest {
    @Test
    fun missingActualClosingCashUsesTheoreticalBalance() {
        val state = DashboardUiState(reports = listOf(report(openingCash = 10_000, cashSales = 5_000)))
        assertEquals(15_000, state.closingCash)
    }

    @Test
    fun explicitZeroActualClosingCashUsesZero() {
        val state = DashboardUiState(reports = listOf(report(openingCash = 10_000, cashSales = 5_000, hasActual = true)))
        assertEquals(0, state.closingCash)
    }

    @Test
    fun positiveActualClosingCashUsesEnteredValue() {
        val state = DashboardUiState(reports = listOf(report(actualClosingCash = 50_000, hasActual = true)))
        assertEquals(50_000, state.closingCash)
    }

    @Test
    fun noTodayReportDoesNotFallbackToPastSales() {
        val state = DashboardUiState(reports = listOf(report(date = "2020-01-01", cashSales = 90_000)))
        val summary = resolveSidebarSummary(state, null)
        assertEquals(0, summary.salesTotal)
    }

    @Test
    fun savedTodayReportIsUsedByTodaySummary() {
        val state = DashboardUiState(reports = listOf(report(date = todayString(), cashSales = 12_000)))
        val summary = resolveSidebarSummary(state, null)
        assertEquals(12_000, summary.salesTotal)
    }

    @Test
    fun liveTodayValuesOverrideSavedTodayReport() {
        val state = DashboardUiState(reports = listOf(report(date = todayString(), cashSales = 12_000)))
        val live = SidebarSummaryOverride(15_000, 14_000, 20_000)
        assertEquals(live, resolveSidebarSummary(state, live))
    }

    @Test
    fun legacyHomeMonthSalesStillUsesCurrentMonthContract() {
        val state = DashboardUiState(
            reports = listOf(
                report(date = "${currentMonthString()}-01", cashSales = 12_000),
                report(date = "2020-01-01", cashSales = 90_000),
            ),
        )

        assertEquals(12_000L, state.monthSales)
    }

    @Test
    fun legacyConsumablesIsUsedWhenNoExpenseRecordExists() {
        val state = DashboardUiState(reports = listOf(report(consumables = 3_000)))
        assertEquals(3_000, state.expenseTotal)
    }

    @Test
    fun consumablesExpenseRecordIsUsedWithoutDailyReport() {
        val state = DashboardUiState(expenses = listOf(expense("consumables", ExpenseCategory.Consumables, 2_000)))
        assertEquals(2_000, state.expenseTotal)
    }

    @Test
    fun consumablesExpenseRecordOverridesLegacyAmountOnSameDate() {
        val date = "2026-07-20"
        val state = DashboardUiState(
            reports = listOf(report(date = date, consumables = 3_000)),
            expenses = listOf(expense("consumables", ExpenseCategory.Consumables, 2_000, date))
        )
        assertEquals(2_000, state.expenseTotal)
    }

    @Test
    fun cancelledConsumablesDoesNotRestoreLegacyInDailyMonthlyOrCashTotals() {
        val date = todayString()
        val state = DashboardUiState(
            reports = listOf(
                report(
                    date = date,
                    openingCash = 100_000L,
                    cashSales = 10_000L,
                    consumables = 3_000L
                )
            ),
            cancelledExpenseKeys = setOf(
                ExpenseDateCategoryKey(date, ExpenseCategory.Consumables)
            )
        )

        assertEquals(0L, state.expenseTotal)
        assertEquals(0L, state.todayExpensesTotal)
        assertEquals(0L, state.monthExpensesTotal)
        assertEquals(0L, state.cashExpenses)
        assertEquals(10_000L, state.todayCashFlow)
        assertEquals(110_000L, state.todayClosingCash)
    }

    @Test
    fun foodAndAlcoholRemainExpenseRecordBased() {
        val date = "2026-07-20"
        val state = DashboardUiState(
            reports = listOf(report(date = date, food = 99_000, alcohol = 88_000)),
            expenses = listOf(
                expense("food", ExpenseCategory.FoodPurchase, 12_000, date),
                expense("alcohol", ExpenseCategory.AlcoholPurchase, 18_000, date)
            )
        )
        assertEquals(30_000, state.expenseTotal)
    }

    @Test
    fun supplierCreditPurchaseTotalsSeparateTokinoYaAndSakatsuAndIgnoreOtherPayments() {
        val month = YearMonth.of(2026, 7)
        val totals = supplierCreditPurchaseTotals(
            listOf(
                expense("tokino", ExpenseCategory.FoodPurchase, 12_000, "2026-07-31", " トキノ屋 ", PaymentMethodCreditPurchase),
                expense("sakatsu", ExpenseCategory.AlcoholPurchase, 8_000, "2026-07-01", "サカツ", PaymentMethodCreditPurchase),
                expense("cash", ExpenseCategory.FoodPurchase, 99_000, "2026-07-15", "トキノ屋", PaymentMethodCash),
                expense("outside", ExpenseCategory.FoodPurchase, 77_000, "2026-08-01", "トキノ屋", PaymentMethodCreditPurchase),
            ),
            month,
        )
        assertEquals(12_000L, totals.tokinoYa)
        assertEquals(8_000L, totals.sakatsu)
    }

    @Test
    fun supplierCreditPurchaseTotalsUsesOnlyActiveExpenseInput() {
        val date = "2026-07-10"
        val totals = supplierCreditPurchaseTotals(
            listOf(expense("cancelled", ExpenseCategory.FoodPurchase, 12_000, date, "トキノ屋", PaymentMethodCreditPurchase)),
            YearMonth.of(2026, 7),
        )
        // DashboardUiState receives active ExpenseRecord rows from the repository.
        assertEquals(12_000L, totals.tokinoYa)
    }

    private fun report(
        date: String = "2026-07-20",
        openingCash: Long = 0,
        cashSales: Long = 0,
        actualClosingCash: Long = 0,
        hasActual: Boolean = false,
        consumables: Long = 0,
        food: Long = 0,
        alcohol: Long = 0
    ) = DailyReport(
        id = date,
        reportDate = date,
        status = DailyReportStatus.Completed,
        authorName = null,
        cashSales = cashSales,
        cardSales = 0,
        qrSales = 0,
        accountsReceivableSales = 0,
        otherSales = 0,
        foodPurchases = food,
        alcoholPurchases = alcohol,
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
        openingCash = openingCash,
        actualClosingCash = actualClosingCash,
        customerCount = 0,
        groupCount = 0,
        memo = null,
        createdAt = 1,
        updatedAt = 1,
        hasActualClosingCash = hasActual
    )

    private fun expense(
        id: String,
        category: String,
        amount: Long,
        date: String = "2026-07-20",
        supplier: String? = null,
        paymentMethod: String = PaymentMethodCash,
    ) = ExpenseRecord(
        id = id,
        expenseDate = date,
        category = category,
        supplierName = supplier,
        amount = amount,
        paymentMethod = paymentMethod,
        memo = null,
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1,
        updatedAt = 1
    )
}
