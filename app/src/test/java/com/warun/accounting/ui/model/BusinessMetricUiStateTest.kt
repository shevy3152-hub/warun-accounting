package com.warun.accounting.ui.model

import com.warun.accounting.data.metrics.BusinessMetricSnapshotMappingFailure
import com.warun.accounting.data.metrics.BusinessMetricSnapshotMappingFailureReason
import com.warun.accounting.data.metrics.BusinessMetricSnapshotResult
import com.warun.accounting.domain.metrics.BusinessMetricSourceSnapshot
import com.warun.accounting.domain.metrics.MetricPeriod
import java.time.Instant
import java.time.LocalDate
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
}
