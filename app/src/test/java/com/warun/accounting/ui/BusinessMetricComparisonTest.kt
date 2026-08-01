package com.warun.accounting.ui

import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.domain.metrics.AmountMetricResult
import com.warun.accounting.domain.metrics.BusinessMetricCalculationCoordinator
import com.warun.accounting.domain.metrics.BusinessMetricCalculationResult
import com.warun.accounting.domain.metrics.BusinessMetricSourceSnapshot
import com.warun.accounting.domain.metrics.MetricDailyReportSource
import com.warun.accounting.domain.metrics.MetricExpenseCategoryMapper
import com.warun.accounting.domain.metrics.MetricExpenseCategoryMappingResult
import com.warun.accounting.domain.metrics.MetricExpenseSource
import com.warun.accounting.domain.metrics.MetricExpenseVisibilitySource
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.domain.metrics.MetricValue
import com.warun.accounting.ui.model.BusinessAnalysisCalculationStatus
import com.warun.accounting.util.ExpenseDateCategoryKey
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test-only comparison boundary. It deliberately does not feed either result back into
 * production UI state or replace the legacy calculations.
 */
class BusinessMetricComparisonTest {
    @Test
    fun basicNormalFixtureMatchesForSharedRecordedIndicators() {
        val date = "2026-07-01"
        val reports = listOf(report(date, sales = 10_000L, rent = 1_000L))
        val expenses = listOf(
            expense("food", date, ExpenseCategory.FoodPurchase, 1_500L),
            expense("alcohol", date, ExpenseCategory.AlcoholPurchase, 500L),
        )
        val comparison = compare(
            reports,
            expenses,
            emptyList(),
            MetricPeriod.Daily(LocalDate.parse(date)),
            expectedDifferences = mapOf(
                ComparisonMetric.BREAK_EVEN to "Daily periods are not eligible for the new completed-calendar-month break-even contract",
            ),
        )

        assertEquals(ComparisonDisposition.MATCH, comparison.sales.disposition)
        assertEquals(ComparisonDisposition.MATCH, comparison.expenses.disposition)
        assertEquals(ComparisonDisposition.MATCH, comparison.referenceCost.disposition)
        assertEquals(ComparisonDisposition.MATCH, comparison.grossProfit.disposition)
        assertEquals(ComparisonDisposition.MATCH, comparison.referenceCostRate.disposition)
        assertEquals(ComparisonDisposition.MATCH, comparison.fixedCost.disposition)
        assertExpectedDifference(comparison.breakEven)
    }

    @Test
    fun activeExpenseWinsOverLegacyForConsumables() {
        val date = "2026-07-02"
        val reports = listOf(report(date, sales = 10_000L, legacyConsumables = 4_000L))
        val active = listOf(expense("active", date, ExpenseCategory.Consumables, 1_000L))
        val comparison = compare(
            reports,
            active,
            emptyList(),
            MetricPeriod.Daily(LocalDate.parse(date)),
            expectedDifferences = unavailableMetricDifferences(
                ComparisonMetric.REFERENCE_COST,
                ComparisonMetric.GROSS_PROFIT,
                ComparisonMetric.REFERENCE_COST_RATE,
                ComparisonMetric.FIXED_COST,
                ComparisonMetric.BREAK_EVEN,
            ),
        )

        assertEquals(1_000L, comparison.old.expenseTotal)
        assertEquals(1_000L, comparison.newReport.recordedExpenses)
        assertEquals(ComparisonDisposition.MATCH, comparison.expenses.disposition)
    }

    @Test
    fun cancellationOnlySuppressesLegacyInsteadOfRevivingIt() {
        val date = "2026-07-03"
        val cancelled = listOf(expense("cancelled", date, ExpenseCategory.Consumables, 1_000L))
        val comparison = compare(
            reports = listOf(report(date, sales = 10_000L, legacyConsumables = 4_000L)),
            activeExpenses = emptyList(),
            cancelledExpenses = cancelled,
            period = MetricPeriod.Daily(LocalDate.parse(date)),
            expectedDifferences = mapOf(
                ComparisonMetric.EXPENSES to "The legacy path displays cancellation-only expense as 0, while the domain path uses null for missing source data",
                ComparisonMetric.REFERENCE_COST to "The new reference-cost contract reports unavailable when no food/alcohol source exists",
                ComparisonMetric.GROSS_PROFIT to "The new gross-profit contract depends on an available reference-cost source",
                ComparisonMetric.REFERENCE_COST_RATE to "The new ratio contract reports unavailable when reference cost is unavailable",
                ComparisonMetric.FIXED_COST to "The new fixed-cost contract reports unavailable when no fixed-cost source exists",
                ComparisonMetric.BREAK_EVEN to "The new break-even contract reports unavailable for this Daily period",
            ),
        )

        assertEquals(0L, comparison.old.expenseTotal)
        assertEquals(null, comparison.newReport.recordedExpenses)
        assertExpectedDifference(comparison.expenses)
    }

