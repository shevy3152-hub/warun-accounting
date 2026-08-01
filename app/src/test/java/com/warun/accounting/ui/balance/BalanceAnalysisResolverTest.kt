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
import com.warun.accounting.ui.model.BusinessAnalysisSummary
import com.warun.accounting.ui.model.BusinessMetricUiState
import com.warun.accounting.ui.model.breakEvenStatusMessage
import com.warun.accounting.ui.model.buildBusinessAnalysisSummary
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceAnalysisResolverTest {
    @Test
    fun allBalanceModesCreateMatchingBusinessMetricPeriods() {
        val daily = BalancePeriod(LocalDate.parse("2026-07-15"), LocalDate.parse("2026-07-15"))
        val monthly = BalancePeriod(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"))
        val customSingleDay = BalancePeriod(
            LocalDate.parse("2026-07-31"),
            LocalDate.parse("2026-07-31"),
        )
        val customCrossMonth = BalancePeriod(
            LocalDate.parse("2026-07-31"),
            LocalDate.parse("2026-08-02"),
        )

        assertEquals(
            MetricPeriod.Daily(LocalDate.parse("2026-07-15")),
            resolveBalanceMetricPeriod(BalancePeriodMode.Today, daily),
        )
        assertEquals(
            MetricPeriod.Daily(LocalDate.parse("2026-07-15")),
            resolveBalanceMetricPeriod(BalancePeriodMode.Yesterday, daily),
        )
        assertEquals(
            MetricPeriod.Monthly(YearMonth.of(2026, 7)),
            resolveBalanceMetricPeriod(BalancePeriodMode.ThisMonth, monthly),
        )
        assertEquals(
            MetricPeriod.Monthly(YearMonth.of(2026, 7)),
            resolveBalanceMetricPeriod(BalancePeriodMode.LastMonth, monthly),
        )
        assertEquals(
            MetricPeriod.CustomRange(
                LocalDate.parse("2026-07-31"),
                LocalDate.parse("2026-07-31"),
            ),
            resolveBalanceMetricPeriod(BalancePeriodMode.Custom, customSingleDay),
        )
        assertEquals(
            MetricPeriod.CustomRange(
                LocalDate.parse("2026-07-31"),
                LocalDate.parse("2026-08-02"),
            ),
            resolveBalanceMetricPeriod(BalancePeriodMode.Custom, customCrossMonth),
        )
    }

    @Test
    fun matchingDailyValuesReachAllThreePresentations() {
        val period = MetricPeriod.Daily(LocalDate.parse("2026-07-15"))
        val legacy = legacySummary("2026-07-15")
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 100_000L,
            expenses = listOf(
                metricExpense("food", period.startDate, MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                metricExpense("alcohol", period.startDate, MetricExpenseCategory.ALCOHOL_PURCHASE, 10_000L),
            ),
        )

        assertMatchesLegacy(resolve(period, legacy, state), legacy)
    }

    @Test
    fun dailyNullMetricsRemainUnavailableWithoutLegacyOrZeroFallback() {
        val period = MetricPeriod.Daily(LocalDate.parse("2026-07-15"))
        val result = resolve(
            period,
            legacySummary("2026-07-15"),
            success(period, "2026-08-01", salesYen = 100_000L, expenses = emptyList()),
        )

        assertNull(result.referenceCost.value)
        assertNull(result.approximateGrossProfit.value)
        assertNull(result.referenceCostRatePercent.value)
        assertEquals("データ不足", result.referenceCost.unavailableText)
        assertTrue(result.referenceCost.statusText.contains("REFERENCE_COST_SOURCE"))
    }

    @Test
    fun currentDailyPartialStatusIsShownForAllThreeMetrics() {
        val period = MetricPeriod.Daily(LocalDate.parse("2026-08-01"))
        val result = resolve(
            period,
            legacySummary("2026-08-01"),
            success(
                period,
                "2026-08-01",
                salesYen = 100_000L,
                expenses = listOf(
                    metricExpense("food", period.startDate, MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                ),
            ),
        )

        result.statuses().forEach {
            assertTrue(it.contains("PARTIAL_DATA"))
            assertTrue(it.contains("当日途中"))
        }
    }

    @Test
    fun dailyCancelledExpenseIsExcludedFromAllThreeMetrics() {
        val period = MetricPeriod.Daily(LocalDate.parse("2026-07-15"))
        val result = resolve(
            period,
            legacySummary("2026-07-15"),
            success(
                period,
                "2026-08-01",
                salesYen = 100_000L,
                expenses = listOf(
                    metricExpense("active-food", period.startDate, MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                    metricExpense(
                        "cancelled-alcohol",
                        period.startDate,
                        MetricExpenseCategory.ALCOHOL_PURCHASE,
                        10_000L,
                        isCancelled = true,
                    ),
                ),
            ),
        )

        assertEquals(20_000L, result.referenceCost.value)
        assertEquals(80_000L, result.approximateGrossProfit.value)
        assertBigDecimalEquals(BigDecimal("20"), result.referenceCostRatePercent.value)
    }

    @Test
    fun matchingMonthlyValuesRemainConnected() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val legacy = legacySummary("2026-07-01")
        val state = success(
            period = period,
            evaluationDate = "2026-08-01",
            salesYen = 100_000L,
            expenses = listOf(
                metricExpense("food", period.startDate, MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                metricExpense("alcohol", period.startDate, MetricExpenseCategory.ALCOHOL_PURCHASE, 10_000L),
            ),
        )

        assertMatchesLegacy(resolve(period, legacy, state), legacy)
    }

    @Test
    fun currentMonthlyPartialStatusRemainsConnected() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val result = resolve(
            period,
            legacySummary("2026-08-01"),
            success(
                period,
                "2026-08-01",
                salesYen = 100_000L,
                expenses = listOf(
                    metricExpense("food", period.startDate, MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                ),
            ),
        )

        result.statuses().forEach {
            assertTrue(it.contains("PARTIAL_DATA"))
            assertTrue(it.contains("月途中"))
        }
    }

    @Test
    fun completedMonthShowsRecordedFixedCostAndReferenceBreakEven() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val result = resolve(
            period,
            legacySummary("2026-07-01"),
            successFromSources(
                period = period,
                evaluationDate = "2026-08-01",
                reports = listOf(
                    MetricDailyReportSource(
                        id = "report-1",
                        reportDate = period.startDate,
                        salesYen = 100_000L,
                        customerCount = 1L,
                        rentExpenseYen = 10_000L,
                        communicationExpenseYen = 5_000L,
                        accountantFeeExpenseYen = 5_000L,
                        electricityExpenseYen = 4_000L,
                        gasExpenseYen = 3_000L,
                        waterExpenseYen = 3_000L,
                    ),
                ),
                expenses = listOf(
                    metricExpense("food", period.startDate, MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                    metricExpense("alcohol", period.startDate, MetricExpenseCategory.ALCOHOL_PURCHASE, 10_000L),
                ),
            ),
        )

        assertEquals("記録済み固定費相当額", result.fixedCostLabel)
        assertEquals(30_000L, result.fixedCost.value)
        assertEquals(42_858L, result.breakEvenSales.value)
        assertEquals(
            "参考損益分岐売上高を ￥57,142上回っています",
            result.breakEvenStatus.value,
        )
        assertTrue(result.fixedCost.statusText.contains("AVAILABLE"))
        assertTrue(result.breakEvenSales.statusText.contains("AVAILABLE"))
    }

    @Test
    fun incompleteMonthKeepsFixedCostPartialAndBreakEvenUnavailable() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val result = resolve(
            period,
            legacySummary("2026-08-01"),
            success(
                period = period,
                evaluationDate = "2026-08-01",
                salesYen = 100_000L,
                expenses = listOf(
                    metricExpense("food", period.startDate, MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                ),
                rentExpenseYen = 30_000L,
            ),
        )

        assertEquals(30_000L, result.fixedCost.value)
        assertTrue(result.fixedCost.statusText.contains("PARTIAL_DATA"))
        assertTrue(result.fixedCost.statusText.contains("月途中"))
        assertNull(result.breakEvenSales.value)
        assertEquals("算出対象外", result.breakEvenSales.unavailableText)
        assertTrue(result.breakEvenSales.statusText.contains("COMPLETED_CALENDAR_MONTH"))
        assertNull(result.breakEvenStatus.value)
        assertEquals("算出対象外", result.breakEvenStatus.unavailableText)
    }

    @Test
    fun missingFixedCostSourceDoesNotBecomeLegacyOrZero() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val result = resolve(
            period,
            legacySummary("2026-07-01"),
            success(
                period = period,
                evaluationDate = "2026-08-01",
                salesYen = 100_000L,
                expenses = listOf(
                    metricExpense("food", period.startDate, MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                ),
            ),
        )

        assertNull(result.fixedCost.value)
        assertEquals("データ不足", result.fixedCost.unavailableText)
        assertTrue(result.fixedCost.statusText.contains("FIXED_COST_SOURCE"))
        assertNull(result.breakEvenSales.value)
        assertEquals("データ不足", result.breakEvenSales.unavailableText)
    }

    @Test
    fun dailyAndCustomRangeAreIneligibleForReferenceBreakEven() {
        val daily = MetricPeriod.Daily(LocalDate.parse("2026-07-15"))
        val custom = MetricPeriod.CustomRange(
            LocalDate.parse("2026-07-01"),
            LocalDate.parse("2026-07-31"),
        )

        listOf(daily, custom).forEach { period ->
            val result = resolve(
                period,
                legacySummary(period.startDate.toString()),
                success(
                    period = period,
                    evaluationDate = "2026-08-01",
                    salesYen = 100_000L,
                    expenses = listOf(
                        metricExpense("food", period.startDate, MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                    ),
                    rentExpenseYen = 30_000L,
                ),
            )

            assertEquals(30_000L, result.fixedCost.value)
            assertNull(result.breakEvenSales.value)
            assertEquals("算出対象外", result.breakEvenSales.unavailableText)
            assertTrue(result.breakEvenSales.statusText.contains("COMPLETED_CALENDAR_MONTH"))
        }
    }

    @Test
    fun customRangeIncludesBothBoundariesAcrossMonthAndExcludesOutside() {
        val period = MetricPeriod.CustomRange(
            LocalDate.parse("2026-07-31"),
            LocalDate.parse("2026-08-02"),
        )
        val state = successFromSources(
            period = period,
            evaluationDate = "2026-08-10",
            reports = listOf(
                MetricDailyReportSource("before", LocalDate.parse("2026-07-30"), 999_000L, 1L),
                MetricDailyReportSource("start", period.startDate, 40_000L, 1L),
                MetricDailyReportSource("end", period.endDateInclusive, 60_000L, 1L),
                MetricDailyReportSource("after", LocalDate.parse("2026-08-03"), 999_000L, 1L),
            ),
            expenses = listOf(
                metricExpense(
                    "food-start",
                    period.startDate,
                    MetricExpenseCategory.FOOD_PURCHASE,
                    20_000L,
                ),
                metricExpense(
                    "alcohol-end",
                    period.endDateInclusive,
                    MetricExpenseCategory.ALCOHOL_PURCHASE,
                    10_000L,
                ),
                metricExpense(
                    "outside",
                    LocalDate.parse("2026-08-03"),
                    MetricExpenseCategory.FOOD_PURCHASE,
                    999_000L,
                ),
            ),
        )

        val result = resolve(period, legacySummary("2026-07-31"), state)

        assertEquals(30_000L, result.referenceCost.value)
        assertEquals(70_000L, result.approximateGrossProfit.value)
        assertBigDecimalEquals(BigDecimal("30"), result.referenceCostRatePercent.value)
    }

    @Test
    fun customRangeExcludesCancelledExpense() {
        val period = MetricPeriod.CustomRange(
            LocalDate.parse("2026-07-31"),
            LocalDate.parse("2026-08-02"),
        )
        val state = success(
            period = period,
            evaluationDate = "2026-08-10",
            salesYen = 100_000L,
            expenses = listOf(
                metricExpense("active-food", period.startDate, MetricExpenseCategory.FOOD_PURCHASE, 20_000L),
                metricExpense(
                    "cancelled-alcohol",
                    period.endDateInclusive,
                    MetricExpenseCategory.ALCOHOL_PURCHASE,
                    10_000L,
                    isCancelled = true,
                ),
            ),
        )

        val result = resolve(period, legacySummary("2026-07-31"), state)

        assertEquals(20_000L, result.referenceCost.value)
        assertEquals(80_000L, result.approximateGrossProfit.value)
        assertBigDecimalEquals(BigDecimal("20"), result.referenceCostRatePercent.value)
    }

    @Test
    fun customRangeNullMetricsRemainUnavailableWithoutLegacyOrZeroFallback() {
        val period = MetricPeriod.CustomRange(
            LocalDate.parse("2026-07-31"),
            LocalDate.parse("2026-08-02"),
        )
        val result = resolve(
            period,
            legacySummary("2026-07-31"),
            success(period, "2026-08-10", salesYen = 100_000L, expenses = emptyList()),
        )

        assertNull(result.referenceCost.value)
        assertNull(result.approximateGrossProfit.value)
        assertNull(result.referenceCostRatePercent.value)
        assertEquals("データ不足", result.referenceCost.unavailableText)
        assertTrue(result.referenceCost.statusText.contains("REFERENCE_COST_SOURCE"))
    }

    @Test
    fun loadingFailureAndLegacySwitchRemainDistinct() {
        val period = MetricPeriod.Daily(LocalDate.parse("2026-07-15"))
        val legacy = legacySummary("2026-07-15")
        val loading = resolve(period, legacy, BusinessMetricUiState.Loading)
        val failure = resolve(
            period,
            legacy,
            BusinessMetricUiState.DataAccessFailure("test.Failure", "unavailable"),
        )
        val rollback = resolveBalanceAnalysis(
            source = BalanceAnalysisSource.LEGACY,
            legacy = legacy,
            expectedPeriod = period,
            state = BusinessMetricUiState.Loading,
        )

        assertNull(loading.referenceCost.value)
        assertEquals("読み込み中", loading.referenceCost.unavailableText)
        assertNull(loading.fixedCost.value)
        assertNull(loading.breakEvenSales.value)
        assertNull(failure.referenceCost.value)
        assertEquals("取得できません", failure.referenceCost.unavailableText)
        assertNull(failure.fixedCost.value)
        assertNull(failure.breakEvenStatus.value)
        assertEquals("概算原価", rollback.referenceCostLabel)
        assertMatchesLegacy(rollback, legacy)
        assertEquals(legacy.simpleFixedCost, rollback.fixedCost.value)
        assertEquals(legacy.estimatedBreakEvenSales, rollback.breakEvenSales.value)
        assertEquals(legacy.breakEvenStatusMessage(), rollback.breakEvenStatus.value)
    }

    private fun resolve(
        period: MetricPeriod,
        legacy: BusinessAnalysisSummary,
        state: BusinessMetricUiState,
    ) = resolveBalanceAnalysis(
        source = BalanceAnalysisSource.BUSINESS_METRIC,
        legacy = legacy,
        expectedPeriod = period,
        state = state,
    )

    private fun success(
        period: MetricPeriod,
        evaluationDate: String,
        salesYen: Long,
        expenses: List<MetricExpenseVisibilitySource>,
        rentExpenseYen: Long = 0L,
    ): BusinessMetricUiState.Success = successFromSources(
        period = period,
        evaluationDate = evaluationDate,
        reports = listOf(
            MetricDailyReportSource(
                id = "report-1",
                reportDate = period.startDate,
                salesYen = salesYen,
                customerCount = 1L,
                rentExpenseYen = rentExpenseYen,
            ),
        ),
        expenses = expenses,
    )

    private fun successFromSources(
        period: MetricPeriod,
        evaluationDate: String,
        reports: List<MetricDailyReportSource>,
        expenses: List<MetricExpenseVisibilitySource>,
    ): BusinessMetricUiState.Success {
        val snapshot = BusinessMetricSourceSnapshot(
            period = period,
            reports = reports,
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
        category: MetricExpenseCategory,
        amountYen: Long,
        isCancelled: Boolean = false,
    ) = MetricExpenseVisibilitySource(
        expense = MetricExpenseSource(id, date, category, amountYen),
        isCancelled = isCancelled,
    )

    private fun legacySummary(date: String) = buildBusinessAnalysisSummary(
        salesTotal = 100_000L,
        expenseTotal = 40_000L,
        periodExpenses = listOf(
            legacyExpense("food", date, ExpenseCategory.FoodPurchase, 20_000L),
            legacyExpense("alcohol", date, ExpenseCategory.AlcoholPurchase, 10_000L),
            legacyExpense("other", date, ExpenseCategory.OtherExpense, 10_000L),
        ),
    )

    private fun legacyExpense(id: String, date: String, category: String, amount: Long) = ExpenseRecord(
        id = id,
        expenseDate = date,
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

    private fun BalanceAnalysisPresentation.statuses() = listOf(
        referenceCost.statusText,
        approximateGrossProfit.statusText,
        referenceCostRatePercent.statusText,
    )

    private fun assertMatchesLegacy(
        actual: BalanceAnalysisPresentation,
        legacy: BusinessAnalysisSummary,
    ) {
        assertEquals(legacy.estimatedCost, actual.referenceCost.value)
        assertEquals(legacy.estimatedGrossProfit, actual.approximateGrossProfit.value)
        assertBigDecimalEquals(legacy.estimatedCostRate, actual.referenceCostRatePercent.value)
    }

    private fun assertBigDecimalEquals(expected: BigDecimal?, actual: BigDecimal?) {
        assertTrue(expected != null && actual != null && expected.compareTo(actual) == 0)
    }
}
