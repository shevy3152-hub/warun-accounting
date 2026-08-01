package com.warun.accounting.ui.model

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseVisibilityRecord
import com.warun.accounting.data.metrics.BusinessMetricSnapshotProvider
import com.warun.accounting.data.metrics.BusinessMetricSnapshotMappingFailure
import com.warun.accounting.data.metrics.BusinessMetricSnapshotMappingFailureReason
import com.warun.accounting.data.metrics.BusinessMetricSnapshotRequest
import com.warun.accounting.data.metrics.BusinessMetricSnapshotResult
import com.warun.accounting.domain.metrics.BusinessMetricSourceSnapshot
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.ui.ComparisonDisposition
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessMetricUiStateTest {
    @Test
    fun mappingFailureDoesNotBecomeSuccessOrZero() {
        val state = BusinessMetricSnapshotResult.MappingFailure(
            BusinessMetricSnapshotMappingFailure(
                reason = BusinessMetricSnapshotMappingFailureReason.UNKNOWN_EXPENSE_CATEGORY,
                recordId = "expense-1",
                originalValue = "unknown",
            ),
        ).toBusinessMetricUiState()

        assertTrue(state is BusinessMetricUiState.MappingFailure)
    }

    @Test
    fun dataAccessFailureRemainsDistinct() {
        val state = BusinessMetricSnapshotResult.DataAccessFailure(
            exceptionType = "java.lang.IllegalStateException",
            message = "unavailable",
        ).toBusinessMetricUiState()

        assertTrue(state is BusinessMetricUiState.DataAccessFailure)
    }

    @Test
    fun successRunsAssemblerAndCalculator() {
        val state = BusinessMetricSnapshotResult.Success(
            BusinessMetricSourceSnapshot(
                period = MetricPeriod.Daily(LocalDate.parse("2026-07-01")),
                reports = emptyList(),
                expenseVisibility = emptyList(),
                evaluationDate = LocalDate.parse("2026-07-29"),
                calculatedAt = Instant.parse("2026-07-29T00:00:00Z"),
            ),
        ).toBusinessMetricUiState()

        assertTrue(state is BusinessMetricUiState.Success)
    }

    @Test
    fun providerLegacyFoodExpectedDifferenceReachesUiStateWithoutUnexpectedDifference() = runTest {
        val date = LocalDate.parse("2026-07-01")
        val provider = BusinessMetricSnapshotProvider(
            MutableStateFlow(listOf(report(date.toString(), legacyFood = 2_000L))),
            MutableStateFlow<List<ExpenseVisibilityRecord>>(emptyList()),
        )

        val state = provider.observe(
            BusinessMetricSnapshotRequest(
                period = MetricPeriod.Daily(date),
                evaluationDate = LocalDate.parse("2026-07-29"),
                calculatedAt = Instant.parse("2026-07-29T00:00:00Z"),
            ),
        ).first().toBusinessMetricUiState() as BusinessMetricUiState.Success
        val comparison = requireNotNull(state.comparison)

        assertEquals(ComparisonDisposition.EXPECTED_DIFFERENCE, comparison.expenses.disposition)
        assertTrue(comparison.expenses.reason?.contains("legacy") == true)
        assertTrue(comparison.unexpectedDifferences.isEmpty())
    }

    private fun report(
        date: String,
        legacyFood: Long,
    ) = DailyReport(
        id = "report-$date",
        reportDate = date,
        status = DailyReportStatus.Completed,
        authorName = null,
        cashSales = 10_000L,
        cardSales = 0L,
        qrSales = 0L,
        accountsReceivableSales = 0L,
        otherSales = 0L,
        foodPurchases = legacyFood,
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
        customerCount = 1,
        groupCount = 1,
        memo = null,
        createdAt = 1L,
        updatedAt = 1L,
        hasActualClosingCash = false,
    )
}
