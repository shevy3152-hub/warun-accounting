package com.warun.accounting.ui

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.metrics.BusinessMetricSnapshotProvider
import com.warun.accounting.data.metrics.BusinessMetricSnapshotRequest
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.ui.model.BusinessMetricUiState
import com.warun.accounting.ui.model.toBusinessMetricUiState
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarSummaryMetricTest {
    @Test
    fun selectedReportDateWinsOverCurrentMonthFallback() {
        assertEquals(
            YearMonth.of(2026, 8),
            sidebarSummaryMonth("2026-08-23", YearMonth.of(2026, 9)),
        )
    }

    @Test
    fun invalidOrMissingReportDateUsesCurrentMonthFallback() {
        val fallback = YearMonth.of(2026, 9)
        assertEquals(fallback, sidebarSummaryMonth("not-a-date", fallback))
        assertEquals(fallback, sidebarSummaryMonth(null, fallback))
    }

    @Test
    fun selectedMonthUsesExistingAugustData() = runTest {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val result = BusinessMetricSnapshotProvider(
            MutableStateFlow(listOf(report("2026-08-23", 12_000L))),
            MutableStateFlow(emptyList()),
        ).observe(
            BusinessMetricSnapshotRequest(
                period = period,
                evaluationDate = LocalDate.parse("2026-09-01"),
                calculatedAt = Instant.parse("2026-09-01T00:00:00Z"),
            )
        ).first()

        val success = result as com.warun.accounting.data.metrics.BusinessMetricSnapshotResult.Success
        val state = success.toBusinessMetricUiStateForTest()
        val resolved = resolveSidebarMonthlyMetric(state, period)
        assertTrue(resolved is SidebarMonthlyMetricResolution.Ready)
        assertEquals(12_000L, (resolved as SidebarMonthlyMetricResolution.Ready).salesYen)
        assertEquals(0L, resolved.expensesYen)
    }

    @Test
    fun emptyMonthIsReadyWithZeroValuesInsteadOfFailure() = runTest {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val result = BusinessMetricSnapshotProvider(
            MutableStateFlow(emptyList()),
            MutableStateFlow(emptyList()),
        ).observe(
            BusinessMetricSnapshotRequest(
                period = period,
                evaluationDate = LocalDate.parse("2026-09-01"),
                calculatedAt = Instant.parse("2026-09-01T00:00:00Z"),
            )
        ).first()

        val success = result as com.warun.accounting.data.metrics.BusinessMetricSnapshotResult.Success
        val resolved = resolveSidebarMonthlyMetric(success.toBusinessMetricUiStateForTest(), period)
        assertEquals(
            SidebarMonthlyMetricResolution.Ready(0L, 0L),
            resolved,
        )
    }

    @Test
    fun providerFailureRemainsFailure() {
        val state = BusinessMetricUiState.DataAccessFailure("test", "failed")
        assertEquals(
            SidebarMonthlyMetricResolution.Failure,
            resolveSidebarMonthlyMetric(state, MetricPeriod.Monthly(YearMonth.of(2026, 8))),
        )
    }

    @Test
    fun oldMonthlyResultIsNotUsedAfterPeriodChanges() = runTest {
        val oldPeriod = MetricPeriod.Monthly(YearMonth.of(2026, 8))
        val state = BusinessMetricUiState.Success(
            report = testBusinessMetricReport(oldPeriod, sales = 12_000L).report,
        )
        assertEquals(
            SidebarMonthlyMetricResolution.Loading,
            resolveSidebarMonthlyMetric(state, MetricPeriod.Monthly(YearMonth.of(2026, 9))),
        )
    }

    private suspend fun testBusinessMetricReport(
        period: MetricPeriod.Monthly,
        sales: Long,
    ) = (BusinessMetricSnapshotProvider(
        MutableStateFlow(listOf(report(period.month.atDay(23).toString(), sales))),
        MutableStateFlow(emptyList()),
    ).observe(
        BusinessMetricSnapshotRequest(
            period = period,
            evaluationDate = LocalDate.parse("2026-09-01"),
            calculatedAt = Instant.parse("2026-09-01T00:00:00Z"),
        )
    ).first() as com.warun.accounting.data.metrics.BusinessMetricSnapshotResult.Success)
        .toBusinessMetricUiStateForTest()
        .let { it as BusinessMetricUiState.Success }

    private fun report(date: String, sales: Long) = DailyReport(
        id = date,
        reportDate = date,
        status = "completed",
        authorName = null,
        cashSales = sales,
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
        updatedAt = 1L,
    )
}

private fun com.warun.accounting.data.metrics.BusinessMetricSnapshotResult.Success.toBusinessMetricUiStateForTest(): BusinessMetricUiState =
    toBusinessMetricUiState()
