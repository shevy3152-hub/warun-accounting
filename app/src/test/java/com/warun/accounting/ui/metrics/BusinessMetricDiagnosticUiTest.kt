package com.warun.accounting.ui.metrics

import com.warun.accounting.domain.metrics.AmountMetricResult
import com.warun.accounting.domain.metrics.BusinessMetricType
import com.warun.accounting.domain.metrics.MetricAvailability
import com.warun.accounting.domain.metrics.MetricClassification
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.domain.metrics.MetricSourceSummary
import com.warun.accounting.domain.metrics.MetricValue
import com.warun.accounting.domain.metrics.MissingMetricInput
import com.warun.accounting.domain.metrics.RatioMetricResult
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessMetricDiagnosticUiTest {
    @Test
    fun zeroAmountIsDisplayedAsYenZero() {
        assertEquals("¥0", formatDiagnosticMetricValue(amountResult(MetricValue.Amount(0L))))
    }

    @Test
    fun nullInsufficientValueIsNotDisplayedAsZero() {
        assertEquals(
            "入力不足",
            unavailableMetricLabel(
                MetricAvailability.INSUFFICIENT_DATA,
                setOf(MissingMetricInput.EXPENSE_SOURCE),
            ),
        )
    }

    @Test
    fun nullRatioIsNotDisplayedAsZeroPercent() {
        assertEquals(
            "入力不足",
            formatDiagnosticMetricValue(ratioResult(null)),
        )
    }

    @Test
    fun unitRatioIsConvertedOnlyForDisplay() {
        assertEquals("35.0%", formatRatio(BigDecimal("0.35")))
    }

    @Test
    fun unavailableStatesHaveDistinctLabels() {
        assertEquals("算出対象外", unavailableMetricLabel(MetricAvailability.NOT_AVAILABLE, emptySet()))
        assertEquals("未確定", unavailableMetricLabel(MetricAvailability.PARTIAL_DATA, emptySet()))
        assertEquals("データなし", unavailableMetricLabel(MetricAvailability.INSUFFICIENT_DATA, emptySet()))
    }

    @Test
    fun dailyPeriodIsParsedWithoutCurrentDate() {
        val result = parseDiagnosticPeriod(
            mode = DiagnosticPeriodMode.DAILY,
            dailyText = "2026-07-01",
            monthlyText = "",
            rangeStartText = "",
            rangeEndText = "",
        )
        assertEquals("Daily: 2026-07-01", (result as DiagnosticPeriodSelection.Valid).period.toDiagnosticLabel())
    }

    @Test
    fun invalidDailyDatesAndWhitespaceAreRejected() {
        listOf(" 2026-08-01", "2026-08-01 ", " 2026-08-01 ", "2026-02-30", "2026-08-01\t", "2026-08-01\n", "　2026-08-01")
            .forEach { input ->
                assertInvalid(
                    parseDiagnosticPeriod(DiagnosticPeriodMode.DAILY, input, "", "", ""),
                )
            }
    }

    @Test
    fun monthlyPeriodIsParsed() {
        val result = parseDiagnosticPeriod(
            mode = DiagnosticPeriodMode.MONTHLY,
            dailyText = "",
            monthlyText = "2026-07",
            rangeStartText = "",
            rangeEndText = "",
        )
        assertEquals("Monthly: 2026-07", (result as DiagnosticPeriodSelection.Valid).period.toDiagnosticLabel())
    }

    @Test
    fun invalidMonthlyValuesAndWhitespaceAreRejected() {
        listOf(" 2026-08", "2026-08 ", " 2026-08 ", "2026-13", "2026-00", "2026-08\t", "2026-08\n", "　2026-08")
            .forEach { input ->
                assertInvalid(
                    parseDiagnosticPeriod(DiagnosticPeriodMode.MONTHLY, "", input, "", ""),
                )
            }
    }

    @Test
    fun customRangeIncludesValidOrderedBounds() {
        val result = parseDiagnosticPeriod(
            mode = DiagnosticPeriodMode.CUSTOM_RANGE,
            dailyText = "",
            monthlyText = "",
            rangeStartText = "2026-07-01",
            rangeEndText = "2026-07-31",
        )
        assertEquals("CustomRange: 2026-07-01 ～ 2026-07-31", (result as DiagnosticPeriodSelection.Valid).period.toDiagnosticLabel())
    }

    @Test
    fun customRangeRejectsWhitespaceAndInvalidDates() {
        listOf(
            " 2026-07-01" to "2026-07-31",
            "2026-07-01 " to "2026-07-31",
            "2026-02-30" to "2026-07-31",
            "2026-07-01" to "2026-02-30",
            "2026-07-01\t" to "2026-07-31",
            "2026-07-01" to "2026-07-31\n",
            "　2026-07-01" to "2026-07-31",
        ).forEach { (start, end) ->
            assertInvalid(parseDiagnosticPeriod(DiagnosticPeriodMode.CUSTOM_RANGE, "", "", start, end))
        }
    }

    @Test
    fun reversedCustomRangeDoesNotProduceRequestPeriod() {
        val result = parseDiagnosticPeriod(
            mode = DiagnosticPeriodMode.CUSTOM_RANGE,
            dailyText = "",
            monthlyText = "",
            rangeStartText = "2026-07-31",
            rangeEndText = "2026-07-01",
        )
        assertEquals("開始日は終了日以前にしてください", (result as DiagnosticPeriodSelection.Invalid).message)
    }

    private fun assertInvalid(result: DiagnosticPeriodSelection) {
        assertTrue(result is DiagnosticPeriodSelection.Invalid)
    }

    @Test
    fun mappingAndDataAccessMessagesDoNotExposeInternalDetails() {
        assertEquals("データ形式を確認してください", diagnosticFailureMessage(
            com.warun.accounting.ui.model.BusinessMetricUiState.MappingFailure(
                com.warun.accounting.data.metrics.BusinessMetricSnapshotMappingFailure(
                    com.warun.accounting.data.metrics.BusinessMetricSnapshotMappingFailureReason.UNKNOWN_EXPENSE_CATEGORY,
                    recordId = "secret-expense-id",
                    originalValue = "secret-category",
                ),
            ),
        ))
        assertEquals("データを取得できませんでした", diagnosticFailureMessage(
            com.warun.accounting.ui.model.BusinessMetricUiState.DataAccessFailure(
                "IllegalStateException",
                "secret stack trace",
            ),
        ))
    }

    private fun amountResult(value: MetricValue.Amount?) = AmountMetricResult(
        type = BusinessMetricType.RECORDED_SALES,
        value = value,
        period = MetricPeriod.Daily(LocalDate.of(2026, 7, 1)),
        availability = if (value == null) MetricAvailability.INSUFFICIENT_DATA else MetricAvailability.AVAILABLE,
        classification = MetricClassification.RECORDED,
        calculationBasis = setOf(com.warun.accounting.domain.metrics.MetricCalculationBasis.RECORDED_SALES_TOTAL),
        missingInputs = if (value == null) setOf(MissingMetricInput.SALES_SOURCE) else emptySet(),
        warnings = emptySet(),
        sourceSummary = MetricSourceSummary(
            salesSourceCount = if (value == null) 0 else 1,
            expenseSourceCount = 0,
            foodPurchaseSourceCount = 0,
            alcoholPurchaseSourceCount = 0,
            fixedCostSourceCount = 0,
            customerCountSourceCount = 0,
            legacyFallbackUsed = false,
            cancellationsExcluded = true,
        ),
        calculatedAt = Instant.parse("2026-07-01T00:00:00Z"),
    )

    private fun ratioResult(value: MetricValue.Ratio?) = RatioMetricResult(
        type = BusinessMetricType.REFERENCE_COST_RATE,
        value = value,
        period = MetricPeriod.Daily(LocalDate.of(2026, 7, 1)),
        availability = if (value == null) MetricAvailability.INSUFFICIENT_DATA else MetricAvailability.AVAILABLE,
        classification = MetricClassification.REFERENCE,
        calculationBasis = setOf(com.warun.accounting.domain.metrics.MetricCalculationBasis.REFERENCE_COST_DIVIDED_BY_SALES),
        missingInputs = if (value == null) setOf(MissingMetricInput.REFERENCE_COST_SOURCE) else emptySet(),
        warnings = emptySet(),
        sourceSummary = MetricSourceSummary(
            salesSourceCount = 1,
            expenseSourceCount = 1,
            foodPurchaseSourceCount = 1,
            alcoholPurchaseSourceCount = 0,
            fixedCostSourceCount = 0,
            customerCountSourceCount = 0,
            legacyFallbackUsed = false,
            cancellationsExcluded = true,
        ),
        calculatedAt = Instant.parse("2026-07-01T00:00:00Z"),
    )
}
