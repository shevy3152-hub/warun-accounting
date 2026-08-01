package com.warun.accounting.ui.metrics

import com.warun.accounting.domain.metrics.AmountMetricResult
import com.warun.accounting.domain.metrics.BreakEvenRemainingMetricResult
import com.warun.accounting.domain.metrics.BusinessMetricResult
import com.warun.accounting.domain.metrics.MetricAvailability
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.domain.metrics.MissingMetricInput
import com.warun.accounting.domain.metrics.RatioMetricResult
import com.warun.accounting.ui.model.BusinessMetricUiState
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.Locale

internal enum class DiagnosticPeriodMode {
    DAILY,
    MONTHLY,
    CUSTOM_RANGE,
}

internal sealed interface DiagnosticPeriodSelection {
    data object Empty : DiagnosticPeriodSelection

    data class Valid(val period: MetricPeriod) : DiagnosticPeriodSelection

    data class Invalid(val message: String) : DiagnosticPeriodSelection
}

internal fun parseDiagnosticPeriod(
    mode: DiagnosticPeriodMode?,
    dailyText: String,
    monthlyText: String,
    rangeStartText: String,
    rangeEndText: String,
): DiagnosticPeriodSelection {
    return when (mode) {
        null -> DiagnosticPeriodSelection.Empty
        DiagnosticPeriodMode.DAILY ->
            parseDate(dailyText, "対象日") { DiagnosticPeriodSelection.Valid(MetricPeriod.Daily(it)) }
        DiagnosticPeriodMode.MONTHLY -> try {
            DiagnosticPeriodSelection.Valid(MetricPeriod.Monthly(YearMonth.parse(monthlyText)))
        } catch (_: DateTimeParseException) {
            DiagnosticPeriodSelection.Invalid("対象年月は yyyy-MM 形式で入力してください")
        }
        DiagnosticPeriodMode.CUSTOM_RANGE -> {
            val start = parseDateValue(rangeStartText)
            val end = parseDateValue(rangeEndText)
            when {
                start == null || end == null ->
                    DiagnosticPeriodSelection.Invalid("開始日と終了日は yyyy-MM-dd 形式で入力してください")
                start.isAfter(end) ->
                    DiagnosticPeriodSelection.Invalid("開始日は終了日以前にしてください")
                else -> DiagnosticPeriodSelection.Valid(MetricPeriod.CustomRange(start, end))
            }
        }
    }
}

private fun parseDate(
    text: String,
    label: String,
    onValid: (LocalDate) -> DiagnosticPeriodSelection,
): DiagnosticPeriodSelection = parseDateValue(text)?.let(onValid)
    ?: DiagnosticPeriodSelection.Invalid("${label}は yyyy-MM-dd 形式で入力してください")

private fun parseDateValue(text: String): LocalDate? = try {
    LocalDate.parse(text)
} catch (_: DateTimeParseException) {
    null
}

internal fun MetricPeriod.toDiagnosticLabel(): String = when (this) {
    is MetricPeriod.Daily -> "Daily: $date"
    is MetricPeriod.Monthly -> "Monthly: $month"
    is MetricPeriod.CustomRange -> "CustomRange: $startDate ～ $endDateInclusive"
}

internal fun formatDiagnosticMetricValue(result: BusinessMetricResult): String = when (result) {
    is AmountMetricResult -> result.value?.let { formatYen(it.yen) }
        ?: unavailableMetricLabel(result.availability, result.missingInputs)
    is RatioMetricResult -> result.value?.let { formatRatio(it.value) }
        ?: unavailableMetricLabel(result.availability, result.missingInputs)
    is BreakEvenRemainingMetricResult -> when (val value = result.value) {
        null -> unavailableMetricLabel(result.availability, result.missingInputs)
        is com.warun.accounting.domain.metrics.BreakEvenPosition.Remaining -> formatYen(value.yen)
        is com.warun.accounting.domain.metrics.BreakEvenPosition.Achieved -> "達成（余剰 ${formatYen(value.excessYen)}）"
    }
}

internal fun formatYen(yen: Long): String = String.format(Locale.JAPAN, "¥%,d", yen)

internal fun formatRatio(value: java.math.BigDecimal): String =
    "${value.multiply(java.math.BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)}%"

internal fun unavailableMetricLabel(
    availability: MetricAvailability,
    missingInputs: Set<MissingMetricInput>,
): String = when {
    availability == MetricAvailability.NOT_AVAILABLE -> "算出対象外"
    missingInputs.isNotEmpty() -> "入力不足"
    availability == MetricAvailability.INSUFFICIENT_DATA -> "データなし"
    availability == MetricAvailability.PARTIAL_DATA -> "未確定"
    else -> "—"
}

internal fun diagnosticFailureMessage(state: BusinessMetricUiState): String = when (state) {
    is BusinessMetricUiState.MappingFailure -> "データ形式を確認してください"
    is BusinessMetricUiState.DataAccessFailure -> "データを取得できませんでした"
    is BusinessMetricUiState.AssemblyFailure -> "入力データを確認してください"
    is BusinessMetricUiState.CalculationFailure -> "指標を計算できませんでした"
    else -> ""
}