    @Test
    fun activeAndCancelledSameCategoryCountActiveOnly() {
        val date = "2026-07-04"
        val active = listOf(expense("active", date, ExpenseCategory.Consumables, 500L))
        val cancelled = listOf(expense("cancelled", date, ExpenseCategory.Consumables, 700L))
        val comparison = compare(
            reports = listOf(report(date, sales = 10_000L)),
            activeExpenses = active,
            cancelledExpenses = cancelled,
            period = MetricPeriod.Daily(LocalDate.parse(date)),
            expectedDifferences = unavailableMetricDifferences(
                ComparisonMetric.REFERENCE_COST,
                ComparisonMetric.GROSS_PROFIT,
                ComparisonMetric.REFERENCE_COST_RATE,
                ComparisonMetric.FIXED_COST,
                ComparisonMetric.BREAK_EVEN,
            ),
        )

        assertEquals(500L, comparison.old.expenseTotal)
        assertEquals(500L, comparison.newReport.recordedExpenses)
        assertEquals(ComparisonDisposition.MATCH, comparison.expenses.disposition)
    }

    @Test
    fun legacyFallbackMatchesForConsumablesButLegacyFoodIsAnExpectedDifference() {
        val date = "2026-07-05"
        val consumables = compare(
            reports = listOf(report(date, sales = 10_000L, legacyConsumables = 2_000L)),
            activeExpenses = emptyList(),
            cancelledExpenses = emptyList(),
            period = MetricPeriod.Daily(LocalDate.parse(date)),
            expectedDifferences = unavailableMetricDifferences(
                ComparisonMetric.REFERENCE_COST,
                ComparisonMetric.GROSS_PROFIT,
                ComparisonMetric.REFERENCE_COST_RATE,
                ComparisonMetric.FIXED_COST,
                ComparisonMetric.BREAK_EVEN,
            ),
        )
        assertEquals(2_000L, consumables.old.expenseTotal)
        assertEquals(2_000L, consumables.newReport.recordedExpenses)
        assertEquals(ComparisonDisposition.MATCH, consumables.expenses.disposition)

        val legacyFood = compare(
            reports = listOf(report(date, sales = 10_000L, legacyFood = 2_000L)),
            activeExpenses = emptyList(),
            cancelledExpenses = emptyList(),
            period = MetricPeriod.Daily(LocalDate.parse(date)),
            expectedDifferences = mapOf(
                ComparisonMetric.EXPENSES to "The legacy UI falls back only for consumables; the shared domain fallback also applies to food",
                ComparisonMetric.REFERENCE_COST to "The new reference-cost contract reports unavailable when no food/alcohol source exists",
                ComparisonMetric.GROSS_PROFIT to "The new gross-profit contract depends on an available reference-cost source",
                ComparisonMetric.REFERENCE_COST_RATE to "The new ratio contract reports unavailable when reference cost is unavailable",
                ComparisonMetric.FIXED_COST to "The new fixed-cost contract reports unavailable when no fixed-cost source exists",
                ComparisonMetric.BREAK_EVEN to "The new break-even contract reports unavailable for this Daily period",
            ),
        )
        // EXPECTED_DIFFERENCE: the legacy UI only falls back for consumables; C1 applies the
        // shared legacy fallback contract to food and alcohol as well.
        assertEquals(0L, legacyFood.old.expenseTotal)
        assertEquals(2_000L, legacyFood.newReport.recordedExpenses)
        assertExpectedDifference(legacyFood.expenses)
    }

