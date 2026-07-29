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
) {
    init {
        require(salesSourceCount >= 0)
        require(expenseSourceCount >= 0)
        require(foodPurchaseSourceCount >= 0)
        require(alcoholPurchaseSourceCount >= 0)
        require(fixedCostSourceCount >= 0)
        require(customerCountSourceCount >= 0)
    }
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
)
