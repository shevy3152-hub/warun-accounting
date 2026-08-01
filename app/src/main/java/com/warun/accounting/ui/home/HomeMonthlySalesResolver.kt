package com.warun.accounting.ui.home

import com.warun.accounting.domain.metrics.MetricAvailability
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.ui.model.BusinessMetricUiState

internal enum class HomeMonthlySalesSource {
    BUSINESS_METRIC,
    LEGACY,
}

internal data class HomeMonthlySalesPresentation(
    val amountYen: Long?,
    val unavailableText: String?,
    val statusText: String,
) {
    init {
        require((amountYen != null) xor (unavailableText != null))
    }
}

internal fun resolveHomeMonthlySales(
    source: HomeMonthlySalesSource,
    legacyYen: Long,
    expectedPeriod: MetricPeriod.Monthly,
    state: BusinessMetricUiState,
): HomeMonthlySalesPresentation {
    if (source == HomeMonthlySalesSource.LEGACY) {
        return HomeMonthlySalesPresentation(
            amountYen = legacyYen,
            unavailableText = null,
            statusText = "LEGACY / 旧Dashboard集計",
        )
    }

    return when (state) {
        BusinessMetricUiState.Loading -> unavailable(
            text = "読み込み中",
            status = "BusinessMetric / Loading / 月途中",
        )

        is BusinessMetricUiState.Success -> {
            if (state.report.period != expectedPeriod) {
                unavailable(
                    text = "読み込み中",
                    status = "BusinessMetric / 期間更新中 / 月途中",
                )
            } else {
                val metric = state.report.recordedSales
                val statusParts = buildList {
                    add("RECORDED")
                    add(metric.availability.name)
                    if (metric.availability == MetricAvailability.PARTIAL_DATA || metric.value == null) {
                        add("月途中")
                    }
                    if (metric.missingInputs.isNotEmpty()) {
                        add("不足: ${metric.missingInputs.joinToString { it.name }}")
                    }
                }
                val amount = metric.value?.yen
                if (amount != null) {
                    HomeMonthlySalesPresentation(
                        amountYen = amount,
                        unavailableText = null,
                        statusText = statusParts.joinToString(" / "),
                    )
                } else {
                    unavailable(
                        text = when (metric.availability) {
                            MetricAvailability.INSUFFICIENT_DATA -> "データ不足"
                            MetricAvailability.NOT_AVAILABLE -> "算出対象外"
                            MetricAvailability.AVAILABLE,
                            MetricAvailability.PARTIAL_DATA,
                            -> error("A value is required for ${metric.availability}")
                        },
                        status = statusParts.joinToString(" / "),
                    )
                }
            }
        }

        is BusinessMetricUiState.MappingFailure,
        is BusinessMetricUiState.DataAccessFailure,
        is BusinessMetricUiState.AssemblyFailure,
        is BusinessMetricUiState.CalculationFailure,
        -> unavailable(
            text = "取得できません",
            status = "BusinessMetric / Failure / 月途中",
        )
    }
}

private fun unavailable(text: String, status: String) = HomeMonthlySalesPresentation(
    amountYen = null,
    unavailableText = text,
    statusText = status,
)
