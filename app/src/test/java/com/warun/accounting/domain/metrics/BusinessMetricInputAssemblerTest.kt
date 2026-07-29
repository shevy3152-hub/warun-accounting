package com.warun.accounting.domain.metrics

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessMetricInputAssemblerTest {
    @Test
    fun sameDayAllocatedUtilitiesSuppressLegacyUtilities() {
        val input = successInput(
            snapshot(
                reports = listOf(
                    report(
                        date = "2026-07-01",
                        electricity = 1_000L,
                        legacyUtilities = 2_000L,
                    ),
                ),
            ),
        )

        assertEquals(1_000L, input.electricityYen)
        assertNull(input.unallocatedUtilitiesYen)
        assertEquals(1, input.sourceSummary.fixedCostSourceCount)
        assertFalse(
            MetricWarning.UNALLOCATED_UTILITIES_USED in
                BusinessMetricCalculator.calculate(input).recordedFixedCostEquivalent.warnings,
        )
    }

    @Test
    fun allocatedAndUnallocatedUtilitiesFromDifferentDatesCoexist() {
        val input = successInput(
            snapshot(
                reports = listOf(
                    report(date = "2026-07-01", electricity = 1_000L),
                    report(date = "2026-07-02", legacyUtilities = 2_000L),
                ),
            ),
        )

        assertEquals(1_000L, input.electricityYen)
        assertNull(input.gasYen)
        assertNull(input.waterYen)
        assertEquals(2_000L, input.unallocatedUtilitiesYen)
        assertEquals(2, input.sourceSummary.fixedCostSourceCount)
        val fixedCost = BusinessMetricCalculator.calculate(input).recordedFixedCostEquivalent
        assertEquals(MetricValue.Amount(3_000L), fixedCost.value)
        assertTrue(MetricWarning.UNALLOCATED_UTILITIES_USED in fixedCost.warnings)
    }

    @Test
    fun multipleUnallocatedUtilityDatesAreSummedWithoutSplitting() {
        val input = successInput(
            snapshot(
                reports = listOf(
                    report(date = "2026-07-02", legacyUtilities = 2_000L),
                    report(date = "2026-07-03", legacyUtilities = 3_000L),
                ),
            ),
        )

        assertEquals(5_000L, input.unallocatedUtilitiesYen)
        assertNull(input.electricityYen)
        assertNull(input.gasYen)
        assertNull(input.waterYen)
        assertEquals(2, input.sourceSummary.fixedCostSourceCount)
        assertEquals(
            1,
            BusinessMetricCalculator.calculate(input).recordedFixedCostEquivalent.warnings
                .count { it == MetricWarning.UNALLOCATED_UTILITIES_USED },
        )
    }

    @Test
    fun allocatedUtilitySourcesAreCountedPerPositiveComponent() {
        val input = successInput(
            snapshot(
                reports = listOf(
                    report(date = "2026-07-01", electricity = 1_000L),
                    report(date = "2026-07-02", gas = 500L, water = 300L),
                ),
            ),
        )

        assertEquals(1_000L, input.electricityYen)
        assertEquals(500L, input.gasYen)
        assertEquals(300L, input.waterYen)
        assertNull(input.unallocatedUtilitiesYen)
        assertEquals(3, input.sourceSummary.fixedCostSourceCount)
    }

    @Test
    fun duplicateDailyReportDateIsRejectedWithoutPartialInput() {
        val result = assemble(
            snapshot(
                reports = listOf(
                    report(id = "one", date = "2026-07-01"),
                    report(id = "two", date = "2026-07-01"),
                ),
            ),
        )

        assertEquals(
            BusinessMetricInputAssemblyResult.InconsistentSnapshot(
                setOf(AssemblyFailureReason.DUPLICATE_DAILY_REPORT_DATE),
            ),
            result,
        )
    }

    @Test
    fun negativeValuesReturnExplicitFailures() {
        val negative = assemble(
            snapshot(
                reports = listOf(
                    report(
                        sales = -1L,
                        customers = -1L,
                        rent = -1L,
                        legacyFood = -1L,
                    ),
                ),
                expenses = listOf(expense(amount = -1L)),
            ),
        ) as BusinessMetricInputAssemblyResult.InvalidSourceData

        assertEquals(
            setOf(
                AssemblyFailureReason.NEGATIVE_SALES,
                AssemblyFailureReason.NEGATIVE_EXPENSE,
                AssemblyFailureReason.NEGATIVE_FIXED_COST,
                AssemblyFailureReason.NEGATIVE_CUSTOMER_COUNT,
            ),
            negative.reasons,
        )
    }

    @Test
    fun zeroFixedCostsAndCustomersRemainUnknownSources() {
        val input = successInput(snapshot(reports = listOf(report())))

        assertNull(input.rentYen)
        assertNull(input.communicationYen)
        assertNull(input.accountantFeeYen)
        assertNull(input.unallocatedUtilitiesYen)
        assertEquals(0, input.sourceSummary.fixedCostSourceCount)
        assertNull(input.customerCount)
        assertEquals(0, input.sourceSummary.customerCountSourceCount)
        val customerMetric = BusinessMetricCalculator.calculate(input).averageSpendPerCustomer
        assertEquals(MetricAvailability.INSUFFICIENT_DATA, customerMetric.availability)
        assertTrue(MetricWarning.CUSTOMER_COUNT_ZERO_OR_UNKNOWN in customerMetric.warnings)
    }

    @Test
    fun activeCancellationAndLegacySelectionUsesSharedFallbackContract() {
        val input = successInput(
            snapshot(
                reports = listOf(
                    report(
                        sales = 10_000L,
                        legacyFood = 9_000L,
                        legacyAlcohol = 4_000L,
                        legacyConsumables = 3_000L,
                    ),
                ),
                expenses = listOf(
                    expense(
                        id = "active-food",
                        category = MetricExpenseCategory.FOOD_PURCHASE,
                        amount = 2_000L,
                    ),
                    expense(
                        id = "cancelled-consumables",
                        category = MetricExpenseCategory.CONSUMABLES,
                        amount = 3_000L,
                        cancelled = true,
                    ),
                ),
            ),
        )

        assertEquals(6_000L, input.recordedExpensesYen)
        assertEquals(2_000L, input.foodPurchasesYen)
        assertEquals(4_000L, input.alcoholPurchasesYen)
        assertEquals(2, input.sourceSummary.expenseSourceCount)
        assertEquals(1, input.sourceSummary.foodPurchaseSourceCount)
        assertEquals(1, input.sourceSummary.alcoholPurchaseSourceCount)
        assertTrue(input.sourceSummary.legacyFallbackUsed)
        assertTrue(input.sourceSummary.cancellationsExcluded)
    }

    @Test
    fun cancelledRowsAreNotCountedAsSources() {
        val input = successInput(
            snapshot(
                reports = listOf(report(legacyFood = 5_000L)),
                expenses = listOf(
                    expense(
                        category = MetricExpenseCategory.FOOD_PURCHASE,
                        amount = 5_000L,
                        cancelled = true,
                    ),
                ),
            ),
        )

        assertNull(input.recordedExpensesYen)
        assertNull(input.foodPurchasesYen)
        assertEquals(0, input.sourceSummary.expenseSourceCount)
        assertEquals(0, input.sourceSummary.foodPurchaseSourceCount)
        assertFalse(input.sourceSummary.legacyFallbackUsed)
        assertTrue(input.sourceSummary.cancellationsExcluded)
    }

    @Test
    fun legacyUtilitiesDoNotChangeExpenseCategoryFallbackFlag() {
        val input = successInput(
            snapshot(
                reports = listOf(report(legacyUtilities = 2_000L)),
            ),
        )

        assertFalse(input.sourceSummary.legacyFallbackUsed)
        assertEquals(1, input.sourceSummary.fixedCostSourceCount)
        assertEquals(0, input.sourceSummary.expenseSourceCount)
        assertEquals(1, input.sourceSummary.directExpenseSourceCount)
    }

    @Test
    fun zeroAmountActiveExpenseIsStillAContributingSource() {
        val input = successInput(
            snapshot(
                reports = listOf(report(legacyFood = 9_000L)),
                expenses = listOf(
                    expense(
                        category = MetricExpenseCategory.FOOD_PURCHASE,
                        amount = 0L,
                    ),
                ),
            ),
        )

        assertEquals(0L, input.recordedExpensesYen)
        assertEquals(0L, input.foodPurchasesYen)
        assertEquals(1, input.sourceSummary.expenseSourceCount)
        assertEquals(1, input.sourceSummary.foodPurchaseSourceCount)
        assertFalse(input.sourceSummary.legacyFallbackUsed)
    }

    @Test
    fun expenseWithoutDailyReportIsIncludedOnce() {
        val input = successInput(
            snapshot(
                reports = emptyList(),
                expenses = listOf(
                    expense(
                        date = "2026-07-10",
                        category = MetricExpenseCategory.OTHER,
                        amount = 700L,
                    ),
                ),
            ),
        )

        assertNull(input.recordedSalesYen)
        assertEquals(700L, input.recordedExpensesYen)
        assertEquals(1, input.sourceSummary.expenseSourceCount)
    }

    @Test
    fun outOfPeriodSourcesAreExcludedBeforeValueValidation() {
        val input = successInput(
            snapshot(
                reports = listOf(
                    report(id = "inside", date = "2026-07-01", sales = 100L),
                    report(id = "outside", date = "2026-08-01", sales = -1L),
                ),
                expenses = listOf(
                    expense(id = "outside", date = "2026-08-01", amount = -1L),
                ),
            ),
        )

        assertEquals(100L, input.recordedSalesYen)
        assertNull(input.recordedExpensesYen)
    }

    @Test
    fun duplicateExpenseIdMakesVisibilitySnapshotInconsistent() {
        val result = assemble(
            snapshot(
                expenses = listOf(
                    expense(id = "duplicate", cancelled = false),
                    expense(id = "duplicate", cancelled = true),
                ),
            ),
        )

        assertEquals(
            BusinessMetricInputAssemblyResult.InconsistentSnapshot(
                setOf(AssemblyFailureReason.VISIBILITY_SNAPSHOT_INCONSISTENT),
            ),
            result,
        )
    }

    @Test
    fun reportSourceCountsAndZeroSalesPreserveRecordedZero() {
        val input = successInput(
            snapshot(
                reports = listOf(
                    report(id = "one", date = "2026-07-01", sales = 0L, customers = 2L),
                    report(id = "two", date = "2026-07-02", sales = 500L, customers = 3L),
                    report(id = "unknown", date = "2026-07-03", sales = 0L, customers = 0L),
                ),
            ),
        )

        assertEquals(500L, input.recordedSalesYen)
        assertEquals(3, input.sourceSummary.salesSourceCount)
        assertEquals(5L, input.customerCount)
        assertEquals(2, input.sourceSummary.customerCountSourceCount)
        val averageSpend = BusinessMetricCalculator.calculate(input).averageSpendPerCustomer
        assertEquals(100L, averageSpend.value?.yen)
        assertTrue(MetricWarning.CUSTOMER_COUNT_ZERO_OR_UNKNOWN in averageSpend.warnings)
    }

    @Test
    fun fixedCostAndCustomerOverflowReturnOverflow() {
        val fixedOverflow = assemble(
            snapshot(
                reports = listOf(
                    report(id = "one", date = "2026-07-01", rent = Long.MAX_VALUE),
                    report(id = "two", date = "2026-07-02", rent = 1L),
                ),
            ),
        )
        val customerOverflow = assemble(
            snapshot(
                reports = listOf(
                    report(id = "one", date = "2026-07-01", customers = Long.MAX_VALUE),
                    report(id = "two", date = "2026-07-02", customers = 1L),
                ),
            ),
        )

        listOf(fixedOverflow, customerOverflow).forEach {
            assertEquals(
                BusinessMetricInputAssemblyResult.Overflow(
                    setOf(AssemblyFailureReason.AMOUNT_OVERFLOW),
                ),
                it,
            )
        }
    }

    @Test
    fun dailyMonthlyLeapMonthAndCustomRangeExtractByInclusiveDates() {
        val reports = listOf(
            report(id = "feb28", date = "2028-02-28", sales = 100L),
            report(id = "feb29", date = "2028-02-29", sales = 200L),
            report(id = "mar01", date = "2028-03-01", sales = 400L),
        )
        val monthly = successInput(
            snapshot(
                period = MetricPeriod.Monthly(YearMonth.of(2028, 2)),
                reports = reports,
                evaluationDate = LocalDate.of(2028, 3, 1),
            ),
        )
        val daily = successInput(
            snapshot(
                period = MetricPeriod.Daily(LocalDate.of(2028, 2, 29)),
                reports = reports,
                evaluationDate = LocalDate.of(2028, 3, 1),
            ),
        )
        val custom = successInput(
            snapshot(
                period = MetricPeriod.CustomRange(
                    LocalDate.of(2028, 2, 29),
                    LocalDate.of(2028, 3, 1),
                ),
                reports = reports,
                evaluationDate = LocalDate.of(2028, 3, 2),
            ),
        )

        assertEquals(300L, monthly.recordedSalesYen)
        assertEquals(200L, daily.recordedSalesYen)
        assertEquals(600L, custom.recordedSalesYen)
        assertTrue(monthly.periodState.isComplete)
        assertTrue(daily.periodState.isComplete)
        assertTrue(custom.periodState.isComplete)
    }

    @Test
    fun currentAndFuturePeriodsUseExternalEvaluationDate() {
        val current = successInput(
            snapshot(
                period = MetricPeriod.Daily(LocalDate.of(2026, 7, 30)),
                reports = listOf(report(date = "2026-07-30")),
                evaluationDate = LocalDate.of(2026, 7, 30),
            ),
        )
        val future = successInput(
            snapshot(
                period = MetricPeriod.Daily(LocalDate.of(2026, 7, 31)),
                reports = listOf(report(date = "2026-07-31")),
                evaluationDate = LocalDate.of(2026, 7, 30),
            ),
        )

        assertFalse(current.periodState.isComplete)
        assertFalse(current.periodState.containsFutureDatedData)
        assertFalse(future.periodState.isComplete)
        assertTrue(future.periodState.containsFutureDatedData)
    }

    @Test
    fun amountOverflowReturnsOverflowWithoutInput() {
        val result = assemble(
            snapshot(
                reports = listOf(
                    report(id = "one", date = "2026-07-01", sales = Long.MAX_VALUE),
                    report(id = "two", date = "2026-07-02", sales = 1L),
                ),
            ),
        )

        assertEquals(
            BusinessMetricInputAssemblyResult.Overflow(
                setOf(AssemblyFailureReason.AMOUNT_OVERFLOW),
            ),
            result,
        )
    }

    @Test
    fun assemblerAndCalculatorProduceDeterministicMonthlyMetrics() {
        val snapshot = snapshot(
            reports = listOf(
                report(
                    date = "2026-06-01",
                    sales = 100_000L,
                    customers = 100L,
                    legacyFood = 20_000L,
                    legacyAlcohol = 10_000L,
                    rent = 10_000L,
                    communication = 5_000L,
                    accountant = 5_000L,
                    electricity = 5_000L,
                    gas = 2_000L,
                    water = 3_000L,
                ),
            ),
            period = MetricPeriod.Monthly(YearMonth.of(2026, 6)),
            evaluationDate = LocalDate.of(2026, 7, 1),
        )

        val firstInput = successInput(snapshot)
        val secondInput = successInput(snapshot)
        val first = BusinessMetricCalculator.calculate(firstInput)
        val second = BusinessMetricCalculator.calculate(secondInput)

        assertEquals(firstInput, secondInput)
        assertEquals(first, second)
        assertEquals(MetricValue.Amount(30_000L), first.referenceCost.value)
        assertEquals(MetricValue.Amount(30_000L), first.recordedFixedCostEquivalent.value)
        assertEquals(MetricValue.Amount(1_000L), first.averageSpendPerCustomer.value)
        assertEquals(2, firstInput.sourceSummary.expenseSourceCount)
        assertEquals(6, firstInput.sourceSummary.directExpenseSourceCount)
        assertEquals(8, firstInput.sourceSummary.recordedExpenseSourceCount)
        assertEquals(snapshot.calculatedAt, first.calculatedAt)
    }

    private fun successInput(snapshot: BusinessMetricSourceSnapshot): BusinessMetricInput {
        val result = assemble(snapshot)
        assertTrue("Expected Success but was $result", result is BusinessMetricInputAssemblyResult.Success)
        return (result as BusinessMetricInputAssemblyResult.Success).input
    }

    private fun assemble(snapshot: BusinessMetricSourceSnapshot) =
        BusinessMetricInputAssembler.assemble(snapshot)

    private fun snapshot(
        reports: List<MetricDailyReportSource> = emptyList(),
        expenses: List<MetricExpenseVisibilitySource> = emptyList(),
        period: MetricPeriod = MetricPeriod.Monthly(YearMonth.of(2026, 7)),
        evaluationDate: LocalDate = LocalDate.of(2026, 8, 1),
    ) = BusinessMetricSourceSnapshot(
        period = period,
        reports = reports,
        expenseVisibility = expenses,
        evaluationDate = evaluationDate,
        calculatedAt = Instant.parse("2026-07-30T12:00:00Z"),
    )

    private fun report(
        id: String = "report",
        date: String = "2026-07-01",
        sales: Long = 0L,
        customers: Long = 0L,
        legacyFood: Long = 0L,
        legacyAlcohol: Long = 0L,
        legacyConsumables: Long = 0L,
        rent: Long = 0L,
        communication: Long = 0L,
        accountant: Long = 0L,
        electricity: Long = 0L,
        gas: Long = 0L,
        water: Long = 0L,
        legacyUtilities: Long = 0L,
    ) = MetricDailyReportSource(
        id = id,
        reportDate = LocalDate.parse(date),
        salesYen = sales,
        customerCount = customers,
        legacyFoodPurchasesYen = legacyFood,
        legacyAlcoholPurchasesYen = legacyAlcohol,
        legacyConsumablesExpenseYen = legacyConsumables,
        rentExpenseYen = rent,
        communicationExpenseYen = communication,
        accountantFeeExpenseYen = accountant,
        electricityExpenseYen = electricity,
        gasExpenseYen = gas,
        waterExpenseYen = water,
        legacyUtilitiesExpenseYen = legacyUtilities,
    )

    private fun expense(
        id: String = "expense",
        date: String = "2026-07-01",
        category: MetricExpenseCategory = MetricExpenseCategory.CONSUMABLES,
        amount: Long = 1_000L,
        cancelled: Boolean = false,
    ) = MetricExpenseVisibilitySource(
        expense = MetricExpenseSource(
            id = id,
            expenseDate = LocalDate.parse(date),
            category = category,
            amountYen = amount,
        ),
        isCancelled = cancelled,
    )
}
