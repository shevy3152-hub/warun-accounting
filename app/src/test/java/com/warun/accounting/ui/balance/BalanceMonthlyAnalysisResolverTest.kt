package com.warun.accounting.ui.balance

import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.domain.metrics.BusinessMetricCalculationCoordinator
import com.warun.accounting.domain.metrics.BusinessMetricCalculationResult
import com.warun.accounting.domain.metrics.BusinessMetricSourceSnapshot
import com.warun.accounting.domain.metrics.MetricDailyReportSource
import com.warun.accounting.domain.metrics.MetricExpenseCategory
import com.warun.accounting.domain.metrics.MetricExpenseSource
import com.warun.accounting.domain.metrics.MetricExpenseVisibilitySource
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.ui.BalancePeriod
import com.warun.accounting.ui.BalancePeriodMode
import com.warun.accounting.ui.model.BusinessMetricUiState
import com.warun.accounting.ui.model.buildBusinessAnalysisSummary
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceMonthlyAnalysisResolverTest {
    @Test
    fun onlyCalendarMonthModesCreateBusinessMetricPeriod() {
        val period = BalancePeriod(
            start = LocalDate.parse("2026-07-01"),
            end = LocalDate.parse("2026-07-31"),
        )

        assertEquals(
            MetricPeriod.Monthly(YearMonth.of(2026, 7)),
            resolveBalanceMonthlyMetricPeriod(BalancePeriodMode.ThisMonth, period),
        )
        assertEquals(
            MetricPeriod.Monthly(YearMonth.of(2026, 7)),
            resolveBalanceMonthlyMetricPeriod(BalancePeriodMode.LastMonth, period),
        )
        assertNull(resolveBalanceMonthlyMetricPeriod(BalancePeriodMode.Today, period))
        assertNull(resolveBalanceMonthlyMetricPeriod(BalancePeriodMode.Yesterday, period))
        assertNull(resolveBalanceMonthlyMetricPeriod(BalancePeriodMode.Custom, period))
    }

    @Test
    fun matchingMonthlyValuesReachAllThreePresentations() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val legacy = buildBusinessAnalysisSummary(
            salesTotal = 100_000L,
            expenseTotal = 30_000L,
            periodExpenses = listOf(
                legacyExpense("food", ExpenseCategory.FoodPurchase, 20_000L),
                legacyExpense("alcohol", ExpenseCategory.AlcoholPurchase, 10_000L),
            ),
        )
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 100_000L,
            expenses = listOf(
                metricExpense("food", MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                metricExpense("alcohol", MetricExpenseCategory.ALCOHOL_PURCHASE, 10_000L),
            ),
        )

        val result = resolve(period, legacy, state)

        assertEquals(legacy.estimatedCost, result.referenceCost.value)
        assertEquals(legacy.estimatedGrossProfit, result.approximateGrossProfit.value)
        assertBigDecimalEquals(legacy.estimatedCostRate, result.referenceCostRatePercent.value)
    }

    @Test
    fun nullMetricsRemainUnavailableWithoutLegacyOrZeroFallback() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val legacy = legacySummary()
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 100_000L,
            expenses = emptyList(),
        )

        val result = resolve(period, legacy, state)

        assertNull(result.referenceCost.value)
        assertNull(result.approximateGrossProfit.value)
        assertNull(result.referenceCostRatePercent.value)
        assertEquals("データ不足", result.referenceCost.unavailableText)
        assertTrue(result.referenceCost.statusText.contains("REFERENCE_COST_SOURCE"))
    }

    @Test
    fun partialDataIsShownForAllThreeMetrics() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 100_000L,
            expenses = listOf(
                metricExpense(
                    "food",
                    MetricExpenseCategory.FOOD_PURCHASE,
                    20_000L,
                    expenseDate = "2026-08-01",
                ),
            ),
        )

        val result = resolve(period, legacySummary(), state)

        listOf(
            result.referenceCost.statusText,
            result.approximateGrossProfit.statusText,
            result.referenceCostRatePercent.statusText,
        ).forEach { status ->
            assertTrue(status.contains("PARTIAL_DATA"))
            assertTrue(status.contains("月途中"))
        }
    }

    @Test
    fun cancelledExpenseIsExcludedFromAllThreeMetrics() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 100_000L,
            expenses = listOf(
                metricExpense("active-food", MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                metricExpense("cancelled-alcohol", MetricExpenseCategory.ALCOHOL_PURCHASE, 10_000L, true),
            ),
        )

        val result = resolve(period, legacySummary(), state)

        assertEquals(20_000L, result.referenceCost.value)
        assertEquals(80_000L, result.approximateGrossProfit.value)
        assertBigDecimalEquals(BigDecimal("20"), result.referenceCostRatePercent.value)
    }

    @Test
    fun loadingAndFailureDoNotUseLegacyValues() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val legacy = legacySummary()
        val loading = resolve(period, legacy, BusinessMetricUiState.Loading)
        val failure = resolve(
            period,
            legacy,
            BusinessMetricUiState.DataAccessFailure("test.Failure", "unavailable"),
        )

        assertNull(loading.referenceCost.value)
        assertEquals("読み込み中", loading.referenceCost.unavailableText)
        assertNull(failure.referenceCost.value)
        assertEquals("取得できません", failure.referenceCost.unavailableText)
    }

    @Test
    fun legacySwitchUsesExplicitOldAnalysisValues() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val legacy = legacySummary()

        val result = resolveBalanceMonthlyAnalysis(
            source = BalanceMonthlyAnalysisSource.LEGACY,
            legacy = legacy,
            expectedPeriod = period,
            state = BusinessMetricUiState.Loading,
        )

        assertEquals("概算原価", result.referenceCostLabel)
        assertEquals(legacy.estimatedCost, result.referenceCost.value)
        assertEquals(legacy.estimatedGrossProfit, result.approximateGrossProfit.value)
        assertBigDecimalEquals(legacy.estimatedCostRate, result.referenceCostRatePercent.value)
    }

    private fun resolve(
        period: MetricPeriod.Monthly,
        legacy: com.warun.accounting.ui.model.BusinessAnalysisSummary,
        state: BusinessMetricUiState,
    ) = resolveBalanceMonthlyAnalysis(
        source = BalanceMonthlyAnalysisSource.BUSINESS_METRIC,
        legacy = legacy,
        expectedPeriod = period,
        state = state,
    )

    private fun legacySummary() = buildBusinessAnalysisSummary(
        salesTotal = 100_000L,
        expenseTotal = 30_000L,
        periodExpenses = listOf(
            legacyExpense("food", ExpenseCategory.FoodPurchase, 20_000L),
            legacyExpense("alcohol", ExpenseCategory.AlcoholPurchase, 10_000L),
        ),
    )

    private fun success(
        period: MetricPeriod.Monthly,
        evaluationDate: String,
        salesYen: Long,
        expenses: List<MetricExpenseVisibilitySource>,
    ): BusinessMetricUiState.Success {
        val snapshot = BusinessMetricSourceSnapshot(
            period = period,
            reports = listOf(
                MetricDailyReportSource(
                    id = "report-1",
                    reportDate = period.startDate,
                    salesYen = salesYen,
                    customerCount = 1L,
                ),
            ),
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
        category: MetricExpenseCategory,
        amountYen: Long,
        isCancelled: Boolean = false,
        expenseDate: String = "2026-07-01",
    ) = MetricExpenseVisibilitySource(
        expense = MetricExpenseSource(
            id = id,
            expenseDate = LocalDate.parse(expenseDate),
            category = category,
            amountYen = amountYen,
        ),
        isCancelled = isCancelled,
    )

    private fun legacyExpense(id: String, category: String, amount: Long) = ExpenseRecord(
        id = id,
        expenseDate = "2026-07-01",
        category = category,
        supplierName = null,
        amount = amount,
        paymentMethod = null,
        memo = null,
        receiptId = null,
        sourceType = "manual",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun assertBigDecimalEquals(expected: BigDecimal?, actual: BigDecimal?) {
        assertTrue(expected != null && actual != null && expected.compareTo(actual) == 0)
    }
}
