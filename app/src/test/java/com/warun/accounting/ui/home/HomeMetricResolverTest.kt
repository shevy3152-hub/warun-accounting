package com.warun.accounting.ui.home

import com.warun.accounting.domain.metrics.BusinessMetricCalculationCoordinator
import com.warun.accounting.domain.metrics.BusinessMetricCalculationResult
import com.warun.accounting.domain.metrics.BusinessMetricSourceSnapshot
import com.warun.accounting.domain.metrics.MetricAvailability
import com.warun.accounting.domain.metrics.MetricDailyReportSource
import com.warun.accounting.domain.metrics.MetricExpenseCategory
import com.warun.accounting.domain.metrics.MetricExpenseSource
import com.warun.accounting.domain.metrics.MetricExpenseVisibilitySource
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.ui.model.BusinessMetricUiState
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMetricResolverTest {
    @Test
    fun dailyPeriodUsesExistingHomeDateValue() {
        assertEquals(
            MetricPeriod.Daily(LocalDate.parse("2026-08-01")),
            homeDailyMetricPeriod("2026-08-01"),
        )
    }

    @Test
    fun dailySalesAndExpensesUseRecordedValues() {
        val period = MetricPeriod.Daily(LocalDate.parse("2026-07-01"))
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 100_000L,
            expenses = listOf(metricExpense("food", period.startDate, 20_000L)),
        )

        assertEquals(100_000L, resolveDailySales(period, state).amountYen)
        assertEquals(20_000L, resolveDailyExpenses(period, state).amountYen)
    }

    @Test
    fun currentDailyNullsRemainUnavailableInsteadOfBecomingLegacyOrZero() {
        val period = MetricPeriod.Daily(LocalDate.parse("2026-08-01"))
        val state = success(period, "2026-08-01", salesYen = null)

        val sales = resolveDailySales(period, state)
        val expenses = resolveDailyExpenses(period, state)

        assertNull(sales.amountYen)
        assertNull(expenses.amountYen)
        assertEquals("データ不足", sales.unavailableText)
        assertTrue(sales.statusText.contains("SALES_SOURCE"))
        assertTrue(expenses.statusText.contains("EXPENSE_SOURCE"))
        assertTrue(sales.statusText.contains("当日途中"))
    }

    @Test
    fun dailyExpensesExcludeCancelledExpense() {
        val period = MetricPeriod.Daily(LocalDate.parse("2026-07-01"))
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 100_000L,
            expenses = listOf(
                metricExpense("active-food", period.startDate, 20_000L),
                metricExpense("cancelled-alcohol", period.startDate, 10_000L, isCancelled = true),
            ),
        )

        assertEquals(20_000L, resolveDailyExpenses(period, state).amountYen)
    }

    @Test
    fun dailyLoadingFailureAndLegacyRemainDistinct() {
        val period = MetricPeriod.Daily(LocalDate.parse("2026-08-01"))
        val loading = resolveDailySales(period, BusinessMetricUiState.Loading)
        val failure = resolveDailyExpenses(
            period,
            BusinessMetricUiState.DataAccessFailure("test.Failure", "unavailable"),
        )
        val legacy = resolveHomeDailySales(
            source = HomeMetricSource.LEGACY,
            legacyYen = 99_000L,
            expectedPeriod = period,
            state = BusinessMetricUiState.Loading,
        )

        assertNull(loading.amountYen)
        assertEquals("読み込み中", loading.unavailableText)
        assertNull(failure.amountYen)
        assertEquals("取得できません", failure.unavailableText)
        assertEquals(99_000L, legacy.amountYen)
        assertTrue(legacy.statusText.contains("LEGACY"))
    }

    @Test
    fun monthlyAvailableValuesRemainConnected() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 12_000L,
            expenses = listOf(metricExpense("expense", period.startDate, 4_000L)),
        )

        val sales = resolveMonthlySales(period, state)
        val expenses = resolveMonthlyExpenses(period, state)

        assertEquals(12_000L, sales.amountYen)
        assertEquals(4_000L, expenses.amountYen)
        assertTrue(sales.statusText.contains(MetricAvailability.AVAILABLE.name))
    }

    @Test
    fun currentMonthlyValuesRemainPartial() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 15_000L,
            expenses = listOf(metricExpense("expense", period.startDate, 4_000L)),
        )

        listOf(resolveMonthlySales(period, state), resolveMonthlyExpenses(period, state)).forEach {
            assertTrue(it.statusText.contains(MetricAvailability.PARTIAL_DATA.name))
            assertTrue(it.statusText.contains("月途中"))
        }
    }

    @Test
    fun monthlyNullLoadingAndFailureStillDoNotFallback() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val nullState = success(period, "2026-08-01", salesYen = null)
        val nullSales = resolveMonthlySales(period, nullState)
        val loading = resolveMonthlyExpenses(period, BusinessMetricUiState.Loading)
        val failure = resolveMonthlySales(
            period,
            BusinessMetricUiState.DataAccessFailure("test.Failure", "unavailable"),
        )

        assertNull(nullSales.amountYen)
        assertEquals("データ不足", nullSales.unavailableText)
        assertNull(loading.amountYen)
        assertEquals("読み込み中", loading.unavailableText)
        assertNull(failure.amountYen)
        assertEquals("取得できません", failure.unavailableText)
    }

    @Test
    fun monthlyLegacySwitchUsesEachExplicitRollbackValue() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val sales = resolveHomeMonthlySales(
            source = HomeMetricSource.LEGACY,
            legacyYen = 99_000L,
            expectedPeriod = period,
            state = BusinessMetricUiState.Loading,
        )
        val expenses = resolveHomeMonthlyExpenses(
            source = HomeMetricSource.LEGACY,
            legacyYen = 33_000L,
            expectedPeriod = period,
            state = BusinessMetricUiState.Loading,
        )

        assertEquals(99_000L, sales.amountYen)
        assertEquals(33_000L, expenses.amountYen)
        assertTrue(expenses.statusText.contains("LEGACY"))
    }

    private fun resolveDailySales(period: MetricPeriod.Daily, state: BusinessMetricUiState) =
        resolveHomeDailySales(HomeMetricSource.BUSINESS_METRIC, 99_000L, period, state)

    private fun resolveDailyExpenses(period: MetricPeriod.Daily, state: BusinessMetricUiState) =
        resolveHomeDailyExpenses(HomeMetricSource.BUSINESS_METRIC, 33_000L, period, state)

    private fun resolveMonthlySales(period: MetricPeriod.Monthly, state: BusinessMetricUiState) =
        resolveHomeMonthlySales(HomeMetricSource.BUSINESS_METRIC, 99_000L, period, state)

    private fun resolveMonthlyExpenses(period: MetricPeriod.Monthly, state: BusinessMetricUiState) =
        resolveHomeMonthlyExpenses(HomeMetricSource.BUSINESS_METRIC, 33_000L, period, state)

    private fun success(
        period: MetricPeriod,
        evaluationDate: String,
        salesYen: Long?,
        expenses: List<MetricExpenseVisibilitySource> = emptyList(),
    ): BusinessMetricUiState.Success {
        val snapshot = BusinessMetricSourceSnapshot(
            period = period,
            reports = salesYen?.let {
                listOf(MetricDailyReportSource("report-1", period.startDate, it, 1L))
            }.orEmpty(),
            expenseVisibility = expenses,
            evaluationDate = LocalDate.parse(evaluationDate),
            calculatedAt = Instant.parse("2026-08-01T00:00:00Z"),
        )
        val calculation = BusinessMetricCalculationCoordinator.calculate(snapshot)
            as BusinessMetricCalculationResult.Success
        return BusinessMetricUiState.Success(calculation.report)
    }

    private fun metricExpense(
        id: String,
        date: LocalDate,
        amount: Long,
        isCancelled: Boolean = false,
    ) = MetricExpenseVisibilitySource(
        expense = MetricExpenseSource(
            id = id,
            expenseDate = date,
            category = MetricExpenseCategory.OTHER_EXPENSE,
            amountYen = amount,
        ),
        isCancelled = isCancelled,
    )
}
