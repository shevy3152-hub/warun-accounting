package com.warun.accounting.ui.home

import com.warun.accounting.domain.metrics.BusinessMetricCalculationCoordinator
import com.warun.accounting.domain.metrics.BusinessMetricCalculationResult
import com.warun.accounting.domain.metrics.BusinessMetricSourceSnapshot
import com.warun.accounting.domain.metrics.MetricAvailability
import com.warun.accounting.domain.metrics.MetricDailyReportSource
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.ui.model.BusinessMetricUiState
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMonthlySalesResolverTest {
    @Test
    fun availableUsesRecordedSales() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))

        val result = resolve(period, success(period, "2026-08-01", 12_000L))

        assertEquals(12_000L, result.amountYen)
        assertNull(result.unavailableText)
        assertTrue(result.statusText.contains(MetricAvailability.AVAILABLE.name))
    }

    @Test
    fun partialShowsRecordedSalesAndCurrentMonthStatus() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))

        val result = resolve(period, success(period, "2026-08-01", 15_000L))

        assertEquals(15_000L, result.amountYen)
        assertTrue(result.statusText.contains(MetricAvailability.PARTIAL_DATA.name))
        assertTrue(result.statusText.contains("月途中"))
    }

    @Test
    fun nullSalesRemainsUnavailableInsteadOfBecomingLegacyOrZero() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))

        val result = resolve(period, success(period, "2026-08-01", null))

        assertNull(result.amountYen)
        assertEquals("データ不足", result.unavailableText)
        assertTrue(result.statusText.contains("SALES_SOURCE"))
    }

    @Test
    fun loadingDoesNotFallbackToLegacy() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))

        val result = resolve(period, BusinessMetricUiState.Loading)

        assertNull(result.amountYen)
        assertEquals("読み込み中", result.unavailableText)
    }

    @Test
    fun failureDoesNotFallbackToLegacy() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val state = BusinessMetricUiState.DataAccessFailure(
            exceptionType = "test.Failure",
            message = "unavailable",
        )

        val result = resolve(period, state)

        assertNull(result.amountYen)
        assertEquals("取得できません", result.unavailableText)
    }

    @Test
    fun legacySwitchUsesExplicitRollbackValue() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))

        val result = resolveHomeMonthlySales(
            source = HomeMonthlySalesSource.LEGACY,
            legacyYen = 99_000L,
            expectedPeriod = period,
            state = BusinessMetricUiState.Loading,
        )

        assertEquals(99_000L, result.amountYen)
        assertTrue(result.statusText.contains("LEGACY"))
    }

    private fun resolve(
        period: MetricPeriod.Monthly,
        state: BusinessMetricUiState,
    ) = resolveHomeMonthlySales(
        source = HomeMonthlySalesSource.BUSINESS_METRIC,
        legacyYen = 99_000L,
        expectedPeriod = period,
        state = state,
    )

    private fun success(
        period: MetricPeriod.Monthly,
        evaluationDate: String,
        salesYen: Long?,
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
            expenseVisibility = emptyList(),
            evaluationDate = LocalDate.parse(evaluationDate),
            calculatedAt = Instant.parse("2026-08-01T00:00:00Z"),
        )
        val calculation = BusinessMetricCalculationCoordinator.calculate(snapshot)
            as BusinessMetricCalculationResult.Success
        return BusinessMetricUiState.Success(calculation.report)
    }
}
