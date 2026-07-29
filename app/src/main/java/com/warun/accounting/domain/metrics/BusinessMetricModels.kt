package com.warun.accounting.domain.metrics

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

sealed interface MetricPeriod {
    val startDate: LocalDate
    val endDateInclusive: LocalDate

    data class Daily(val date: LocalDate) : MetricPeriod {
        override val startDate: LocalDate = date
        override val endDateInclusive: LocalDate = date
    }

    data class Monthly(val month: YearMonth) : MetricPeriod {
        override val startDate: LocalDate = month.atDay(1)
        override val endDateInclusive: LocalDate = month.atEndOfMonth()
    }

    data class CustomRange(
        override val startDate: LocalDate,
        override val endDateInclusive: LocalDate,
    ) : MetricPeriod {
        init {
            require(!startDate.isAfter(endDateInclusive)) {
                "startDate must not be after endDateInclusive"
            }
        }
    }
}

data class MetricPeriodState(
    val isComplete: Boolean,
    val containsFutureDatedData: Boolean = false,
)

enum class BusinessMetricType {
    RECORDED_SALES,
    RECORDED_EXPENSES,
    REFERENCE_COST,
    REFERENCE_COST_RATE,
    APPROXIMATE_GROSS_PROFIT,
    RECORDED_FIXED_COST_EQUIVALENT,
    FIXED_COST_RECOVERY_GAP,
    FIXED_COST_RECOVERY_RATE,
    REFERENCE_BREAK_EVEN_SALES,
    BREAK_EVEN_REMAINING,
    AVERAGE_SPEND_PER_CUSTOMER,
}

enum class MetricClassification {
    RECORDED,
    REFERENCE,
    ESTIMATED,
    CONFIRMED,
}

enum class MetricAvailability {
    AVAILABLE,
    PARTIAL_DATA,
    INSUFFICIENT_DATA,
    NOT_AVAILABLE,
}

enum class MissingMetricInput {
    SALES_SOURCE,
    EXPENSE_SOURCE,
    REFERENCE_COST_SOURCE,
    FIXED_COST_SOURCE,
    CUSTOMER_COUNT_SOURCE,
    POSITIVE_SALES,
    POSITIVE_FIXED_COST,
    POSITIVE_CUSTOMER_COUNT,
    COMPLETED_CALENDAR_MONTH,
    VALID_CONTRIBUTION_MARGIN,
    NON_NEGATIVE_INPUT,
    VALUE_WITHIN_LONG_RANGE,
}

enum class MetricWarning {
    INVENTORY_NOT_INCLUDED,
    UNREGISTERED_PURCHASES_NOT_INCLUDED,
    CONSUMABLES_EXCLUDED_FROM_REFERENCE_COST,
    UTILITIES_TREATED_AS_MANAGEMENT_FIXED_COST,
    UNALLOCATED_UTILITIES_USED,
    OWNER_LABOR_COST_EXCLUDED,
    CUSTOMER_COUNT_ZERO_OR_UNKNOWN,
    PARTIAL_PERIOD,
    FUTURE_DATED_DATA_INCLUDED,
    LEGACY_FALLBACK_USED,
    SOURCE_DATA_PARTIAL,
    CANCELLATION_EXCLUSION_NOT_CONFIRMED,
}

enum class MetricCalculationBasis {
    RECORDED_SALES_TOTAL,
    RECORDED_EXPENSE_TOTAL,
    FOOD_AND_ALCOHOL_PURCHASES,
    REFERENCE_COST_DIVIDED_BY_SALES,
    SALES_MINUS_REFERENCE_COST,
    MANAGEMENT_FIXED_COST_CATEGORIES,
    GROSS_PROFIT_MINUS_FIXED_COST,
    GROSS_PROFIT_DIVIDED_BY_FIXED_COST,
    FIXED_COST_DIVIDED_BY_CONTRIBUTION_MARGIN,
    BREAK_EVEN_SALES_MINUS_RECORDED_SALES,
    RECORDED_SALES_DIVIDED_BY_CUSTOMER_COUNT,
}

sealed interface MetricValue {
    data class Amount(val yen: Long) : MetricValue

    /**
     * Unitless ratio. For example, 0.25 represents 25 percent.
     * Display rounding and percentage conversion belong to the UI layer.
     */
    data class Ratio(val value: BigDecimal) : MetricValue
}

sealed interface BreakEvenPosition {
    data class Remaining(val yen: Long) : BreakEvenPosition

    data class Achieved(val excessYen: Long) : BreakEvenPosition
}

sealed interface BusinessMetricResult {
    val type: BusinessMetricType
    val period: MetricPeriod
    val availability: MetricAvailability
    val classification: MetricClassification
    val calculationBasis: Set<MetricCalculationBasis>
    val missingInputs: Set<MissingMetricInput>
    val warnings: Set<MetricWarning>
    val sourceSummary: MetricSourceSummary
    val calculatedAt: Instant
}

