package com.warun.accounting.ui.model

import com.warun.accounting.data.metrics.BusinessMetricSnapshotMappingFailure
import com.warun.accounting.data.metrics.BusinessMetricSnapshotResult
import com.warun.accounting.domain.metrics.BusinessMetricCalculationCoordinator
import com.warun.accounting.domain.metrics.BusinessMetricCalculationResult
import com.warun.accounting.domain.metrics.BusinessMetricInputAssemblyResult
import com.warun.accounting.domain.metrics.BusinessMetricReport
import com.warun.accounting.ui.BalancePeriod
import com.warun.accounting.ui.BusinessMetricComparison
import com.warun.accounting.ui.compareBusinessMetrics
import com.warun.accounting.ui.expectedBusinessMetricDifferences

sealed interface BusinessMetricUiState {
    data object Loading : BusinessMetricUiState

    data class Success(
        val report: BusinessMetricReport,
        val comparison: BusinessMetricComparison? = null,
    ) : BusinessMetricUiState

    data class MappingFailure(
        val failure: BusinessMetricSnapshotMappingFailure,
    ) : BusinessMetricUiState

    data class DataAccessFailure(
        val exceptionType: String,
        val message: String?,
    ) : BusinessMetricUiState

    data class AssemblyFailure(
        val result: BusinessMetricInputAssemblyResult,
    ) : BusinessMetricUiState

    data class CalculationFailure(
        val exceptionType: String,
        val message: String?,
    ) : BusinessMetricUiState
}

internal fun BusinessMetricSnapshotResult.toBusinessMetricUiState(): BusinessMetricUiState =
    when (this) {
        is BusinessMetricSnapshotResult.Success ->
            when (val calculation = BusinessMetricCalculationCoordinator.calculate(snapshot)) {
                is BusinessMetricCalculationResult.Success ->
                    BusinessMetricUiState.Success(
                        report = calculation.report,
                        comparison = comparisonSource?.let { source ->
                            val oldSummary = com.warun.accounting.ui.buildBalanceSummary(
                                reports = source.reports,
                                expenses = source.activeExpenses,
                                period = BalancePeriod(
                                    start = snapshot.period.startDate,
                                    end = snapshot.period.endDateInclusive,
                                ),
                                cancelledExpenseKeys = source.cancelledExpenseKeys,
                            )
                            compareBusinessMetrics(
                                old = oldSummary,
                                newReport = calculation.report,
                                expectedDifferences = expectedBusinessMetricDifferences(
                                    source = source,
                                    period = snapshot.period,
                                    old = oldSummary,
                                    newReport = calculation.report,
                                ),
                            )
                        },
                    )
                is BusinessMetricCalculationResult.AssemblyFailure ->
                    BusinessMetricUiState.AssemblyFailure(calculation.result)
                is BusinessMetricCalculationResult.CalculationFailure ->
                    BusinessMetricUiState.CalculationFailure(
                        exceptionType = calculation.exceptionType,
                        message = calculation.message,
                    )
            }
        is BusinessMetricSnapshotResult.MappingFailure ->
            BusinessMetricUiState.MappingFailure(failure)
        is BusinessMetricSnapshotResult.DataAccessFailure ->
            BusinessMetricUiState.DataAccessFailure(
                exceptionType = exceptionType,
                message = message,
            )
    }
