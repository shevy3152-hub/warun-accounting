package com.warun.accounting.domain.metrics

import java.time.Instant
import java.time.LocalDate

enum class MetricExpenseCategory {
    FOOD_PURCHASE,
    ALCOHOL_PURCHASE,
    CONSUMABLES,
    OTHER_EXPENSE,
    VEHICLE_TRANSPORT,
    OTHER,
}

data class MetricDailyReportSource(
    val id: String,
    val reportDate: LocalDate,
    val salesYen: Long,
    val customerCount: Long,
    val legacyFoodPurchasesYen: Long = 0L,
    val legacyAlcoholPurchasesYen: Long = 0L,
    val legacyConsumablesExpenseYen: Long = 0L,
    val rentExpenseYen: Long = 0L,
    val communicationExpenseYen: Long = 0L,
    val accountantFeeExpenseYen: Long = 0L,
    val electricityExpenseYen: Long = 0L,
    val gasExpenseYen: Long = 0L,
    val waterExpenseYen: Long = 0L,
    val legacyUtilitiesExpenseYen: Long = 0L,
    val miscellaneousExpenseYen: Long = 0L,
)

data class MetricExpenseSource(
    val id: String,
    val expenseDate: LocalDate,
    val category: MetricExpenseCategory,
    val amountYen: Long,
)

data class MetricExpenseVisibilitySource(
    val expense: MetricExpenseSource,
    val isCancelled: Boolean,
)

/**
 * Broad, single-generation data supplied to the assembler.
 *
 * The assembler performs period extraction. Expense visibility must originate from one visibility
 * query result so active rows and cancellation state cannot represent different generations.
 */
data class BusinessMetricSourceSnapshot(
    val period: MetricPeriod,
    val reports: List<MetricDailyReportSource>,
    val expenseVisibility: List<MetricExpenseVisibilitySource>,
    val evaluationDate: LocalDate,
    val calculatedAt: Instant,
)

enum class AssemblyFailureReason {
    NEGATIVE_SALES,
    NEGATIVE_EXPENSE,
    NEGATIVE_FIXED_COST,
    NEGATIVE_CUSTOMER_COUNT,
    DUPLICATE_DAILY_REPORT_DATE,
    VISIBILITY_SNAPSHOT_INCONSISTENT,
    SOURCE_COUNT_OVERFLOW,
    AMOUNT_OVERFLOW,
}

sealed interface BusinessMetricInputAssemblyResult {
    data class Success(val input: BusinessMetricInput) : BusinessMetricInputAssemblyResult

    data class InvalidSourceData(
        val reasons: Set<AssemblyFailureReason>,
    ) : BusinessMetricInputAssemblyResult

    data class InconsistentSnapshot(
        val reasons: Set<AssemblyFailureReason>,
    ) : BusinessMetricInputAssemblyResult

    data class Overflow(
        val reasons: Set<AssemblyFailureReason>,
    ) : BusinessMetricInputAssemblyResult
}
