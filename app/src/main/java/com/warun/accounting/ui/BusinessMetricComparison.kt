package com.warun.accounting.ui

import com.warun.accounting.data.local.ExpenseCategory
import com.warun.accounting.data.metrics.BusinessMetricComparisonSource
import com.warun.accounting.domain.metrics.AmountMetricResult
import com.warun.accounting.domain.metrics.BusinessMetricReport
import com.warun.accounting.domain.metrics.BusinessMetricResult
import com.warun.accounting.domain.metrics.MetricAvailability
import com.warun.accounting.domain.metrics.MetricPeriod
import com.warun.accounting.domain.metrics.RatioMetricResult
import com.warun.accounting.util.ExpenseDateCategoryKey
import com.warun.accounting.util.PreferredExpenseAmountSource
import com.warun.accounting.util.preferredExpenseAmountDetail
import java.math.BigDecimal
import java.math.RoundingMode

enum class ComparisonMetric {
    SALES,
    EXPENSES,
    REFERENCE_COST,
    GROSS_PROFIT,
    REFERENCE_COST_RATE,
    FIXED_COST,
    BREAK_EVEN,
}

enum class ComparisonDisposition {
    MATCH,
    EXPECTED_DIFFERENCE,
    NOT_COMPARABLE,
    UNEXPECTED_DIFFERENCE,
}

data class MetricComparison<T>(
    val metric: ComparisonMetric,
    val old: T,
    val new: T,
    val disposition: ComparisonDisposition,
    val reason: String?,
)

data class BusinessMetricComparisonNewValues(
    val recordedSales: Long?,
    val recordedExpenses: Long?,
    val referenceCost: Long?,
    val grossProfit: Long?,
    val referenceCostRate: BigDecimal?,
    val fixedCost: Long?,
    val breakEvenSales: Long?,
)

data class BusinessMetricComparisonOldAnalysis(
    val estimatedCost: Long?,
    val estimatedCostRate: BigDecimal?,
    val estimatedGrossProfit: Long?,
    val simpleFixedCost: Long?,
    val estimatedBreakEvenSales: Long?,
    val calculationStatus: String,
)

data class BusinessMetricComparisonOldValues(
    val salesTotal: Long,
    val expenseTotal: Long,
    val businessAnalysis: BusinessMetricComparisonOldAnalysis,
)