data class AmountMetricResult(
    override val type: BusinessMetricType,
    val value: MetricValue.Amount?,
    override val period: MetricPeriod,
    override val availability: MetricAvailability,
    override val classification: MetricClassification,
    override val calculationBasis: Set<MetricCalculationBasis>,
    override val missingInputs: Set<MissingMetricInput>,
    override val warnings: Set<MetricWarning>,
    override val sourceSummary: MetricSourceSummary,
    override val calculatedAt: Instant,
) : BusinessMetricResult {
    init {
        require(type in AMOUNT_METRIC_TYPES)
        require(classification == type.expectedClassification)
        require(availability.hasValue == (value != null))
    }
}

data class RatioMetricResult(
    override val type: BusinessMetricType,
    val value: MetricValue.Ratio?,
    override val period: MetricPeriod,
    override val availability: MetricAvailability,
    override val classification: MetricClassification,
    override val calculationBasis: Set<MetricCalculationBasis>,
    override val missingInputs: Set<MissingMetricInput>,
    override val warnings: Set<MetricWarning>,
    override val sourceSummary: MetricSourceSummary,
    override val calculatedAt: Instant,
) : BusinessMetricResult {
    init {
        require(type in RATIO_METRIC_TYPES)
        require(classification == type.expectedClassification)
        require(availability.hasValue == (value != null))
    }
}

data class BreakEvenRemainingMetricResult(
    val value: BreakEvenPosition?,
    override val period: MetricPeriod,
    override val availability: MetricAvailability,
    override val calculationBasis: Set<MetricCalculationBasis>,
    override val missingInputs: Set<MissingMetricInput>,
    override val warnings: Set<MetricWarning>,
    override val sourceSummary: MetricSourceSummary,
    override val calculatedAt: Instant,
) : BusinessMetricResult {
    override val type: BusinessMetricType = BusinessMetricType.BREAK_EVEN_REMAINING
    override val classification: MetricClassification = MetricClassification.REFERENCE

    init {
        require(availability.hasValue == (value != null))
    }
}

data class BusinessMetricReport(
    val period: MetricPeriod,
    val recordedSales: AmountMetricResult,
    val recordedExpenses: AmountMetricResult,
    val referenceCost: AmountMetricResult,
    val referenceCostRate: RatioMetricResult,
    val approximateGrossProfit: AmountMetricResult,
    val recordedFixedCostEquivalent: AmountMetricResult,
    val fixedCostRecoveryGap: AmountMetricResult,
    val fixedCostRecoveryRate: RatioMetricResult,
    val referenceBreakEvenSales: AmountMetricResult,
    val breakEvenRemaining: BreakEvenRemainingMetricResult,
    val averageSpendPerCustomer: AmountMetricResult,
    val calculatedAt: Instant,
) {
    val results: List<BusinessMetricResult>
        get() = listOf(
            recordedSales,
            recordedExpenses,
            referenceCost,
            referenceCostRate,
            approximateGrossProfit,
            recordedFixedCostEquivalent,
            fixedCostRecoveryGap,
            fixedCostRecoveryRate,
            referenceBreakEvenSales,
            breakEvenRemaining,
            averageSpendPerCustomer,
        )

    init {
        require(results.all { it.period == period })
        require(results.all { it.calculatedAt == calculatedAt })
        require(results.map { it.type }.toSet() == BusinessMetricType.entries.toSet())
    }
}

internal val MetricAvailability.hasValue: Boolean
    get() = this == MetricAvailability.AVAILABLE || this == MetricAvailability.PARTIAL_DATA

internal val BusinessMetricType.expectedClassification: MetricClassification
    get() = when (this) {
        BusinessMetricType.RECORDED_SALES,
        BusinessMetricType.RECORDED_EXPENSES,
        BusinessMetricType.RECORDED_FIXED_COST_EQUIVALENT,
        -> MetricClassification.RECORDED

        else -> MetricClassification.REFERENCE
    }

private val AMOUNT_METRIC_TYPES = setOf(
    BusinessMetricType.RECORDED_SALES,
    BusinessMetricType.RECORDED_EXPENSES,
    BusinessMetricType.REFERENCE_COST,
    BusinessMetricType.APPROXIMATE_GROSS_PROFIT,
    BusinessMetricType.RECORDED_FIXED_COST_EQUIVALENT,
    BusinessMetricType.FIXED_COST_RECOVERY_GAP,
    BusinessMetricType.REFERENCE_BREAK_EVEN_SALES,
    BusinessMetricType.AVERAGE_SPEND_PER_CUSTOMER,
)

private val RATIO_METRIC_TYPES = setOf(
    BusinessMetricType.REFERENCE_COST_RATE,
    BusinessMetricType.FIXED_COST_RECOVERY_RATE,
)
