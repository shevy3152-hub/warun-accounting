package com.warun.accounting.ui.balance

import com.warun.accounting.domain.metrics.AmountMetricResult
import com.warun.accounting.domain.metrics.BusinessMetricResult
import com.warun.accounting.domain.metrics.MetricAvailability
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.domain.metrics.MetricWarning
import com.warun.accounting.domain.metrics.RatioMetricResult
import com.warun.accounting.ui.BalancePeriod
import com.warun.accounting.ui.BalancePeriodMode
import com.warun.accounting.ui.model.BusinessAnalysisSummary
import com.warun.accounting.ui.model.BusinessMetricUiState
import java.math.BigDecimal
import java.time.YearMonth

internal enum class BalanceAnalysisSource {
    BUSINESS_METRIC,
    LEGACY,
}

internal data class BalanceMetricPresentation<T>(
    val value: T?,
    val unavailableText: String?,
    val statusText: String,
) {
    init {
        require((value != null) xor (unavailableText != null))
    }
}

internal data class BalanceAnalysisPresentation(
    val referenceCostLabel: String,
    val referenceCostRateLabel: String,
    val referenceCost: BalanceMetricPresentation<Long>,
    val approximateGrossProfit: BalanceMetricPresentation<Long>,
    val referenceCostRatePercent: BalanceMetricPresentation<BigDecimal>,
)

internal fun resolveBalanceMetricPeriod(
    mode: BalancePeriodMode,
    period: BalancePeriod,
): MetricPeriod? = when (mode) {
    BalancePeriodMode.Today,
    BalancePeriodMode.Yesterday,
    -> MetricPeriod.Daily(period.start)

    BalancePeriodMode.ThisMonth,
    BalancePeriodMode.LastMonth,
    -> MetricPeriod.Monthly(YearMonth.from(period.start))

    BalancePeriodMode.Custom -> MetricPeriod.CustomRange(
        startDate = period.start,
        endDateInclusive = period.end,
    )
}

internal fun resolveBalanceAnalysis(
    source: BalanceAnalysisSource,
    legacy: BusinessAnalysisSummary,
    expectedPeriod: MetricPeriod,
    state: BusinessMetricUiState,
): BalanceAnalysisPresentation {
    if (source == BalanceAnalysisSource.LEGACY) {
        val status = "LEGACY / 旧BusinessAnalysis"
        return BalanceAnalysisPresentation(
            referenceCostLabel = "概算原価",
            referenceCostRateLabel = "概算原価率",
            referenceCost = legacyMetric(legacy.estimatedCost, "計算不可", status),
            approximateGrossProfit = legacyMetric(legacy.estimatedGrossProfit, "計算不可", status),
            referenceCostRatePercent = legacyMetric(legacy.estimatedCostRate, "—", status),
        )
    }

    return when (state) {
        BusinessMetricUiState.Loading -> unavailableAnalysis("読み込み中", "BusinessMetric / Loading")
        is BusinessMetricUiState.Success -> {
            if (state.report.period != expectedPeriod) {
                unavailableAnalysis("読み込み中", "BusinessMetric / 期間更新中")
            } else {
                BalanceAnalysisPresentation(
                    referenceCostLabel = "参考原価",
                    referenceCostRateLabel = "参考原価率",
                    referenceCost = amountPresentation(state.report.referenceCost),
                    approximateGrossProfit = amountPresentation(state.report.approximateGrossProfit),
                    referenceCostRatePercent = ratioPresentation(state.report.referenceCostRate),
                )
            }
        }

        is BusinessMetricUiState.MappingFailure,
        is BusinessMetricUiState.DataAccessFailure,
        is BusinessMetricUiState.AssemblyFailure,
        is BusinessMetricUiState.CalculationFailure,
        -> unavailableAnalysis("取得できません", "BusinessMetric / Failure")
    }
}

private fun amountPresentation(result: AmountMetricResult): BalanceMetricPresentation<Long> =
    metricPresentation(
        value = result.value?.yen,
        result = result,
    )

private fun ratioPresentation(result: RatioMetricResult): BalanceMetricPresentation<BigDecimal> =
    metricPresentation(
        value = result.value?.value?.movePointRight(2),
        result = result,
    )

private fun <T> metricPresentation(
    value: T?,
    result: BusinessMetricResult,
): BalanceMetricPresentation<T> {
    val status = buildList {
        add(result.classification.name)
        add(result.availability.name)
        if (MetricWarning.PARTIAL_PERIOD in result.warnings) {
            add(result.period.progressLabel())
        }
        if (result.missingInputs.isNotEmpty()) {
            add("不足: ${result.missingInputs.joinToString { it.name }}")
        }
    }.joinToString(" / ")
    return if (value != null) {
        BalanceMetricPresentation(value, null, status)
    } else {
        BalanceMetricPresentation(
            value = null,
            unavailableText = when (result.availability) {
                MetricAvailability.INSUFFICIENT_DATA -> "データ不足"
                MetricAvailability.NOT_AVAILABLE -> "算出対象外"
                MetricAvailability.AVAILABLE,
                MetricAvailability.PARTIAL_DATA,
                -> error("A value is required for ${result.availability}")
            },
            statusText = status,
        )
    }
}

private fun MetricPeriod.progressLabel(): String = when (this) {
    is MetricPeriod.Daily -> "当日途中"
    is MetricPeriod.Monthly -> "月途中"
    is MetricPeriod.CustomRange -> "期間途中"
}

private fun unavailableAnalysis(
    text: String,
    status: String,
): BalanceAnalysisPresentation = BalanceAnalysisPresentation(
    referenceCostLabel = "参考原価",
    referenceCostRateLabel = "参考原価率",
    referenceCost = BalanceMetricPresentation(null, text, status),
    approximateGrossProfit = BalanceMetricPresentation(null, text, status),
    referenceCostRatePercent = BalanceMetricPresentation(null, text, status),
)

private fun <T> legacyMetric(
    value: T?,
    unavailableText: String,
    status: String,
): BalanceMetricPresentation<T> = BalanceMetricPresentation(
    value = value,
    unavailableText = unavailableText.takeIf { value == null },
    statusText = status,
)