    @Test
    fun reportlessExpenseIsIncludedOnceButFixedCostDefinitionsDiffer() {
        val date = "2026-07-06"
        val active = listOf(expense("food", date, ExpenseCategory.FoodPurchase, 3_000L))
        val comparison = compare(
            reports = emptyList(),
            activeExpenses = active,
            cancelledExpenses = emptyList(),
            period = MetricPeriod.Daily(LocalDate.parse(date)),
            expectedDifferences = mapOf(
                ComparisonMetric.FIXED_COST to "The legacy simpleFixedCost derives from total-minus-reference-cost; the new fixed-cost metric requires fixed-cost sources",
                ComparisonMetric.SALES to "The legacy balance summary represents an expense-only period with a zero sales total; the new sales result has no DailyReport source",
            ),
        )

        assertEquals(3_000L, comparison.old.expenseTotal)
        assertEquals(3_000L, comparison.newReport.recordedExpenses)
        assertEquals(3_000L, comparison.old.businessAnalysis.estimatedCost)
        assertEquals(3_000L, comparison.newReport.referenceCost)
        // EXPECTED_DIFFERENCE: the old simpleFixedCost is derived as total minus cost, while the
        // new management fixed-cost metric requires recorded fixed-cost sources.
        assertExpectedDifference(comparison.fixedCost)
    }

    @Test
    fun fixedCostCategoriesMatchAndDailyBreakEvenIsUnavailable() {
        val date = "2026-07-07"
        val reports = listOf(
            report(
                date = date,
                sales = 10_000L,
                rent = 1_000L,
                communication = 500L,
                accountant = 300L,
                electricity = 200L,
                gas = 100L,
                water = 50L,
            ),
            report("2026-07-08", sales = 0L),
        )
        val comparison = compare(
            reports = reports,
            activeExpenses = listOf(
                expense("food", date, ExpenseCategory.FoodPurchase, 1_000L),
                expense("alcohol", date, ExpenseCategory.AlcoholPurchase, 500L),
            ),
            cancelledExpenses = emptyList(),
            period = MetricPeriod.Daily(LocalDate.parse(date)),
            expectedDifferences = mapOf(
                ComparisonMetric.BREAK_EVEN to "The new break-even result is null because a Daily period is not a completed calendar month",
            ),
        )

        assertEquals(2_150L, comparison.old.businessAnalysis.simpleFixedCost)
        assertEquals(2_150L, comparison.newReport.fixedCost)
        assertEquals(ComparisonDisposition.MATCH, comparison.fixedCost.disposition)
        // EXPECTED_DIFFERENCE: the new result is unavailable for this Daily period rather than
        // a rounding variant of the legacy value.
        assertEquals(2_529L, comparison.old.businessAnalysis.estimatedBreakEvenSales)
        assertEquals(null, comparison.newReport.breakEvenSales)
        assertExpectedDifference(comparison.breakEven)
    }

    @Test
    fun allFiveCategoriesKeepRecordedExpenseParityButOnlyFoodAndAlcoholAreReferenceCost() {
        val date = "2026-07-09"
        val expenses = listOf(
            expense("food", date, ExpenseCategory.FoodPurchase, 100L),
            expense("alcohol", date, ExpenseCategory.AlcoholPurchase, 200L),
            expense("consumables", date, ExpenseCategory.Consumables, 300L),
            expense("other", date, ExpenseCategory.OtherExpense, 400L),
            expense("vehicle", date, ExpenseCategory.VehicleTransport, 500L),
        )
        val comparison = compare(
            reports = listOf(report(date, sales = 10_000L)),
            activeExpenses = expenses,
            cancelledExpenses = emptyList(),
            period = MetricPeriod.Daily(LocalDate.parse(date)),
            expectedDifferences = mapOf(
                ComparisonMetric.FIXED_COST to "The legacy simpleFixedCost includes non-reference expenses; the new fixed-cost metric excludes them and has no fixed-cost source",
                ComparisonMetric.BREAK_EVEN to "The new break-even contract reports unavailable for this Daily period",
            ),
        )

        assertEquals(1_500L, comparison.old.expenseTotal)
        assertEquals(1_500L, comparison.newReport.recordedExpenses)
        assertEquals(300L, comparison.newReport.referenceCost)
        // EXPECTED_DIFFERENCE: old simpleFixedCost includes all non-food/alcohol ExpenseRecords;
        // the new fixed-cost equivalent only includes recorded fixed-cost categories.
        assertExpectedDifference(comparison.fixedCost)
    }

