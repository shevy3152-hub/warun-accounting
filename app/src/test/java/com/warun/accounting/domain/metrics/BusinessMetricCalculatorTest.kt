package com.warun.accounting.domain.metrics

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessMetricCalculatorTest {
    @Test
    fun completeMonthCalculatesAllMetricsFromNormalizedTotals() {
        val report = calculate()

        assertAmount(100_000L, report.recordedSales)
        assertAmount(50_000L, report.recordedExpenses)
        assertAmount(30_000L, report.referenceCost)
        assertRatio("0.300000000000", report.referenceCostRate)
        assertAmount(70_000L, report.approximateGrossProfit)
        assertAmount(30_000L, report.recordedFixedCostEquivalent)
        assertAmount(40_000L, report.fixedCostRecoveryGap)
        assertRatio("2.333333333333", report.fixedCostRecoveryRate)
        assertAmount(42_858L, report.referenceBreakEvenSales)
        assertEquals(BreakEvenPosition.Achieved(57_142L), report.breakEvenRemaining.value)
        assertAmount(1_000L, report.averageSpendPerCustomer)
    }

    @Test
    fun reportContainsEveryMetricExactlyOnceWithContractClassification() {
        val report = calculate()

        assertEquals(BusinessMetricType.entries.toSet(), report.results.map { it.type }.toSet())
        assertEquals(BusinessMetricType.entries.size, report.results.size)
        assertEquals(
            setOf(MetricClassification.RECORDED, MetricClassification.REFERENCE),
            report.results.map { it.classification }.toSet(),
        )
        assertFalse(report.results.any { it.classification == MetricClassification.ESTIMATED })
        assertFalse(report.results.any { it.classification == MetricClassification.CONFIRMED })
    }

    @Test
    fun everyResultCarriesPeriodBasisSourceSummaryAndCalculationTime() {
        val input = baseInput()

        val report = calculate(input)

        report.results.forEach { result ->
            assertEquals(input.period, result.period)
            assertEquals(input.sourceSummary, result.sourceSummary)
            assertEquals(input.calculatedAt, result.calculatedAt)
            assertTrue(result.calculationBasis.isNotEmpty())
        }
    }

    @Test
    fun resultModelRejectsMetricTypeAndValueShapeMismatch() {
        val recordedSales = calculate().recordedSales

        assertThrows(IllegalArgumentException::class.java) {
            recordedSales.copy(type = BusinessMetricType.REFERENCE_COST_RATE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            recordedSales.copy(value = null)
        }
    }

    @Test
    fun calculatedAtIsProvidedByCallerAndPropagatedWithoutReadingClock() {
        val calculatedAt = Instant.parse("2000-01-02T03:04:05Z")

        val report = calculate(baseInput().copy(calculatedAt = calculatedAt))

        assertEquals(calculatedAt, report.calculatedAt)
        assertTrue(report.results.all { it.calculatedAt == calculatedAt })
    }

    @Test
    fun referenceCostUsesOnlyFoodAndAlcoholPurchases() {
        val report = calculate(
            baseInput().copy(
                foodPurchasesYen = 12_345L,
                alcoholPurchasesYen = 6_789L,
                recordedExpensesYen = 999_999L,
            ),
        )

        assertAmount(19_134L, report.referenceCost)
        assertTrue(MetricWarning.INVENTORY_NOT_INCLUDED in report.referenceCost.warnings)
        assertTrue(MetricWarning.UNREGISTERED_PURCHASES_NOT_INCLUDED in report.referenceCost.warnings)
        assertTrue(MetricWarning.CONSUMABLES_EXCLUDED_FROM_REFERENCE_COST in report.referenceCost.warnings)
    }

    @Test
    fun oneReferenceCostCategoryCanBeAbsentWithoutInventingItsSource() {
        val input = baseInput().copy(
            foodPurchasesYen = 8_000L,
            alcoholPurchasesYen = null,
            sourceSummary = baseSources().copy(
                foodPurchaseSourceCount = 1,
                alcoholPurchaseSourceCount = 0,
            ),
        )

        assertAmount(8_000L, calculate(input).referenceCost)
    }

    @Test
    fun missingReferenceCostSourcesDoNotBecomeZero() {
        val input = baseInput().copy(
            foodPurchasesYen = null,
            alcoholPurchasesYen = null,
            sourceSummary = baseSources().copy(
                foodPurchaseSourceCount = 0,
                alcoholPurchaseSourceCount = 0,
            ),
        )

        val result = calculate(input).referenceCost

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, result.availability)
        assertNull(result.value)
        assertTrue(MissingMetricInput.REFERENCE_COST_SOURCE in result.missingInputs)
    }

    @Test
    fun explicitZeroReferenceCostSourceIsAvailable() {
        val input = baseInput().copy(
            foodPurchasesYen = 0L,
            alcoholPurchasesYen = null,
            sourceSummary = baseSources().copy(
                foodPurchaseSourceCount = 1,
                alcoholPurchaseSourceCount = 0,
            ),
        )

        val report = calculate(input)

        assertAmount(0L, report.referenceCost)
        assertRatio("0E-12", report.referenceCostRate)
    }

    @Test
    fun zeroWithoutReferenceCostSourceIsRejectedAsInconsistentInput() {
        val input = baseInput().copy(
            foodPurchasesYen = 0L,
            alcoholPurchasesYen = null,
            sourceSummary = baseSources().copy(
                foodPurchaseSourceCount = 0,
                alcoholPurchaseSourceCount = 0,
            ),
        )

        val result = calculate(input).referenceCost

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, result.availability)
        assertTrue(MissingMetricInput.NON_NEGATIVE_INPUT in result.missingInputs)
    }

    @Test
    fun fixedCostEquivalentUsesOnlySpecifiedManagementCategories() {
        val input = baseInput().copy(
            rentYen = 10_001L,
            communicationYen = 2_002L,
            accountantFeeYen = 3_003L,
            electricityYen = 4_004L,
            gasYen = 5_005L,
            waterYen = 6_006L,
            recordedExpensesYen = 900_000L,
        )

        val result = calculate(input).recordedFixedCostEquivalent

        assertAmount(30_021L, result)
        assertTrue(MetricWarning.UTILITIES_TREATED_AS_MANAGEMENT_FIXED_COST in result.warnings)
        assertTrue(MetricWarning.OWNER_LABOR_COST_EXCLUDED in result.warnings)
    }

    @Test
    fun partialFixedCostSourcesKeepRecordedValueAndDeclarePartialData() {
        val input = baseInput().copy(
            rentYen = 10_000L,
            communicationYen = null,
            accountantFeeYen = null,
            electricityYen = null,
            gasYen = null,
            waterYen = null,
            sourceSummary = baseSources().copy(fixedCostSourceCount = 1),
        )

        val result = calculate(input).recordedFixedCostEquivalent

        assertEquals(MetricValue.Amount(10_000L), result.value)
        assertEquals(MetricAvailability.PARTIAL_DATA, result.availability)
        assertTrue(MetricWarning.SOURCE_DATA_PARTIAL in result.warnings)
    }

    @Test
    fun missingFixedCostSourceDoesNotBecomeZero() {
        val input = baseInput().copy(
            rentYen = null,
            communicationYen = null,
            accountantFeeYen = null,
            electricityYen = null,
            gasYen = null,
            waterYen = null,
            sourceSummary = baseSources().copy(fixedCostSourceCount = 0),
        )

        val result = calculate(input).recordedFixedCostEquivalent

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, result.availability)
        assertNull(result.value)
        assertTrue(MissingMetricInput.FIXED_COST_SOURCE in result.missingInputs)
    }

    @Test
    fun explicitZeroFixedCostIsRecordedButRecoveryAndBreakEvenAreUnavailable() {
        val input = baseInput().copy(
            rentYen = 0L,
            communicationYen = 0L,
            accountantFeeYen = 0L,
            electricityYen = 0L,
            gasYen = 0L,
            waterYen = 0L,
        )

        val report = calculate(input)

        assertAmount(0L, report.recordedFixedCostEquivalent)
        assertEquals(MetricAvailability.NOT_AVAILABLE, report.fixedCostRecoveryGap.availability)
        assertNull(report.fixedCostRecoveryGap.value)
        assertEquals(MetricAvailability.NOT_AVAILABLE, report.fixedCostRecoveryRate.availability)
        assertEquals(MetricAvailability.NOT_AVAILABLE, report.referenceBreakEvenSales.availability)
        assertTrue(
            MissingMetricInput.POSITIVE_FIXED_COST in report.referenceBreakEvenSales.missingInputs,
        )
    }

    @Test
    fun zeroSalesSourceIsRecordedButRateAndBreakEvenAreUnavailable() {
        val input = baseInput().copy(recordedSalesYen = 0L)

        val report = calculate(input)

        assertAmount(0L, report.recordedSales)
        assertEquals(MetricAvailability.NOT_AVAILABLE, report.referenceCostRate.availability)
        assertNull(report.referenceCostRate.value)
        assertEquals(MetricAvailability.NOT_AVAILABLE, report.referenceBreakEvenSales.availability)
        assertFalse(report.results.any { result ->
            result is RatioMetricResult &&
                result.value?.value?.let { it.toDouble().isNaN() || it.toDouble().isInfinite() } == true
        })
    }

    @Test
    fun missingSalesSourcePropagatesInsufficientDataWithoutZeroSubstitution() {
        val input = baseInput().copy(
            recordedSalesYen = null,
            sourceSummary = baseSources().copy(salesSourceCount = 0),
        )

        val report = calculate(input)

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, report.recordedSales.availability)
        assertNull(report.recordedSales.value)
        assertNull(report.approximateGrossProfit.value)
        assertNull(report.averageSpendPerCustomer.value)
    }

    @Test
    fun costAboveSalesKeepsNegativeGrossProfitAndDisablesBreakEven() {
        val input = baseInput().copy(
            recordedSalesYen = 10_000L,
            foodPurchasesYen = 15_000L,
            alcoholPurchasesYen = 0L,
        )

        val report = calculate(input)

        assertRatio("1.500000000000", report.referenceCostRate)
        assertAmount(-5_000L, report.approximateGrossProfit)
        assertEquals(MetricAvailability.NOT_AVAILABLE, report.referenceBreakEvenSales.availability)
        assertTrue(
            MissingMetricInput.VALID_CONTRIBUTION_MARGIN in
                report.referenceBreakEvenSales.missingInputs,
        )
    }

    @Test
    fun oneHundredPercentCostRateDisablesBreakEvenWithoutNaNOrInfinity() {
        val input = baseInput().copy(
            recordedSalesYen = 10_000L,
            foodPurchasesYen = 10_000L,
            alcoholPurchasesYen = 0L,
        )

        val report = calculate(input)

        assertRatio("1.000000000000", report.referenceCostRate)
        assertAmount(0L, report.approximateGrossProfit)
        assertEquals(MetricAvailability.NOT_AVAILABLE, report.referenceBreakEvenSales.availability)
        assertNull(report.referenceBreakEvenSales.value)
        assertTrue(
            MissingMetricInput.VALID_CONTRIBUTION_MARGIN in
                report.referenceBreakEvenSales.missingInputs,
        )
    }

    @Test
    fun fixedCostRecoveryGapAndRateCanExpressLoss() {
        val input = baseInput().copy(
            recordedSalesYen = 20_000L,
            foodPurchasesYen = 30_000L,
            alcoholPurchasesYen = 0L,
        )

        val report = calculate(input)

        assertAmount(-40_000L, report.fixedCostRecoveryGap)
        assertRatio("-0.333333333333", report.fixedCostRecoveryRate)
    }

    @Test
    fun ratioUsesStableBigDecimalPrecisionWithoutDisplayRounding() {
        val input = baseInput().copy(
            recordedSalesYen = 3L,
            foodPurchasesYen = 1L,
            alcoholPurchasesYen = 0L,
        )

        assertRatio("0.333333333333", calculate(input).referenceCostRate)
    }

    @Test
    fun breakEvenUsesCeilingSoReferenceAmountIsNotUnderstated() {
        val input = baseInput().copy(
            recordedSalesYen = 100L,
            foodPurchasesYen = 34L,
            alcoholPurchasesYen = 0L,
            rentYen = 1L,
            communicationYen = 0L,
            accountantFeeYen = 0L,
            electricityYen = 0L,
            gasYen = 0L,
            waterYen = 0L,
        )

        assertAmount(2L, calculate(input).referenceBreakEvenSales)
    }

    @Test
    fun breakEvenRemainingDistinguishesRemainingFromAchievedAndExcess() {
        val remaining = calculate(
            baseInput().copy(
                recordedSalesYen = 40_000L,
                foodPurchasesYen = 20_000L,
                alcoholPurchasesYen = 0L,
            ),
        )
        val achievedExactly = calculate(
            baseInput().copy(
                recordedSalesYen = 60_000L,
                foodPurchasesYen = 30_000L,
                alcoholPurchasesYen = 0L,
            ),
        )

        assertEquals(BreakEvenPosition.Remaining(20_000L), remaining.breakEvenRemaining.value)
        assertEquals(BreakEvenPosition.Achieved(0L), achievedExactly.breakEvenRemaining.value)
    }

    @Test
    fun breakEvenIsLimitedToCompletedCalendarMonth() {
        val daily = calculate(
            baseInput().copy(
                period = MetricPeriod.Daily(LocalDate.of(2026, 6, 30)),
            ),
        )
        val custom = calculate(
            baseInput().copy(
                period = MetricPeriod.CustomRange(
                    LocalDate.of(2026, 6, 1),
                    LocalDate.of(2026, 6, 30),
                ),
            ),
        )
        val incompleteMonth = calculate(
            baseInput().copy(periodState = MetricPeriodState(isComplete = false)),
        )

        listOf(daily, custom, incompleteMonth).forEach { report ->
            assertEquals(MetricAvailability.NOT_AVAILABLE, report.referenceBreakEvenSales.availability)
            assertNull(report.referenceBreakEvenSales.value)
            assertTrue(
                MissingMetricInput.COMPLETED_CALENDAR_MONTH in
                    report.referenceBreakEvenSales.missingInputs,
            )
        }
    }

    @Test
    fun incompletePeriodReturnsPartialDataForOtherwiseCalculableMetrics() {
        val report = calculate(
            baseInput().copy(periodState = MetricPeriodState(isComplete = false)),
        )

        assertEquals(MetricAvailability.PARTIAL_DATA, report.recordedSales.availability)
        assertEquals(MetricAvailability.PARTIAL_DATA, report.referenceCost.availability)
        assertTrue(MetricWarning.PARTIAL_PERIOD in report.recordedSales.warnings)
    }

    @Test
    fun futureDatedSourceReturnsPartialDataAndWarning() {
        val report = calculate(
            baseInput().copy(
                periodState = MetricPeriodState(
                    isComplete = true,
                    containsFutureDatedData = true,
                ),
            ),
        )

        assertEquals(MetricAvailability.PARTIAL_DATA, report.recordedSales.availability)
        assertTrue(MetricWarning.FUTURE_DATED_DATA_INCLUDED in report.recordedSales.warnings)
    }

    @Test
    fun unconfirmedCancellationExclusionReturnsPartialDataAndWarning() {
        val report = calculate(
            baseInput().copy(
                sourceSummary = baseSources().copy(cancellationsExcluded = false),
            ),
        )

        assertEquals(MetricAvailability.PARTIAL_DATA, report.recordedExpenses.availability)
        assertTrue(
            MetricWarning.CANCELLATION_EXCLUSION_NOT_CONFIRMED in
                report.recordedExpenses.warnings,
        )
    }

    @Test
    fun legacyFallbackUsageIsDeclaredWithoutReimplementingFallback() {
        val report = calculate(
            baseInput().copy(
                sourceSummary = baseSources().copy(legacyFallbackUsed = true),
            ),
        )

        assertAmount(50_000L, report.recordedExpenses)
        assertTrue(MetricWarning.LEGACY_FALLBACK_USED in report.recordedExpenses.warnings)
    }

    @Test
    fun customerUnitPriceUsesTotalSalesDividedByTotalCustomersWithIntegerTruncation() {
        val input = baseInput().copy(
            recordedSalesYen = 100L,
            customerCount = 3L,
        )

        assertAmount(33L, calculate(input).averageSpendPerCustomer)
    }

    @Test
    fun zeroCustomerCountIsInsufficientRatherThanZeroYen() {
        val input = baseInput().copy(customerCount = 0L)

        val result = calculate(input).averageSpendPerCustomer

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, result.availability)
        assertNull(result.value)
        assertTrue(MetricWarning.CUSTOMER_COUNT_ZERO_OR_UNKNOWN in result.warnings)
    }

    @Test
    fun missingCustomerSourceIsInsufficientRatherThanZeroYen() {
        val input = baseInput().copy(
            customerCount = null,
            sourceSummary = baseSources().copy(customerCountSourceCount = 0),
        )

        val result = calculate(input).averageSpendPerCustomer

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, result.availability)
        assertNull(result.value)
        assertTrue(MissingMetricInput.CUSTOMER_COUNT_SOURCE in result.missingInputs)
    }

    @Test
    fun negativeInputsAreRejectedWithoutThrowingOrProducingAValue() {
        val input = baseInput().copy(
            recordedSalesYen = -1L,
            foodPurchasesYen = -2L,
            customerCount = -3L,
        )

        val report = calculate(input)

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, report.recordedSales.availability)
        assertNull(report.referenceCost.value)
        assertNull(report.averageSpendPerCustomer.value)
        assertTrue(MissingMetricInput.NON_NEGATIVE_INPUT in report.recordedSales.missingInputs)
    }

    @Test
    fun referenceCostOverflowIsReportedWithoutWrapping() {
        val input = baseInput().copy(
            foodPurchasesYen = Long.MAX_VALUE,
            alcoholPurchasesYen = 1L,
        )

        val result = calculate(input).referenceCost

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, result.availability)
        assertNull(result.value)
        assertTrue(MissingMetricInput.VALUE_WITHIN_LONG_RANGE in result.missingInputs)
    }

    @Test
    fun fixedCostOverflowIsReportedWithoutWrapping() {
        val input = baseInput().copy(
            rentYen = Long.MAX_VALUE,
            communicationYen = 1L,
            accountantFeeYen = 0L,
            electricityYen = 0L,
            gasYen = 0L,
            waterYen = 0L,
        )

        val result = calculate(input).recordedFixedCostEquivalent

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, result.availability)
        assertNull(result.value)
        assertTrue(MissingMetricInput.VALUE_WITHIN_LONG_RANGE in result.missingInputs)
    }

    @Test
    fun recoveryGapOverflowIsReportedWithoutWrapping() {
        val input = baseInput().copy(
            recordedSalesYen = 0L,
            foodPurchasesYen = Long.MAX_VALUE,
            alcoholPurchasesYen = 0L,
            rentYen = Long.MAX_VALUE,
            communicationYen = 0L,
            accountantFeeYen = 0L,
            electricityYen = 0L,
            gasYen = 0L,
            waterYen = 0L,
        )

        val result = calculate(input).fixedCostRecoveryGap

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, result.availability)
        assertNull(result.value)
        assertTrue(MissingMetricInput.VALUE_WITHIN_LONG_RANGE in result.missingInputs)
    }

    @Test
    fun longMaximumBoundaryCalculatesWithoutOverflowWhenMathematicallyValid() {
        val input = baseInput().copy(
            recordedSalesYen = Long.MAX_VALUE,
            recordedExpensesYen = Long.MAX_VALUE,
            foodPurchasesYen = 0L,
            alcoholPurchasesYen = null,
            rentYen = Long.MAX_VALUE,
            communicationYen = 0L,
            accountantFeeYen = 0L,
            electricityYen = 0L,
            gasYen = 0L,
            waterYen = 0L,
            customerCount = 1L,
            sourceSummary = baseSources().copy(alcoholPurchaseSourceCount = 0),
        )

        val report = calculate(input)

        assertAmount(Long.MAX_VALUE, report.recordedSales)
        assertAmount(Long.MAX_VALUE, report.recordedFixedCostEquivalent)
        assertAmount(Long.MAX_VALUE, report.referenceBreakEvenSales)
        assertAmount(Long.MAX_VALUE, report.averageSpendPerCustomer)
    }

    @Test
    fun unavailableResultsNeverCarryZeroAsAPlaceholderValue() {
        val input = baseInput().copy(
            recordedSalesYen = null,
            foodPurchasesYen = null,
            alcoholPurchasesYen = null,
            rentYen = null,
            communicationYen = null,
            accountantFeeYen = null,
            electricityYen = null,
            gasYen = null,
            waterYen = null,
            customerCount = null,
            sourceSummary = MetricSourceSummary(
                salesSourceCount = 0,
                expenseSourceCount = 1,
                foodPurchaseSourceCount = 0,
                alcoholPurchaseSourceCount = 0,
                fixedCostSourceCount = 0,
                customerCountSourceCount = 0,
                legacyFallbackUsed = false,
                cancellationsExcluded = true,
            ),
        )

        val report = calculate(input)

        report.results.filter {
            it.availability == MetricAvailability.INSUFFICIENT_DATA ||
                it.availability == MetricAvailability.NOT_AVAILABLE
        }.forEach { result ->
            when (result) {
                is AmountMetricResult -> assertNull(result.value)
                is RatioMetricResult -> assertNull(result.value)
                is BreakEvenRemainingMetricResult -> assertNull(result.value)
            }
        }
    }

    @Test
    fun sourceSummaryRejectsNegativeCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            baseSources().copy(salesSourceCount = -1)
        }
    }

    private fun calculate(input: BusinessMetricInput = baseInput()): BusinessMetricReport =
        BusinessMetricCalculator.calculate(input)

    private fun baseInput() = BusinessMetricInput(
        period = MetricPeriod.Monthly(YearMonth.of(2026, 6)),
        periodState = MetricPeriodState(isComplete = true),
        recordedSalesYen = 100_000L,
        recordedExpensesYen = 50_000L,
        foodPurchasesYen = 20_000L,
        alcoholPurchasesYen = 10_000L,
        rentYen = 10_000L,
        communicationYen = 5_000L,
        accountantFeeYen = 5_000L,
        electricityYen = 5_000L,
        gasYen = 2_000L,
        waterYen = 3_000L,
        customerCount = 100L,
        sourceSummary = baseSources(),
        calculatedAt = Instant.parse("2026-07-29T00:00:00Z"),
    )

    private fun baseSources() = MetricSourceSummary(
        salesSourceCount = 1,
        expenseSourceCount = 4,
        foodPurchaseSourceCount = 1,
        alcoholPurchaseSourceCount = 1,
        fixedCostSourceCount = 6,
        customerCountSourceCount = 1,
        legacyFallbackUsed = false,
        cancellationsExcluded = true,
    )

    private fun assertAmount(expected: Long, actual: AmountMetricResult) {
        assertEquals(MetricAvailability.AVAILABLE, actual.availability)
        assertEquals(MetricValue.Amount(expected), actual.value)
    }

    private fun assertRatio(expected: String, actual: RatioMetricResult) {
        assertEquals(MetricAvailability.AVAILABLE, actual.availability)
        assertEquals(BigDecimal(expected), actual.value?.value)
    }
}
