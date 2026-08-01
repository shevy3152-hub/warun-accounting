package com.warun.accounting.ui.balance

import com.warun.accounting.domain.metrics.AmountMetricResult
import com.warun.accounting.domain.metrics.BreakEvenPosition
import com.warun.accounting.domain.metrics.BreakEvenRemainingMetricResult
import com.warun.accounting.domain.metrics.BusinessMetricResult
import com.warun.accounting.domain.metrics.MetricAvailability
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.domain.metrics.MetricWarning
import com.warun.accounting.domain.metrics.RatioMetricResult
import com.warun.accounting.ui.BalancePeriod
import com.warun.accounting.ui.BalancePeriodMode
import com.warun.accounting.ui.model.BusinessAnalysisSummary
import com.warun.accounting.ui.model.BusinessMetricUiState
import com.warun.accounting.ui.model.breakEvenStatusMessage
import com.warun.accounting.ui.util.toYen
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
    val fixedCostLabel: String,
    val breakEvenSalesLabel: String,
    val breakEvenStatusLabel: String,
    val referenceCost: BalanceMetricPresentation<Long>,
    val approximateGrossProfit: BalanceMetricPresentation<Long>,
    val referenceCostRatePercent: BalanceMetricPresentation<BigDecimal>,
    val fixedCost: BalanceMetricPresentation<Long>,
    val breakEvenSales: BalanceMetricPresentation<Long>,
    val breakEvenStatus: BalanceMetricPresentation<String>,
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
            fixedCostLabel = "固定費相当額（簡易）",
            breakEvenSalesLabel = "概算損益分岐点売上",
            breakEvenStatusLabel = "損益分岐点との差",
            referenceCost = legacyMetric(legacy.estimatedCost, "計算不可", status),
            approximateGrossProfit = legacyMetric(legacy.estimatedGrossProfit, "計算不可", status),
            referenceCostRatePercent = legacyMetric(legacy.estimatedCostRate, "—", status),
            fixedCost = legacyMetric(legacy.simpleFixedCost, "計算不可", status),
            breakEvenSales = legacyMetric(legacy.estimatedBreakEvenSales, "計算不可", status),
            breakEvenStatus = BalanceMetricPresentation(
                value = legacy.breakEvenStatusMessage(),
                unavailableText = null,
                statusText = status,
            ),
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
                    fixedCostLabel = "記録済み固定費相当額",
                    breakEvenSalesLabel = "参考損益分岐売上高",
                    breakEvenStatusLabel = "参考損益分岐との差",
                    referenceCost = amountPresentation(state.report.referenceCost),
                    approximateGrossProfit = amountPresentation(state.report.approximateGrossProfit),
                    referenceCostRatePercent = ratioPresentation(state.report.referenceCostRate),
                    fixedCost = amountPresentation(state.report.recordedFixedCostEquivalent),
                    breakEvenSales = amountPresentation(state.report.referenceBreakEvenSales),
                    breakEvenStatus = breakEvenPresentation(state.report.breakEvenRemaining),
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

private fun breakEvenPresentation(
    result: BreakEvenRemainingMetricResult,
): BalanceMetricPresentation<String> = metricPresentation(
    value = when (val position = result.value) {
        is BreakEvenPosition.Remaining -> "参考損益分岐売上高まであと ${position.yen.toYen()}です"
        is BreakEvenPosition.Achieved ->
            "参考損益分岐売上高を ${position.excessYen.toYen()}上回っています"
        null -> null
    },
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
    fixedCostLabel = "記録済み固定費相当額",
    breakEvenSalesLabel = "参考損益分岐売上高",
    breakEvenStatusLabel = "参考損益分岐との差",
    referenceCost = BalanceMetricPresentation(null, text, status),
    approximateGrossProfit = BalanceMetricPresentation(null, text, status),
    referenceCostRatePercent = BalanceMetricPresentation(null, text, status),
    fixedCost = BalanceMetricPresentation(null, text, status),
    breakEvenSales = BalanceMetricPresentation(null, text, status),
    breakEvenStatus = BalanceMetricPresentation(null, text, status),
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