    @Test
    fun zeroDataDistinguishesUnavailableNewSourcesFromLegacyUiZeros() {
        val comparison = compare(
            reports = emptyList(),
            activeExpenses = emptyList(),
            cancelledExpenses = emptyList(),
            period = MetricPeriod.Daily(LocalDate.parse("2026-07-10")),
            expectedDifferences = mapOf(
                ComparisonMetric.SALES to "The legacy UI represents no sales as 0, while the domain contract represents absent sales as null",
                ComparisonMetric.EXPENSES to "The new expense result reports unavailable when there is no expense source",
                ComparisonMetric.REFERENCE_COST to "The new reference-cost result reports unavailable when there is no source",
                ComparisonMetric.GROSS_PROFIT to "The new gross-profit result depends on an available reference-cost source",
                ComparisonMetric.FIXED_COST to "The new fixed-cost result reports unavailable when there is no source",
            ),
        )

        assertEquals(0L, comparison.old.salesTotal)
        assertEquals(null, comparison.newReport.recordedSales)
        assertEquals(BusinessAnalysisCalculationStatus.NoSales, comparison.old.businessAnalysis.calculationStatus)
        assertExpectedDifference(comparison.sales)
    }

    @Test
    fun customRangeIncludesBothBoundariesAndUsesExpenseDate() {
        val period = MetricPeriod.CustomRange(
            startDate = LocalDate.parse("2026-07-11"),
            endDateInclusive = LocalDate.parse("2026-07-12"),
        )
        val reports = listOf(
            report("2026-07-11", sales = 1_000L),
            report("2026-07-12", sales = 2_000L),
            report("2026-07-13", sales = 9_000L),
        )
        val expenses = listOf(
            expense("inside", "2026-07-12", ExpenseCategory.FoodPurchase, 300L),
            expense("outside", "2026-07-13", ExpenseCategory.FoodPurchase, 900L),
        )
        val comparison = compare(
            reports,
            expenses,
            emptyList(),
            period,
            expectedDifferences = unavailableMetricDifferences(
                ComparisonMetric.FIXED_COST,
                ComparisonMetric.BREAK_EVEN,
            ),
        )

        assertEquals(3_000L, comparison.newReport.recordedSales)
        assertEquals(300L, comparison.newReport.recordedExpenses)
        assertEquals(3_000L, comparison.old.salesTotal)
        assertEquals(300L, comparison.old.expenseTotal)
        assertEquals(ComparisonDisposition.MATCH, comparison.sales.disposition)
        assertEquals(ComparisonDisposition.MATCH, comparison.expenses.disposition)
    }

    @Test
    fun cancellationDateIsNotAComparableMetricBecauseBothPathsUseExpenseDate() {
        val date = "2026-07-14"
        val cancelled = expense("cancelled", date, ExpenseCategory.FoodPurchase, 500L)
        val comparison = compare(
            reports = listOf(report(date, sales = 2_000L, legacyFood = 1_000L)),
            activeExpenses = emptyList(),
            cancelledExpenses = listOf(cancelled),
            period = MetricPeriod.Daily(LocalDate.parse(date)),
            notComparable = mapOf(
                ComparisonMetric.EXPENSES to "Cancellation date is not part of either aggregation input; both use the original expenseDate",
            ),
            expectedDifferences = unavailableMetricDifferences(
                ComparisonMetric.REFERENCE_COST,
                ComparisonMetric.GROSS_PROFIT,
                ComparisonMetric.REFERENCE_COST_RATE,
                ComparisonMetric.FIXED_COST,
                ComparisonMetric.BREAK_EVEN,
            ),
        )

        // NOT_COMPARABLE: cancellationDate is not part of either aggregation input; the shared
        // contract is keyed by the original ExpenseRecord.expenseDate.
        assertEquals(null, comparison.newReport.recordedExpenses)
        assertEquals(ComparisonDisposition.NOT_COMPARABLE, comparison.expenses.disposition)
        assertTrue(!comparison.expenses.reason.isNullOrBlank())
        assertEquals(0L, comparison.old.expenseTotal)
    }