data class BusinessMetricComparison(
    val old: BusinessMetricComparisonOldValues,
    val newReport: BusinessMetricComparisonNewValues,
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

internal fun MetricPeriod.toBalancePeriod(): BalancePeriod =
    BalancePeriod(start = startDate, end = endDateInclusive)

internal fun expectedBusinessMetricDifferences(
    source: BusinessMetricComparisonSource,
    period: MetricPeriod,
    old: BalanceSummary,
    newReport: BusinessMetricReport,
): Map<ComparisonMetric, String> {
    val fallbackTotal = source.legacyReferenceCostFallbackTotal(period)
    if (fallbackTotal == 0L) return emptyMap()

    val reason =
        "旧経路は食材仕入・酒類仕入のlegacy値をfallbackせず、新経路だけがshared fallbackを適用する既知差分"
    val expected = mutableMapOf<ComparisonMetric, String>()
    val newSales = amount(newReport.recordedSales)
    val newExpenses = amount(newReport.recordedExpenses)
    val newReferenceCost = amount(newReport.referenceCost)
    val newGrossProfit = amount(newReport.approximateGrossProfit)
    val newReferenceCostRate = ratio(newReport.referenceCostRate)
    val newFixedCost = amount(newReport.recordedFixedCostEquivalent)
    val newBreakEven = amount(newReport.referenceBreakEvenSales)

    if (addExactOrNull(old.expenseTotal, fallbackTotal) == newExpenses) {
        expected[ComparisonMetric.EXPENSES] = reason
    }

    val referenceCostDifferenceIsExpected = old.businessAnalysis.estimatedCost?.let { oldCost ->
        addExactOrNull(oldCost, fallbackTotal) == newReferenceCost
    } == true
    if (referenceCostDifferenceIsExpected) {
        expected[ComparisonMetric.REFERENCE_COST] = reason
    }

    if (
        referenceCostDifferenceIsExpected &&
        newSales == old.salesTotal &&
        old.businessAnalysis.estimatedGrossProfit?.let { oldGrossProfit ->
            subtractExactOrNull(oldGrossProfit, fallbackTotal) == newGrossProfit
        } == true
    ) {
        expected[ComparisonMetric.GROSS_PROFIT] = reason
    }

    if (
        referenceCostDifferenceIsExpected &&
        newSales == old.salesTotal &&
        newSales > 0L &&
        newReferenceCost != null &&
        newReferenceCostRate == BigDecimal.valueOf(newReferenceCost).divide(
            BigDecimal.valueOf(newSales),
            12,
            RoundingMode.HALF_UP,
        )
    ) {
        expected[ComparisonMetric.REFERENCE_COST_RATE] = reason
    }

    if (
        referenceCostDifferenceIsExpected &&
        newSales == old.salesTotal &&
        newFixedCost == old.businessAnalysis.simpleFixedCost &&
        newBreakEven != old.businessAnalysis.estimatedBreakEvenSales
    ) {
        expected[ComparisonMetric.BREAK_EVEN] = reason
    }

    return expected
}

private fun BusinessMetricComparisonSource.legacyReferenceCostFallbackTotal(
    period: MetricPeriod,
): Long {
    val balancePeriod = period.toBalancePeriod()
    var total = 0L
    reports
        .asSequence()
        .filter { balancePeriod.contains(it.reportDate) }
        .forEach { report ->
            listOf(
                ExpenseCategory.FoodPurchase to report.foodPurchases,
                ExpenseCategory.AlcoholPurchase to report.alcoholPurchases,
            ).forEach { (category, legacyAmount) ->
                val activeRecordCount = activeExpenses.count { expense ->
                    expense.expenseDate == report.reportDate && expense.category == category
                }
                val detail = preferredExpenseAmountDetail(
                    activeRecordCount = activeRecordCount,
                    activeAmount = 0L,
                    cancellationExists = ExpenseDateCategoryKey(
                        expenseDate = report.reportDate,
                        category = category,
                    ) in cancelledExpenseKeys,
                    legacyAmount = legacyAmount,
                )
                if (detail.source == PreferredExpenseAmountSource.LEGACY_FALLBACK) {
                    total = Math.addExact(total, detail.amount)
                }
            }
        }
    return total
}

private fun addExactOrNull(left: Long, right: Long): Long? =
    try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

private fun subtractExactOrNull(left: Long, right: Long): Long? =
    try {
        Math.subtractExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

/**
 * Shared comparison boundary for the debug diagnostic screen and JVM comparison tests.
 * The legacy values are supplied by the existing BalanceSummary calculation; this function only
 * applies the comparison contract and never changes either aggregation path.
 */
internal fun compareBusinessMetrics(
    old: BalanceSummary,
    newReport: BusinessMetricReport,
    expectedDifferences: Map<ComparisonMetric, String> = emptyMap(),
    notComparable: Map<ComparisonMetric, String> = emptyMap(),
): BusinessMetricComparison {
    val oldValues = old.toComparisonValues()
    val newValues = BusinessMetricComparisonNewValues(
        recordedSales = amount(newReport.recordedSales),
        recordedExpenses = amount(newReport.recordedExpenses),
        referenceCost = amount(newReport.referenceCost),
        grossProfit = amount(newReport.approximateGrossProfit),
        referenceCostRate = ratio(newReport.referenceCostRate),
        fixedCost = amount(newReport.recordedFixedCostEquivalent),
        breakEvenSales = amount(newReport.referenceBreakEvenSales),
    )

    return BusinessMetricComparison(
        old = oldValues,
        newReport = newValues,
        sales = compare(
            metric = ComparisonMetric.SALES,
            old = old.salesTotal,
            new = newValues.recordedSales,
            result = newReport.recordedSales,
            expectedDifferences = expectedDifferences,
            notComparable = notComparable,
        ),
        expenses = compare(
            metric = ComparisonMetric.EXPENSES,
            old = old.expenseTotal,
            new = newValues.recordedExpenses,
            result = newReport.recordedExpenses,
            expectedDifferences = expectedDifferences,
            notComparable = notComparable,
        ),
        referenceCost = compare(
            metric = ComparisonMetric.REFERENCE_COST,
            old = old.businessAnalysis.estimatedCost,
            new = newValues.referenceCost,
            result = newReport.referenceCost,
            expectedDifferences = expectedDifferences,
            notComparable = notComparable,
        ),
        grossProfit = compare(
            metric = ComparisonMetric.GROSS_PROFIT,
            old = old.businessAnalysis.estimatedGrossProfit,
            new = newValues.grossProfit,
            result = newReport.approximateGrossProfit,
            expectedDifferences = expectedDifferences,
            notComparable = notComparable,
        ),
        referenceCostRate = compare(
            metric = ComparisonMetric.REFERENCE_COST_RATE,
            old = old.businessAnalysis.estimatedCostRate?.divide(
                BigDecimal.valueOf(100L),
                12,
                RoundingMode.HALF_UP,
            ),
            new = newValues.referenceCostRate,
            result = newReport.referenceCostRate,
            expectedDifferences = expectedDifferences,
            notComparable = notComparable,
        ),
        fixedCost = compare(
            metric = ComparisonMetric.FIXED_COST,
            old = old.businessAnalysis.simpleFixedCost,
            new = newValues.fixedCost,
            result = newReport.recordedFixedCostEquivalent,
            expectedDifferences = expectedDifferences,
            notComparable = notComparable,
        ),
        breakEven = compare(
            metric = ComparisonMetric.BREAK_EVEN,
            old = old.businessAnalysis.estimatedBreakEvenSales,
            new = newValues.breakEvenSales,
            result = newReport.referenceBreakEvenSales,
            expectedDifferences = expectedDifferences,
            notComparable = notComparable,
        ),
    )
}

private fun BalanceSummary.toComparisonValues(): BusinessMetricComparisonOldValues =
    BusinessMetricComparisonOldValues(
        salesTotal = salesTotal,
        expenseTotal = expenseTotal,
        businessAnalysis = BusinessMetricComparisonOldAnalysis(
            estimatedCost = businessAnalysis.estimatedCost,
            estimatedCostRate = businessAnalysis.estimatedCostRate,
            estimatedGrossProfit = businessAnalysis.estimatedGrossProfit,
            simpleFixedCost = businessAnalysis.simpleFixedCost,
            estimatedBreakEvenSales = businessAnalysis.estimatedBreakEvenSales,
            calculationStatus = businessAnalysis.calculationStatus.name,
        ),
    )

private fun <T> compare(
    metric: ComparisonMetric,
    old: T,
    new: T,
    result: BusinessMetricResult,
    expectedDifferences: Map<ComparisonMetric, String>,
    notComparable: Map<ComparisonMetric, String>,
): MetricComparison<T> {
    val disposition = when {
        old == new -> ComparisonDisposition.MATCH
        metric in notComparable -> ComparisonDisposition.NOT_COMPARABLE
        metric in expectedDifferences -> ComparisonDisposition.EXPECTED_DIFFERENCE
        new == null && result.valueUnavailableForComparison() -> ComparisonDisposition.EXPECTED_DIFFERENCE
        else -> ComparisonDisposition.UNEXPECTED_DIFFERENCE
    }
    val reason = when (disposition) {
        ComparisonDisposition.MATCH -> null
        ComparisonDisposition.NOT_COMPARABLE -> notComparable[metric]
        ComparisonDisposition.EXPECTED_DIFFERENCE ->
            expectedDifferences[metric] ?: defaultExpectedDifferenceReason(result)
        ComparisonDisposition.UNEXPECTED_DIFFERENCE ->
            "旧値と新値が一致しません"
    }
    return MetricComparison(metric, old, new, disposition, reason)
}

private fun BusinessMetricResult.valueUnavailableForComparison(): Boolean =
    availability != MetricAvailability.AVAILABLE

private fun defaultExpectedDifferenceReason(result: BusinessMetricResult): String = buildString {
    append("新経路の値が")
    append(result.availability)
    append("で算出不能")
    if (result.missingInputs.isNotEmpty()) {
        append("（不足: ")
        append(result.missingInputs.joinToString())
        append("）")
    }
}

private fun amount(result: AmountMetricResult): Long? =
    result.value?.yen

private fun ratio(result: RatioMetricResult): BigDecimal? =
    result.value?.value
