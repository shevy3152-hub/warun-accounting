package com.warun.accounting.domain.metrics

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessMetricCalculationCoordinatorTest {
    @Test
    fun successSnapshotProducesCalculatorReport() {
        val result = BusinessMetricCalculationCoordinator.calculate(snapshot())

        val success = result as BusinessMetricCalculationResult.Success
        assertEquals(1_000L, success.report.recordedSales.value?.yen)
        assertEquals(snapshot().calculatedAt, success.report.calculatedAt)
    }

    @Test
    fun assemblyFailureDoesNotProducePartialReport() {
        val result = BusinessMetricCalculationCoordinator.calculate(
            snapshot(reports = listOf(report(), report("duplicate"))),
        )

        val failure = result as BusinessMetricCalculationResult.AssemblyFailure
        assertTrue(failure.result is BusinessMetricInputAssemblyResult.InconsistentSnapshot)
    }

    @Test
    fun invalidExpenseAmountRemainsAssemblyFailure() {
        val result = BusinessMetricCalculationCoordinator.calculate(
            snapshot(
                expenses = listOf(
                    MetricExpenseVisibilitySource(
                        expense = MetricExpenseSource(
                            id = "expense-1",
                            expenseDate = LocalDate.parse("2026-07-01"),
                            category = MetricExpenseCategory.OTHER_EXPENSE,
                            amountYen = -100L,
                        ),
                        isCancelled = false,
                    ),
                ),
            ),
        )

        val failure = result as BusinessMetricCalculationResult.AssemblyFailure
        assertTrue(failure.result is BusinessMetricInputAssemblyResult.InvalidSourceData)
    }

    @Test
    fun sameInputProducesDeterministicReport() {
        val first = BusinessMetricCalculationCoordinator.calculate(snapshot())
        val second = BusinessMetricCalculationCoordinator.calculate(snapshot())

        assertEquals(first, second)
    }

    private fun snapshot(
        reports: List<MetricDailyReportSource> = listOf(report()),
        expenses: List<MetricExpenseVisibilitySource> = emptyList(),
    ) = BusinessMetricSourceSnapshot(
        period = MetricPeriod.Daily(LocalDate.parse("2026-07-01")),
        reports = reports,
        expenseVisibility = expenses,
        evaluationDate = LocalDate.parse("2026-07-29"),
        calculatedAt = Instant.parse("2026-07-29T00:00:00Z"),
    )

    private fun report(id: String = "report-1") = MetricDailyReportSource(
        id = id,
        reportDate = LocalDate.parse("2026-07-01"),
        salesYen = 1_000L,
        customerCount = 1L,
    )
}