    @Test
    fun monthlyFixtureIncludesMonthBoundariesAndExcludesAdjacentDates() {
        val period = MetricPeriod.Monthly(YearMonth.of(2026, 7))
        val reports = listOf(
            report("2026-06-30", sales = 900L),
            report("2026-07-01", sales = 1_000L),
            report("2026-07-31", sales = 2_000L),
            report("2026-08-01", sales = 800L),
        )
        val expenses = listOf(
            expense("outside-before", "2026-06-30", ExpenseCategory.FoodPurchase, 90L),
            expense("inside-start", "2026-07-01", ExpenseCategory.FoodPurchase, 100L),
            expense("inside-end", "2026-07-31", ExpenseCategory.AlcoholPurchase, 200L),
            expense("outside-after", "2026-08-01", ExpenseCategory.FoodPurchase, 80L),
        )
        val comparison = compare(
            reports,
            expenses,
            emptyList(),
            period,
            expectedDifferences = unavailableMetricDifferences(
                ComparisonMetric.FIXED_COST,
                ComparisonMetric.BREAK_EVEN,
            ),
        )

        assertEquals(3_000L, comparison.old.salesTotal)
        assertEquals(3_000L, comparison.newReport.recordedSales)
        assertEquals(300L, comparison.old.expenseTotal)
        assertEquals(300L, comparison.newReport.recordedExpenses)
        assertEquals(ComparisonDisposition.MATCH, comparison.sales.disposition)
        assertEquals(ComparisonDisposition.MATCH, comparison.expenses.disposition)
    }

    @Test
    fun safeLargeLongAmountsRemainEqualWithoutFloatingPointConversion() {
        val date = "2026-07-15"
        val sales = 4_000_000_000_000L
        val amount = 1_000_000_000_000L
        val comparison = compare(
            reports = listOf(report(date, sales = sales)),
            activeExpenses = listOf(expense("food", date, ExpenseCategory.FoodPurchase, amount)),
            cancelledExpenses = emptyList(),
            period = MetricPeriod.Daily(LocalDate.parse(date)),
            expectedDifferences = mapOf(
                ComparisonMetric.FIXED_COST to "The new fixed-cost result reports unavailable when no fixed-cost source exists",
                ComparisonMetric.BREAK_EVEN to "The new break-even contract reports unavailable for this Daily period",
            ),
        )

        assertEquals(sales, comparison.old.salesTotal)
        assertEquals(sales, comparison.newReport.recordedSales)
        assertEquals(amount, comparison.old.expenseTotal)
        assertEquals(amount, comparison.newReport.recordedExpenses)
        assertEquals(ComparisonDisposition.MATCH, comparison.sales.disposition)
        assertEquals(ComparisonDisposition.MATCH, comparison.expenses.disposition)
    }

