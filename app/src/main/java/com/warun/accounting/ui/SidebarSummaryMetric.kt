package com.warun.accounting.ui

import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.domain.metrics.MetricValue
import com.warun.accounting.ui.model.BusinessMetricUiState
import java.time.LocalDate
import java.time.YearMonth

internal fun sidebarSummaryMonth(
    reportDate: String?,
    fallbackMonth: YearMonth,
): YearMonth = reportDate
    ?.let { value -> runCatching { YearMonth.from(LocalDate.parse(value)) }.getOrNull() }
    ?: fallbackMonth

internal sealed interface SidebarMonthlyMetricResolution {
    data object Loading : SidebarMonthlyMetricResolution
    data object Failure : SidebarMonthlyMetricResolution

    data class Ready(
        val salesYen: Long?,
        val expensesYen: Long?,
    ) : SidebarMonthlyMetricResolution
}

internal fun resolveSidebarMonthlyMetric(
    state: BusinessMetricUiState,
    expectedPeriod: MetricPeriod.Monthly,
): SidebarMonthlyMetricResolution = when (state) {
    BusinessMetricUiState.Loading -> SidebarMonthlyMetricResolution.Loading
    is BusinessMetricUiState.Success -> {
        if (state.report.period != expectedPeriod) {
            SidebarMonthlyMetricResolution.Loading
        } else {
            val source = state.report.recordedSales.sourceSummary
            SidebarMonthlyMetricResolution.Ready(
                salesYen = (state.report.recordedSales.value as? MetricValue.Amount)?.yen
                    ?: 0L.takeIf { source.salesSourceCount == 0 },
                expensesYen = (state.report.recordedExpenses.value as? MetricValue.Amount)?.yen
                    ?: 0L.takeIf { source.recordedExpenseSourceCount == 0 },
            )
        }
    }

    is BusinessMetricUiState.MappingFailure,
    is BusinessMetricUiState.DataAccessFailure,
    is BusinessMetricUiState.AssemblyFailure,
    is BusinessMetricUiState.CalculationFailure,
    -> SidebarMonthlyMetricResolution.Failure
}
