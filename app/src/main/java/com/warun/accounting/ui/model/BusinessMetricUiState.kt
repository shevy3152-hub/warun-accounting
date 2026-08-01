package com.warun.accounting.ui.model

import com.warun.accounting.data.metrics.BusinessMetricSnapshotMappingFailure
import com.warun.accounting.data.metrics.BusinessMetricSnapshotResult
import com.warun.accounting.domain.metrics.BusinessMetricCalculationCoordinator
import com.warun.accounting.domain.metrics.BusinessMetricCalculationResult
import com.warun.accounting.domain.metrics.BusinessMetricInputAssemblyResult
import com.warun.accounting.domain.metrics.BusinessMetricReport

sealed interface BusinessMetricUiState {
    data object Loading : BusinessMetricUiState

    data class Success(val report: BusinessMetricReport) : BusinessMetricUiState

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
                    BusinessMetricUiState.Success(calculation.report)
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