    private fun compare(
        reports: List<DailyReport>,
        activeExpenses: List<ExpenseRecord>,
        cancelledExpenses: List<ExpenseRecord>,
        period: MetricPeriod,
        expectedDifferences: Map<ComparisonMetric, String> = emptyMap(),
        notComparable: Map<ComparisonMetric, String> = emptyMap(),
    ): ComparisonFixtureResult {
        val cancelledKeys = cancelledExpenses
            .map { ExpenseDateCategoryKey(it.expenseDate, it.category) }
            .toSet()
        val old = buildBalanceSummary(
            reports = reports,
            expenses = activeExpenses,
            period = period.toBalancePeriod(),
            cancelledExpenseKeys = cancelledKeys,
        )
        val visibility = (activeExpenses.map { it to false } + cancelledExpenses.map { it to true })
            .map { (expense, isCancelled) ->
                val category = MetricExpenseCategoryMapper.map(expense.category, expense.id)
                require(category is MetricExpenseCategoryMappingResult.Success)
                MetricExpenseVisibilitySource(
                    expense = MetricExpenseSource(
                        id = expense.id,
                        expenseDate = LocalDate.parse(expense.expenseDate),
                        category = category.category,
                        amountYen = expense.amount,
                    ),
                    isCancelled = isCancelled,
                )
            }
        val snapshot = BusinessMetricSourceSnapshot(
            period = period,
            reports = reports.map { it.toMetricSource() },
            expenseVisibility = visibility,
            evaluationDate = LocalDate.parse("2026-07-29"),
            calculatedAt = Instant.parse("2026-07-29T00:00:00Z"),
        )
        val result = BusinessMetricCalculationCoordinator.calculate(snapshot)
        require(result is BusinessMetricCalculationResult.Success)
        val report = result.report

        val comparisonResult = ComparisonFixtureResult(
            old = old,
            newReport = NewValues(
                recordedSales = amount(report.recordedSales),
                recordedExpenses = amount(report.recordedExpenses),
                referenceCost = amount(report.referenceCost),
                grossProfit = amount(report.approximateGrossProfit),
                referenceCostRate = ratio(report.referenceCostRate),
                fixedCost = amount(report.recordedFixedCostEquivalent),
                breakEvenSales = amount(report.referenceBreakEvenSales),
            ),
            sales = comparison(ComparisonMetric.SALES, old.salesTotal, amount(report.recordedSales), expectedDifferences, notComparable),
            expenses = comparison(ComparisonMetric.EXPENSES, old.expenseTotal, amount(report.recordedExpenses), expectedDifferences, notComparable),
            referenceCost = comparison(
                ComparisonMetric.REFERENCE_COST,
                old.businessAnalysis.estimatedCost,
                amount(report.referenceCost),
                expectedDifferences,
                notComparable,
            ),
            grossProfit = comparison(
                ComparisonMetric.GROSS_PROFIT,
                old.businessAnalysis.estimatedGrossProfit,
                amount(report.approximateGrossProfit),
                expectedDifferences,
                notComparable,
            ),
            referenceCostRate = comparison(
                ComparisonMetric.REFERENCE_COST_RATE,
                old.businessAnalysis.estimatedCostRate?.divide(
                    BigDecimal.valueOf(100L),
                    12,
                    RoundingMode.HALF_UP,
                ),
                ratio(report.referenceCostRate),
                expectedDifferences,
                notComparable,
            ),
            fixedCost = comparison(
                ComparisonMetric.FIXED_COST,
                old.businessAnalysis.simpleFixedCost,
                amount(report.recordedFixedCostEquivalent),
                expectedDifferences,
                notComparable,
            ),
            breakEven = comparison(
                ComparisonMetric.BREAK_EVEN,
                old.businessAnalysis.estimatedBreakEvenSales,
                amount(report.referenceBreakEvenSales),
                expectedDifferences,
                notComparable,
            ),
        )
        assertEquals(
            comparisonResult.unexpectedDifferences.joinToString { "${it.metric}: old=${it.old}, new=${it.new}" },
            0,
            comparisonResult.unexpectedDifferences.size,
        )
        return comparisonResult
    }

    private fun DailyReport.toMetricSource() = MetricDailyReportSource(
        id = id,
        reportDate = LocalDate.parse(reportDate),
        salesYen = cashSales + cardSales + qrSales + accountsReceivableSales + otherSales,
        customerCount = customerCount.toLong(),
        legacyFoodPurchasesYen = foodPurchases,
        legacyAlcoholPurchasesYen = alcoholPurchases,
        legacyConsumablesExpenseYen = consumablesExpense,
        rentExpenseYen = rentExpense,
        communicationExpenseYen = communicationExpense,
        accountantFeeExpenseYen = accountantFeeExpense,
        electricityExpenseYen = electricityExpense,
        gasExpenseYen = gasExpense,
        waterExpenseYen = waterExpense,
        legacyUtilitiesExpenseYen = utilitiesExpense,
        miscellaneousExpenseYen = miscellaneousExpense,
    )

    private fun MetricPeriod.toBalancePeriod() = BalancePeriod(startDate, endDateInclusive)

    private fun amount(result: AmountMetricResult): Long? =
        (result.value as? MetricValue.Amount)?.yen

    private fun ratio(result: com.warun.accounting.domain.metrics.RatioMetricResult): BigDecimal? =
        (result.value as? MetricValue.Ratio)?.value

