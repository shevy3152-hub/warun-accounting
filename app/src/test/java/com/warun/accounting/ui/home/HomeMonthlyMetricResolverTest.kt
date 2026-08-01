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

class HomeMonthlyMetricResolverTest {
    @Test
    fun availableUsesRecordedSales() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))

        val result = resolveSales(period, success(period, "2026-08-01", salesYen = 12_000L))

        assertEquals(12_000L, result.amountYen)
        assertNull(result.unavailableText)
        assertTrue(result.statusText.contains(MetricAvailability.AVAILABLE.name))
    }

    @Test
    fun partialSalesShowsRecordedValueAndCurrentMonthStatus() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))

        val result = resolveSales(period, success(period, "2026-08-01", salesYen = 15_000L))

        assertEquals(15_000L, result.amountYen)
        assertTrue(result.statusText.contains(MetricAvailability.PARTIAL_DATA.name))
        assertTrue(result.statusText.contains("月途中"))
    }

    @Test
    fun nullSalesRemainsUnavailableInsteadOfBecomingLegacyOrZero() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))

        val result = resolveSales(period, success(period, "2026-08-01", salesYen = null))

        assertNull(result.amountYen)
        assertEquals("データ不足", result.unavailableText)
        assertTrue(result.statusText.contains("SALES_SOURCE"))
    }

    @Test
    fun availableExpensesUsesRecordedExpensesRatherThanSales() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 12_000L,
            expenseYen = 4_000L,
        )

        val result = resolveExpenses(period, state)

        assertEquals(4_000L, result.amountYen)
        assertTrue(result.statusText.contains(MetricAvailability.AVAILABLE.name))
    }

    @Test
    fun partialExpensesShowsRecordedValueAndCurrentMonthStatus() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 12_000L,
            expenseYen = 4_000L,
        )

        val result = resolveExpenses(period, state)

        assertEquals(4_000L, result.amountYen)
        assertTrue(result.statusText.contains(MetricAvailability.PARTIAL_DATA.name))
        assertTrue(result.statusText.contains("月途中"))
    }

    @Test
    fun nullExpensesRemainsUnavailableInsteadOfBecomingLegacyOrZero() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 12_000L,
            expenseYen = null,
        )

        val result = resolveExpenses(period, state)

        assertNull(result.amountYen)
        assertEquals("データ不足", result.unavailableText)
        assertTrue(result.statusText.contains("EXPENSE_SOURCE"))
    }

    @Test
    fun loadingDoesNotFallbackEitherCardToLegacy() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))

        val sales = resolveSales(period, BusinessMetricUiState.Loading)
        val expenses = resolveExpenses(period, BusinessMetricUiState.Loading)

        assertNull(sales.amountYen)
        assertNull(expenses.amountYen)
        assertEquals("読み込み中", expenses.unavailableText)
    }

    @Test
    fun failureDoesNotFallbackEitherCardToLegacy() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val state = BusinessMetricUiState.DataAccessFailure(
            exceptionType = "test.Failure",
            message = "unavailable",
        )

        val sales = resolveSales(period, state)
        val expenses = resolveExpenses(period, state)

        assertNull(sales.amountYen)
        assertNull(expenses.amountYen)
        assertEquals("取得できません", expenses.unavailableText)
    }

    @Test
    fun legacySwitchUsesEachExplicitRollbackValue() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))

        val sales = resolveHomeMonthlySales(
            source = HomeMonthlyMetricSource.LEGACY,
            legacyYen = 99_000L,
            expectedPeriod = period,
            state = BusinessMetricUiState.Loading,
        )
        val expenses = resolveHomeMonthlyExpenses(
            source = HomeMonthlyMetricSource.LEGACY,
            legacyYen = 33_000L,
            expectedPeriod = period,
            state = BusinessMetricUiState.Loading,
        )

        assertEquals(99_000L, sales.amountYen)
        assertEquals(33_000L, expenses.amountYen)
        assertTrue(expenses.statusText.contains("LEGACY"))
    }

    private fun resolveSales(
        period: MetricPeriod.Monthly,
        state: BusinessMetricUiState,
    ) = resolveHomeMonthlySales(
        source = HomeMonthlyMetricSource.BUSINESS_METRIC,
        legacyYen = 99_000L,
        expectedPeriod = period,
        state = state,
    )

    private fun resolveExpenses(
        period: MetricPeriod.Monthly,
        state: BusinessMetricUiState,
    ) = resolveHomeMonthlyExpenses(
        source = HomeMonthlyMetricSource.BUSINESS_METRIC,
        legacyYen = 33_000L,
        expectedPeriod = period,
        state = state,
    )

    private fun success(
        period: MetricPeriod.Monthly,
        evaluationDate: String,
        salesYen: Long?,
        expenseYen: Long? = null,
    ): BusinessMetricUiState.Success {
        val snapshot = BusinessMetricSourceSnapshot(
            period = period,
            reports = salesYen?.let { sales ->
                listOf(
                    MetricDailyReportSource(
                        id = "report-1",
                        reportDate = period.startDate,
                        salesYen = sales,
                        customerCount = 1L,
                    ),
                )
            }.orEmpty(),
            expenseVisibility = expenseYen?.let { expense ->
                listOf(
                    MetricExpenseVisibilitySource(
                        expense = MetricExpenseSource(
                            id = "expense-1",
                            expenseDate = period.startDate,
                            category = MetricExpenseCategory.OTHER_EXPENSE,
                            amountYen = expense,
                        ),
                        isCancelled = false,
                    ),
                )
            }.orEmpty(),
            evaluationDate = LocalDate.parse(evaluationDate),
            calculatedAt = Instant.parse("2026-08-01T00:00:00Z"),
        )
        val calculation = BusinessMetricCalculationCoordinator.calculate(snapshot)
            as BusinessMetricCalculationResult.Success
        return BusinessMetricUiState.Success(calculation.report)
    }
}
