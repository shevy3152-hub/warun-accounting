package com.warun.accounting.domain.metrics

import java.time.Instant

data class MetricSourceSummary(
    val salesSourceCount: Int,
    val expenseSourceCount: Int,
    val foodPurchaseSourceCount: Int,
    val alcoholPurchaseSourceCount: Int,
    val fixedCostSourceCount: Int,
    val customerCountSourceCount: Int,
    val legacyFallbackUsed: Boolean,
    val cancellationsExcluded: Boolean,
    val fixedCostSourcePartial: Boolean = false,
    val customerCountZeroOrUnknown: Boolean = false,
    val directExpenseSourceCount: Int = 0,
) {
    init {
        require(salesSourceCount >= 0)
        require(expenseSourceCount >= 0)
        require(foodPurchaseSourceCount >= 0)
        require(alcoholPurchaseSourceCount >= 0)
        require(fixedCostSourceCount >= 0)
        require(customerCountSourceCount >= 0)
        require(directExpenseSourceCount >= 0)
        require(expenseSourceCount.toLong() + directExpenseSourceCount <= Int.MAX_VALUE)
    }

    val recordedExpenseSourceCount: Int
        get() = expenseSourceCount + directExpenseSourceCount
}

/**
 * Normalized totals supplied by the data layer.
 *
 * Cancellation exclusion and the legacy fallback contract must be applied before constructing
 * this input. A null amount means that the source is unavailable; an explicit zero means that a
 * source exists and its recorded total is zero.
 */
data class BusinessMetricInput(
    val period: MetricPeriod,
    val periodState: MetricPeriodState,
    val recordedSalesYen: Long?,
    val recordedExpensesYen: Long?,
    val foodPurchasesYen: Long?,
    val alcoholPurchasesYen: Long?,
    val rentYen: Long?,
    val communicationYen: Long?,
    val accountantFeeYen: Long?,
    val electricityYen: Long?,
    val gasYen: Long?,
    val waterYen: Long?,
    val customerCount: Long?,
    val sourceSummary: MetricSourceSummary,
    val calculatedAt: Instant,
    /**
     * Period total of legacy utilities amounts that could not be attributed to electricity, gas,
     * or water. Per-report source selection is completed before this input is constructed, so
     * this total may coexist with allocated utility totals originating from different dates.
     */
    val unallocatedUtilitiesYen: Long? = null,
)