    private fun <T> comparison(
        metric: ComparisonMetric,
        old: T,
        new: T,
        expectedDifferences: Map<ComparisonMetric, String>,
        notComparable: Map<ComparisonMetric, String>,
    ): MetricComparison<T> {
        val disposition = when {
            old == new -> ComparisonDisposition.MATCH
            metric in expectedDifferences -> ComparisonDisposition.EXPECTED_DIFFERENCE
            metric in notComparable -> ComparisonDisposition.NOT_COMPARABLE
            else -> ComparisonDisposition.UNEXPECTED_DIFFERENCE
        }
        return MetricComparison(
            metric = metric,
            old = old,
            new = new,
            disposition = disposition,
            reason = expectedDifferences[metric] ?: notComparable[metric],
        )
    }

    private fun unavailableMetricDifferences(
        vararg metrics: ComparisonMetric,
    ): Map<ComparisonMetric, String> = metrics.associateWith {
        "The new metric reports unavailable because its required source is absent"
    }

    private fun assertExpectedDifference(metric: MetricComparison<*>) {
        assertEquals(ComparisonDisposition.EXPECTED_DIFFERENCE, metric.disposition)
        assertTrue("expected-difference reason must be retained", !metric.reason.isNullOrBlank())
    }

    private fun report(
        date: String,
        sales: Long,
        rent: Long = 0L,
        communication: Long = 0L,
        accountant: Long = 0L,
        electricity: Long = 0L,
        gas: Long = 0L,
        water: Long = 0L,
        legacyFood: Long = 0L,
        legacyConsumables: Long = 0L,
    ) = DailyReport(
        id = "report-$date",
        reportDate = date,
        status = DailyReportStatus.Completed,
        authorName = null,
        cashSales = sales,
        cardSales = 0L,
        qrSales = 0L,
        accountsReceivableSales = 0L,
        otherSales = 0L,
        foodPurchases = legacyFood,
        alcoholPurchases = 0L,
        consumablesExpense = legacyConsumables,
        utilitiesExpense = 0L,
        electricityExpense = electricity,
        gasExpense = gas,
        waterExpense = water,
        communicationExpense = communication,
        rentExpense = rent,
        accountantFeeExpense = accountant,
        miscellaneousExpense = 0L,
        otherExpense = 0L,
        openingCash = 0L,
        actualClosingCash = 0L,
        customerCount = 1,
        groupCount = 1,
        memo = null,
        createdAt = 1L,
        updatedAt = 1L,
        hasActualClosingCash = false,
    )

    private fun expense(id: String, date: String, category: String, amount: Long) = ExpenseRecord(
        id = id,
        expenseDate = date,
        category = category,
        supplierName = null,
        amount = amount,
        paymentMethod = null,
        memo = null,
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private data class ComparisonFixtureResult(
        val old: BalanceSummary,
        val newReport: NewValues,
        val sales: MetricComparison<Long?>,
        val expenses: MetricComparison<Long?>,
        val referenceCost: MetricComparison<Long?>,
        val grossProfit: MetricComparison<Long?>,
        val referenceCostRate: MetricComparison<BigDecimal?>,
        val fixedCost: MetricComparison<Long?>,
        val breakEven: MetricComparison<Long?>,
    ) {
        val all: List<MetricComparison<*>> = listOf(
            sales,
            expenses,
            referenceCost,
            grossProfit,
            referenceCostRate,
            fixedCost,
            breakEven,
        )

        val unexpectedDifferences: List<MetricComparison<*>> =
            all.filter { it.disposition == ComparisonDisposition.UNEXPECTED_DIFFERENCE }
    }

    private data class NewValues(
        val recordedSales: Long?,
        val recordedExpenses: Long?,
        val referenceCost: Long?,
        val grossProfit: Long?,
        val referenceCostRate: BigDecimal?,
        val fixedCost: Long?,
        val breakEvenSales: Long?,
    )

    private data class MetricComparison<T>(
        val metric: ComparisonMetric,
        val old: T,
        val new: T,
        val disposition: ComparisonDisposition,
        val reason: String?,
    )

    private enum class ComparisonMetric {
        SALES,
        EXPENSES,
        REFERENCE_COST,
        GROSS_PROFIT,
        REFERENCE_COST_RATE,
        FIXED_COST,
        BREAK_EVEN,
    }

    private enum class ComparisonDisposition {
        MATCH,
        EXPECTED_DIFFERENCE,
        NOT_COMPARABLE,
        UNEXPECTED_DIFFERENCE,
    }
}
