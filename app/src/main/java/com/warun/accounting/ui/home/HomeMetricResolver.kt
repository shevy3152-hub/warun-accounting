package com.warun.accounting.ui.home

import com.warun.accounting.domain.metrics.AmountMetricResult
import com.warun.accounting.domain.metrics.BusinessMetricReport
import com.warun.accounting.domain.metrics.MetricAvailability
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.ui.model.BusinessMetricUiState
import java.time.LocalDate

internal enum class HomeMetricSource {
    BUSINESS_METRIC,
    LEGACY,
}

internal data class HomeMetricPresentation(
    val amountYen: Long?,
    val unavailableText: String?,
    val statusText: String,
) {
    init {
        require((amountYen != null) xor (unavailableText != null))
    }
}

internal fun homeDailyMetricPeriod(today: String): MetricPeriod.Daily =
    MetricPeriod.Daily(LocalDate.parse(today))

internal fun resolveHomeDailySales(
    source: HomeMetricSource,
    legacyYen: Long,
    expectedPeriod: MetricPeriod.Daily,
    state: BusinessMetricUiState,
): HomeMetricPresentation = resolveHomeMetric(
    source = source,
    legacyYen = legacyYen,
    expectedPeriod = expectedPeriod,
    state = state,
    metric = BusinessMetricReport::recordedSales,
)

internal fun resolveHomeDailyExpenses(
    source: HomeMetricSource,
    legacyYen: Long,
    expectedPeriod: MetricPeriod.Daily,
    state: BusinessMetricUiState,
): HomeMetricPresentation = resolveHomeMetric(
    source = source,
    legacyYen = legacyYen,
    expectedPeriod = expectedPeriod,
    state = state,
    metric = BusinessMetricReport::recordedExpenses,
)

internal fun resolveHomeMonthlySales(
    source: HomeMetricSource,
    legacyYen: Long,
    expectedPeriod: MetricPeriod.Monthly,
    state: BusinessMetricUiState,
): HomeMetricPresentation = resolveHomeMetric(
    source = source,
    legacyYen = legacyYen,
    expectedPeriod = expectedPeriod,
    state = state,
    metric = BusinessMetricReport::recordedSales,
)

internal fun resolveHomeMonthlyExpenses(
    source: HomeMetricSource,
    legacyYen: Long,
    expectedPeriod: MetricPeriod.Monthly,
    state: BusinessMetricUiState,
): HomeMetricPresentation = resolveHomeMetric(
    source = source,
    legacyYen = legacyYen,
    expectedPeriod = expectedPeriod,
    state = state,
    metric = BusinessMetricReport::recordedExpenses,
)

private fun resolveHomeMetric(
    source: HomeMetricSource,
    legacyYen: Long,
    expectedPeriod: MetricPeriod,
    state: BusinessMetricUiState,
    metric: (BusinessMetricReport) -> AmountMetricResult,
): HomeMetricPresentation {
    if (source == HomeMetricSource.LEGACY) {
        return HomeMetricPresentation(
            amountYen = legacyYen,
            unavailableText = null,
            statusText = "LEGACY / 旧Dashboard集計",
        )
    }

    val progressLabel = expectedPeriod.progressLabel()
    return when (state) {
        BusinessMetricUiState.Loading -> unavailable(
            text = "読み込み中",
            status = "BusinessMetric / Loading / $progressLabel",
        )

        is BusinessMetricUiState.Success -> {
            if (state.report.period != expectedPeriod) {
                unavailable(
                    text = "読み込み中",
                    status = "BusinessMetric / 期間更新中 / $progressLabel",
                )
            } else {
                resolveMetric(metric(state.report), progressLabel)
            }
        }

        is BusinessMetricUiState.MappingFailure,
        is BusinessMetricUiState.DataAccessFailure,
        is BusinessMetricUiState.AssemblyFailure,
        is BusinessMetricUiState.CalculationFailure,
        -> unavailable(
            text = "取得できません",
            status = "BusinessMetric / Failure / $progressLabel",
        )
    }
}

private fun resolveMetric(
    metric: AmountMetricResult,
    progressLabel: String,
): HomeMetricPresentation {
    val statusParts = buildList {
        add("RECORDED")
        add(metric.availability.name)
        if (metric.availability == MetricAvailability.PARTIAL_DATA || metric.value == null) {
            add(progressLabel)
        }
        if (metric.missingInputs.isNotEmpty()) {
            add("不足: ${metric.missingInputs.joinToString { it.name }}")
        }
    }
    val amount = metric.value?.yen
    return if (amount != null) {
        HomeMetricPresentation(
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

private fun MetricPeriod.progressLabel(): String = when (this) {
    is MetricPeriod.Daily -> "当日途中"
    is MetricPeriod.Monthly -> "月途中"
    is MetricPeriod.CustomRange -> "期間途中"
}

private fun unavailable(text: String, status: String) = HomeMetricPresentation(
    amountYen = null,
    unavailableText = text,
    statusText = status,
)
